(ns talaria.dev
  (:require [clojure.tools.nrepl.server :refer (start-server stop-server)]
            [clojure.tools.logging :as log]
            [compojure.core :refer (routes GET POST)]
            [compojure.route]
            [cider.nrepl]
            [immutant.web :as web]
            [immutant.web.async :as immutant]
            [talaria.api :as tal]
            [talaria.routes :as tal-routes]
            [talaria.view :as view]
            [http.async.client :as client]))

(defn route-handler [tal-state]
  (let [ws-setup (tal-routes/websocket-setup tal-state)
        ajax-poll (tal-routes/ajax-poll tal-state)
        ajax-send (tal-routes/ajax-send tal-state)]
    (routes
     (GET "/" req
          {:status 200 :body (view/body)})
     (GET "/talaria" req (ws-setup req))
     (GET "/talaria/ajax-poll" req (ajax-poll req))
     (POST "/talaria/ajax-send" req (ajax-send req))
     (compojure.route/resources "/" {:root "public"
                                     :mime-types {:svg "image/svg"}})
     (fn [req]
       {:status 404
        :body "<body>Sorry, we couldn't find that page. <a href='/'>Back to home</a>.</body>"}))))

(defonce last-req (atom nil))
(defonce last-resp (atom nil))

(defn debug-middleware [handler]
  (fn [req]
    (reset! last-req req)
    (let [resp (handler req)]
      (reset! last-resp resp))))

(defn handler [tal-state]
  (-> (route-handler tal-state)
      (debug-middleware)
      (tal-routes/wrap-session-id)))

(defn nrepl-port []
  (if (System/getenv "NREPL_PORT")
    (Integer/parseInt (System/getenv "NREPL_PORT"))
    3005))

(defn queue-handler-dispatch [tal-state msg]
  (:op msg))

(defmulti recv-queue-handler #'queue-handler-dispatch)

(defmethod recv-queue-handler :default
  [tal-state msg]
  (log/info (dissoc msg :tal/ch :tal/ring-req)))

(defmethod recv-queue-handler :tal-dev/echo
  [tal-state msg]
  (tal/queue-reply! tal-state msg (:data msg)))

(defmethod recv-queue-handler :tal-dev/async-echo
  [tal-state msg]
  (tal/queue-msg! tal-state (:tal/ch-id msg) {:op :tal-dev/async-echo :data (:data msg)}))

(defmethod recv-queue-handler :tal-dev/inc
  [tal-state msg]
  (tal/queue-msg! tal-state (:tal/ch-id msg) {:op :tal-dev/inc :data (inc (Integer/parseInt (:data msg)))}))

(defn init []
  (let [port (nrepl-port)]
    (println "Starting nrepl on port" port)
    (def nrepl-server (start-server :port port :handler cider.nrepl/cider-nrepl-handler))
    (println "Starting web server on port 4569")
    (def tal-state (tal/init))
    (def web-server (web/server (web/run
                                  (handler tal-state)
                                  {:port 4569
                                   :host "0.0.0.0"})))
    (def tal-handler (future (let [q (tal/get-recv-queue tal-state)]
                               (while true
                                 (let [msg (.take q)]
                                   (try
                                     (recv-queue-handler tal-state msg)
                                     (catch Exception e
                                       (log/error e "Error in tal handler"))
                                     (catch AssertionError e
                                       (log/error e "Error in tal handler"))))))))))

(defn restart-web []
  (.stop web-server)
  (def web-server (web/server (web/run
                                  (handler tal-state)
                                  {:port 4569
                                   :host "0.0.0.0"}))))


(defn -main []
  (init))
