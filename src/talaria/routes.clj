(ns talaria.routes
  (:require [talaria.core :as tal]
            [immutant.web.async :as immutant])
  (:import [java.util UUID]))

(def session-id-key ::session-id)

(defn default-ch-id-fn [req]
  (let [session-id (get-in req [:session session-id-key])
        tab-id (some->> req
                        :query-string
                        (re-find #"tab-id=([^&]+)($|&)")
                        second)]
    (assert session-id (str "Session id should be present if you're using the default channel id"
                            " function. Are you including the wrap-session-id middleware?"))
    (assert tab-id (str "tab-id should be passed as a query param if you're using the default"
                        " channel id function."))

    (str session-id "-" tab-id)))

(defn assoc-session-id [req response sente-id]
  (if (= (get-in req [:session session-id-key]) sente-id)
    response
    (-> response
      (assoc :session (:session response (:session req)))
      (assoc-in [:session session-id-key] sente-id))))

(defn wrap-session-id [handler]
  (fn [req]
    (let [session-id (or (get-in req [:session session-id-key])
                         (str (UUID/randomUUID)))]
      (if-let [response (-> req
                            (assoc-in [:session session-id-key] session-id)
                            handler)]
        (assoc-session-id req response session-id)))))

(defn compute-channel-id [tal-state req]
  (let [ch-id-fn (:ch-id-fn @tal-state)]
    (if (fn? ch-id-fn)
      (ch-id-fn req)
      (default-ch-id-fn req))))

(defn websocket-setup [tal-state]
  (fn [req]
    (def myreq req)
    (if (:websocket? req)
      (let [channel-id (compute-channel-id tal-state req)]
        (immutant/as-channel (assoc req :tal/ch-id channel-id)
                             {:on-open (tal/ws-open-handler tal-state)
                              :on-close (tal/ws-close-handler tal-state)
                              :on-message (tal/ws-msg-handler tal-state)
                              :on-error (tal/ws-error-handler tal-state)}))
      {:status 400 :body "WebSocket headers not present"})))

(defn ajax-poll [tal-state]
  (fn [req]
    (let [channel-id (compute-channel-id tal-state req)]
      (if (get-in req [:params "open?"])
        (do (tal/handle-ajax-open tal-state channel-id req)
            {:status 200 :body "connected"})
        (immutant/as-channel (assoc req :tal/ch-id channel-id)
                             {:on-open (tal/ajax-channel-handler tal-state channel-id)
                              :on-close (tal/ajax-close-handler tal-state)
                              :on-error (tal/ws-error-handler tal-state)})))))

(defn ajax-send [tal-state]
  (fn [req]
    (let [channel-id (compute-channel-id tal-state req)
          res (tal/handle-ajax-msg tal-state channel-id (:body req) req)]
      (case res
        :sent {:status 200 :body ""}
        :channel-closed {:status 400 :body "channel-closed"}))))
