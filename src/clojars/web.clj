(ns clojars.web
  (:require
   [cemerick.friend :as friend]
   [cemerick.friend.workflows :as workflows]
   [clojars.auth :as auth :refer [try-account]]
   [clojars.config :refer [config]]
   [clojars.errors :refer [wrap-exceptions]]
   [clojars.friend.oauth.github :as github]
   [clojars.friend.oauth.gitlab :as gitlab]
   [clojars.friend.registration :as registration]
   [clojars.http-utils :refer [wrap-secure-session wrap-additional-security-headers]]
   [clojars.log :as log]
   [clojars.middleware :refer [wrap-ignore-trailing-slash]]
   [clojars.routes.api :as api]
   [clojars.routes.artifact :as artifact]
   [clojars.routes.group :as group]
   [clojars.routes.repo :as repo]
   [clojars.routes.session :as session]
   [clojars.routes.token :as token]
   [clojars.routes.token-breach :as token-breach]
   [clojars.routes.user :as user]
   [clojars.web.browse :refer [browse]]
   [clojars.web.common :refer [html-doc]]
   [clojars.web.dashboard :refer [dashboard index-page]]
   [clojars.web.safe-hiccup :refer [raw]]
   [clojars.web.search :refer [search]]
   [clojure.java.io :as io]
   [compojure.core :refer [ANY context GET PUT routes]]
   [compojure.route :refer [not-found]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.defaults :as ring-defaults]
   [ring.middleware.flash :refer [wrap-flash]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]))

(defn try-parse-page
  "Will throw a targeted error if maybe-page doesn't parse as an integer."
  [maybe-page]
  (try
    (Integer/parseInt maybe-page)
    (catch NumberFormatException _nfe
      (throw (ex-info
              "page must be an integer"
              {:report? false
               :title "Bad Request"
               :error-message "The page query parameter must be an integer."
               :status 400})))))

(defn- main-routes [db stats search-obj mailer]
  (routes
   (GET "/" _
        (try-account
         #(if %
            (dashboard db %)
            (index-page db stats %))))
   (GET "/search" {:keys [params]}
        (try-account
         #(let [validated-params (if (:page params)
                                   (assoc params :page (try-parse-page (:page params)))
                                   params)]
            (search search-obj % validated-params))))
   (GET "/projects" {:keys [params]}
        (try-account
         #(let [validated-params (if (:page params)
                                   (assoc params :page (try-parse-page (:page params)))
                                   params)]
            (browse db % validated-params))))
   (GET "/security" []
        (try-account
         #(html-doc "Security" {:account %}
                    (raw (slurp (io/resource "security.html"))))))
   (GET "/dmca" []
        (try-account
         #(html-doc "DMCA" {:account %}
                    (raw (slurp (io/resource "dmca.html"))))))
   session/routes
   (group/routes db)
   (artifact/routes db stats)
   ;; user routes must go after artifact routes
   ;; since they both catch /:identifier
   (user/routes db mailer)
   (token/routes db)
   (api/routes db stats)
   (GET "/error" _ (throw (Exception. "What!? You really want an error?")))
   (PUT "*" _ {:status 405 :headers {} :body "Did you mean to use /repo?"})
   (ANY "*" _
        (try-account
         #(not-found
           (html-doc "Page not found" {:account %}
                     [:div.small-section
                      [:h1 "Page not found"]
                      [:p "Thundering typhoons!  I think we lost it.  Sorry!"]]))))))

(def ^:private defaults-config
  (-> ring-defaults/secure-site-defaults
      ;; Be more strict than the default; we never want to be frame-embedded
      (assoc-in [:security :frame-options] :deny)
      ;; we handle this in nginx
      (update :security dissoc :ssl-redirect)
      ;; We have our own session impl in http-utils
      (dissoc :session)))

(defn clojars-app
  [{:keys [db
           error-reporter
           http-client
           github
           gitlab
           mailer
           search
           stats
           storage]}]
  (let [db (:spec db)]
    (routes
     (-> (context
          "/repo" _
          (-> (repo/routes storage db search)
              (friend/authenticate
               {:credential-fn (auth/token-credential-fn db)
                :workflows [(workflows/http-basic :realm "clojars")]
                :allow-anon? false
                :unauthenticated-handler
                (partial workflows/http-basic-deny "clojars")})
              (repo/wrap-reject-non-token db)
              (repo/wrap-exceptions error-reporter)
              (repo/wrap-file (:repo (config)))
              (log/wrap-request-context)
              (repo/wrap-reject-double-dot)))
         (wrap-secure-session))
     (-> (token-breach/routes db)
         (wrap-exceptions error-reporter)
         (log/wrap-request-context))
     (-> (main-routes db stats search mailer)
         (friend/authenticate
          {:credential-fn (auth/password-credential-fn db)
           :workflows [(auth/interactive-form-with-mfa-workflow)
                       (registration/workflow db)
                       (github/workflow github http-client db)
                       (gitlab/workflow gitlab http-client db)]})
         (wrap-exceptions error-reporter)
         (log/wrap-request-context)
         ;; Use flash directly since we have custom session logic, so can't use
         ;; ring-defaults' session support
         (wrap-flash)
         (ring-defaults/wrap-defaults defaults-config)
         (wrap-additional-security-headers)
         (wrap-secure-session)
         (wrap-content-type)
         (wrap-not-modified)
         (wrap-ignore-trailing-slash)))))
