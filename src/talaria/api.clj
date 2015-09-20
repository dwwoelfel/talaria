(ns talaria.api
  (:require [talaria.delay :as delay]
            [talaria.core :as core])
  (:import [java.util.concurrent LinkedBlockingQueue]))

(defn init
  "Starts talaria and returns talaria state. The talaria state is required to
   send and receive messages.
   The user of the library is expected to set up a handler for the recv-queue,
   otherwise the queue will grow without bound."
  [& {:keys [ws-delay ajax-delay ping-ms ch-id-fn
             transit-writer-opts transit-reader-opts]
      :or {ws-delay 30
           ajax-delay 150
           ping-ms (* 1000 60 5)
           ;;ch-id-fn
           }}]
  (let [;; Received messages go on the recv-queue
        recv-queue (LinkedBlockingQueue.)
        ;; Handles scheduling sends, pings, and ajax cleanups
        async-pool (delay/make-pool!)
        tal-state (ref {:connections {}
                        :open? true
                        :recv-queue recv-queue
                        :async-pool async-pool
                        :ws-delay ws-delay
                        :ajax-delay ajax-delay
                        :ping-ms ping-ms
                        :stats {}
                        :transit-reader-opts transit-reader-opts
                        :transit-writer-opts transit-writer-opts})]
    tal-state))

(defn shutdown [tal-state]
  (assert false "This function is just a stub, still need to implement shutdown"))

(defn get-recv-queue [tal-state]
  (:recv-queue @tal-state))

(defn queue-msg!
  "Public interface for sending messages to the client. msg should normally be a
   map with `:op` and `:data` keys."
  [tal-state ch-id msg & {:keys [on-success on-error close?]}]
  (core/queue-msg! tal-state ch-id {:msg msg
                                    :on-success on-success
                                    :on-error on-error
                                    :close? close?}))

(defn queue-reply!
  "Public interface for sending messages to the client. reply-data can be arbitrary
  data and will be passed directly to the callback on the client."
  [tal-state original-msg reply-data & {:keys [on-success on-error close?]}]
  (assert (:tal/cb-id original-msg)
          "Message must register a callback handler on the client-side to send reply")
  (core/queue-msg! tal-state (:tal/ch-id original-msg) {:msg {:op :tal/reply
                                                              :data reply-data
                                                              :tal/cb-id (:tal/cb-id original-msg)
                                                              :on-success on-success
                                                              :on-error on-error
                                                              :close? close?}}))
