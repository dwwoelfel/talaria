(ns talaria.api
  (:require [cognitect.transit :as transit]
            [goog.Uri]
            [talaria.core :as core]
            [talaria.queue :as queue]))

(defn start-message-consumer [tal-state handler]
  (let [recv-queue (:recv-queue @tal-state)]
    (queue/add-consumer recv-queue ::message-consumer #(core/consume-recv-queue tal-state handler))))

(defn init [& [{:keys [secure? host port tab-id csrf-token params ;; url parts
                       ws-delay long-poll-delay keep-alive-ms
                       transit-reader transit-writer
                       on-open on-close on-error on-reconnect
                       ;; optional handler, if not provided then client needs to call start-message-consumer elsewhere
                       message-handler
                       test-long-polling?]
                :or {keep-alive-ms (* 1000 60 5)
                     ;; short send delay to let things build up in the queue
                     long-poll-delay 150
                     ws-delay 30}
                :as opts}]]
  (let [ ;; Compute defaults for connection url
        url-parts {:secure (if (contains? opts :secure?)
                             secure?
                             (= "https" (.getScheme (goog.Uri. js/window.location.href))))
                   :host (if host host (.getDomain (goog.Uri. js/window.location.href)))
                   :port (if port port (.getPort (goog.Uri. js/window.location.href)))
                   :tab-id (if tab-id tab-id (random-uuid))
                   :csrf-token csrf-token
                   :params params}

        transit-reader (or transit-reader (transit/reader :json))
        transit-writer (or transit-writer (transit/writer :json))

        send-queue (queue/new-queue)
        recv-queue (queue/new-queue)
        tal-state (atom {:keep-alive-ms keep-alive-ms
                         :open? false
                         :send-queue send-queue
                         :recv-queue recv-queue
                         :callbacks {}
                         :transit-writer transit-writer
                         :transit-reader transit-reader})
        common-setup-options {:url-parts url-parts
                              :on-open on-open
                              :on-error on-error
                              :on-reconnect on-reconnect}]
    (if (and js/window.WebSocket
             (not test-long-polling?))
      (core/setup-ws tal-state (assoc common-setup-options :delay ws-delay))
      (core/setup-ajax tal-state (assoc common-setup-options :delay long-poll-delay)))
    (when (fn? message-consumer)
      (start-message-consumer tal-state message-handler))

    tal-state))

(defn shutdown [tal-state]
  (core/close-connection tal-state)
  (core/shutdown-send-queue tal-state))

(def queue-msg! core/queue-msg!)
