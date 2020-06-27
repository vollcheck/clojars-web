(ns clojars.auth
  (:require
   [cemerick.friend :as friend]
   [cemerick.friend.credentials :as creds]
   [cemerick.friend.util :as friend.util]
   [cemerick.friend.workflows :as workflows]
   [clojars.db :as db]
   [clojars.event :as event]
   [clojars.util :as util]
   [clojure.string :as str]
   [one-time.core :as ot]
   [ring.util.request :as req]
   [clojars.log :as log]))

(defn try-account [f]
  (f (:username (friend/current-authentication))))

(defn with-account [f]
  (friend/authenticated (try-account f)))

(defn authorized-admin? [db account group]
  (when account
    (let [adminnames (db/group-adminnames db group)
          allnames (db/group-allnames db group)]
      (or (some #{account} adminnames) (empty? allnames)))))

(defn authorized-member? [db account group]
  (when account
    (some #{account} (db/group-membernames db group))))

(defn require-admin-authorization [db account group f]
  (if (authorized-admin? db account group)
    (f)
    (friend/throw-unauthorized friend/*identity*
      {:cemerick.friend/required-roles group})))

(defn- get-param
  [key form-params params]
  (or (get form-params (name key)) (get params key "")))

;; copied from cemerick.friend/workflows and modified to support MFA
(defn interactive-form-with-mfa-workflow
  [& {:keys [redirect-on-auth?] :as form-config
      :or {redirect-on-auth? true}}]
  (fn [{:keys [request-method params form-params] :as request}]
    (when (and (= (friend.util/gets :login-uri
                                    form-config
                                    (::friend/auth-config request))
                  (req/path-info request))
               (= :post request-method))
      (let [creds {:username (get-param :username form-params params)
                   :password (get-param :password form-params params)
                   :otp (get-param :otp form-params params)}
            {:keys [username password]} creds]
        (if-let [user-record (and username password
                                  ((friend.util/gets :credential-fn
                                                     form-config
                                                     (::friend/auth-config request))
                                   (with-meta creds {::friend/workflow :interactive-form})))]
          (workflows/make-auth user-record
                               {::friend/workflow :interactive-form
                                ::friend/redirect-on-auth? redirect-on-auth?})
          ((or (friend.util/gets :login-failure-handler
                                 form-config
                                 (::friend/auth-config request))
               #'workflows/interactive-login-redirect)
           (update-in request [::friend/auth-config] merge form-config)))))))

(defn token-credential-fn [db]
  (fn [{:keys [username password]}]
    (log/with-context {:tag :authentication
                       :username username
                       :type :token}
      (if-let [token (and password
                          (->> (db/find-user-tokens-by-username db username)
                               (remove :disabled)
                               (some #(when (creds/bcrypt-verify password (:token %)) %))))]
        (do
          (db/set-deploy-token-used db (:id token))
          (log/info {:status :success})
          {:username username
           :token token})
        (log/info {:status :failed
                   :reason :invalid-token})))))

(defn valid-totp-token?
  [otp {:as _user :keys [otp_secret_key]}]
  (when-let [otp-n (-> otp
                       (str/replace #"\s+" "")
                       (util/parse-long))]
    (ot/is-valid-totp-token? otp-n otp_secret_key)))

(defn validate-otp
  [db
   {:as user
    recovery-code :otp_recovery_code
    username :user}
   otp]
  (if (creds/bcrypt-verify otp recovery-code)
    (do
      (db/disable-otp! db username)
      (event/emit :mfa-deactivated {:username username
                                    :source :recovery-code})
      true)
    (valid-totp-token? otp user)))

(defn password-credential-fn [db]
  (fn [{:keys [username password otp]}]
    (log/with-context {:tag :authentication
                       :username username
                       :type :password
                       :otp? (boolean otp)}
      (if (not (str/blank? password))
        (let [{:as user :keys [otp_active]} (db/find-user db username)]
          (if (and (not (str/blank? (:password user)))
                     (creds/bcrypt-verify password (:password user))
                     (or (not otp_active)
                         (validate-otp db user otp)))
            (do
              (log/info {:status :success})
              {:username username})
            (log/info {:status :failed
                       :reason :password-or-otp-incorrect})))
        (log/info {:status :failed
                   :reason :password-blank})))))
