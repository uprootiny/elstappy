(ns electric-fiddle.ring-middleware
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [contrib.assert :refer [check]]
   [contrib.template :refer [template]]
   [ring.middleware.basic-authentication :as auth]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.cookies :as cookies]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as res]))

(defn authenticate [username _password] username) ; demo (accept-all) authentication

(defn wrap-demo-authentication "A Basic Auth example. Accepts any username/password and store the username in a cookie."
  [next-handler]
  (-> (fn [ring-req]
        (let [res (next-handler ring-req)]
          (if-let [username (:basic-authentication ring-req)]
            (res/set-cookie res "username" username {:http-only true})
            res)))
    (cookies/wrap-cookies)
    (auth/wrap-basic-authentication authenticate)))

(defn wrap-authenticated-request [next-handler]
  (fn [ring-request]
    (next-handler (auth/basic-authentication-request ring-request authenticate))))

(defn wrap-demo-router "A basic path-based routing middleware"
  [next-handler]
  (fn [ring-req]
    (case (:uri ring-req)
      "/auth" (let [response  ((wrap-demo-authentication next-handler) ring-req)]
                (if (= 401 (:status response)) ; authenticated?
                  response                     ; send response to trigger auth prompt
                  (-> (res/status response 302) ; redirect
                    (res/header "Location" (get-in ring-req [:headers "referer"]))))) ; redirect to where the auth originated
      ;; For any other route, delegate to next middleware
      (next-handler ring-req))))

(defn get-modules [manifest-path]
  (when-let [manifest (io/resource manifest-path)]
    (let [manifest-folder (when-let [folder-name (second (rseq (str/split manifest-path #"\/")))]
                            (str "/" folder-name "/"))]
      (->> (slurp manifest)
        (edn/read-string)
        (reduce (fn [r module] (assoc r (keyword "hyperfiddle.client.module" (name (:name module)))
                                 (str manifest-folder (:output-name module)))) {})))))

(defn wrap-index-page
  "Server the `index.html` file with injected javascript modules from `manifest.edn`.
`manifest.edn` is generated by the client build and contains javascript modules
information."
  [next-handler config]
  (fn [ring-req]
    (if-let [response (res/resource-response (str (check string? (:resources-path config)) "/index.html"))]
      (if-let [bag (merge config (get-modules (check string? (:manifest-path config))))]
        (-> (res/response (template (slurp (:body response)) bag)) ; TODO cache in prod mode
          (res/content-type "text/html") ; ensure `index.html` is not cached
          (res/header "Cache-Control" "no-store")
          (res/header "Last-Modified" (get-in response [:headers "Last-Modified"])))
        (-> (res/not-found (pr-str ::missing-shadow-build-manifest)) ; can't inject js modules
          (res/content-type "text/plain")))
      ;; index.html file not found on classpath
      (next-handler ring-req))))

(defn not-found-handler [_ring-request]
  (-> (res/not-found "Not found")
    (res/content-type "text/plain")))

(defn http-middleware [config]
  ;; these compose as functions, so are applied bottom up
  (-> not-found-handler
    (wrap-index-page config) ; 5. otherwise fallback to default page file
    (wrap-resource (:resources-path config)) ; 4. serve static file from classpath
    (wrap-content-type) ; 3. detect content (e.g. for index.html)
    (wrap-demo-router) ; 2. route
    ))