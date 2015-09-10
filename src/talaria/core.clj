(ns talaria.core
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [immutant.web.async :as immutant]
            [talaria.delay :as delay]
            [talaria.state :as state]
            [talaria.utils :as utils])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.concurrent LinkedBlockingQueue]))


(defn streamify
  "Expects a string or a stream, returns a stream"
  [msg]
  (if (string? msg)
    (io/input-stream (.getBytes msg "UTF-8"))
    msg))

(defn decode-msg [msg]
  (-> msg
      (streamify)
      ;; TODO: support for custom readers, should be passed into init
      (transit/reader :json)
      (transit/read)))

(defn encode-msg [msg]
  (let [out (ByteArrayOutputStream. 4096)
        w (transit/writer out :json)]
    (transit/write w msg)
    ;; TODO: look into streams and binary streams
    (.toString out)))

(defn recv-queue [tal-state]
  (:recv-queue @tal-state))

(defn get-channel-info [tal-state ch-id]
  (get-in @tal-state [:connections ch-id]))

(defn ch-id [ch]
  (:tal/ch-id (immutant/originating-request ch)))

(defn pop-all [coll-atom]
  (loop [val @coll-atom]
    (if (compare-and-set! coll-atom val (empty val))
      val
      (recur @coll-atom))))

;; TODO: should we wrap callbacks in try/catch blocks?
(defn combine-callbacks [callbacks]
  (reduce (fn [acc cb]
            (if (fn? cb)
              (if (fn? acc)
                (juxt acc cb)
                cb)
              acc))
          callbacks))

(defn send!
  "Low-level function to immediately send messages; expects a vector of messages.
  End users should prefer `queue-msg!`, which takes a single message."
  [tal-state ch-id msg & {:keys [on-success on-error close?]}]
  (assert (vector? msg))
  (if-let [ch (:channel (get-channel-info tal-state ch-id))]
    (let [res (immutant/send! ch
                              (encode-msg msg)
                              {:close? (or close? (utils/ajax-channel? ch))
                               :on-success (fn []
                                             (state/record-send-success tal-state ch-id msg)
                                             (when (fn? on-success)
                                               (on-success)))
                               :on-error (fn [throwable]
                                           (log/errorf throwable "error sending message for channel with id %s" ch-id)
                                           (state/record-send-error tal-state ch-id msg throwable)
                                           (when (fn? on-error)
                                             (on-error throwable)))})]
      (if res
        (state/record-send tal-state ch-id msg)
        (when (fn? on-error)
          (on-error (Exception. "channel is closed"))))
      res)
    (when (fn? on-error)
      (on-error (Exception. "channel is closed"))
      nil)))

(defn send-queued!
  "Low-level function to flush the send queue. End users shouldn't normally call this function."
  [tal-state ch-id]
  (let [ch-info (get-channel-info tal-state ch-id)]
    (when (:channel ch-info)
      (let [jobs (pop-all (:delay-jobs ch-info))
            messages (pop-all (:send-queue ch-info))]
        (doseq [job jobs]
          (.cancel job false))
        (when (seq messages)
          (send! tal-state ch-id (mapv :msg messages)
                 :on-success (combine-callbacks (map :on-success messages))
                 :on-error (combine-callbacks (map :on-error messages))
                 :close? (some :close? messages)))))))

(defn schedule-send
  "Schedules send, allowing a short period for other messages to enter the queue."
  [tal-state ch-id delay-ms]
  (let [job (delay/delay-fn (:async-pool @tal-state)
                            delay-ms
                            #(send-queued! tal-state ch-id))
        delay-jobs (get-in @tal-state [:connections ch-id :delay-jobs])]
    (when delay-jobs
      (swap! delay-jobs conj job))))

(defn queue-msg!
  "Queues message to send to client. msg should normally be a map with `:op` and `:data` keys."
  [tal-state ch-id {:keys [msg on-success on-error close?] :as msg-props}]
  (when-let [channel-info (get-channel-info tal-state ch-id)]
    (swap! (:send-queue channel-info) conj msg-props)
    (schedule-send tal-state ch-id (:send-delay channel-info))))

(defn handle-message [tal-state msg]
  (.put (:recv-queue @tal-state) msg))

(defn setup-ping-job
  "Runs ping job at most every (/ ping-ms 2) and at least every (+ ping-ms (/ ping-ms 2)).
   Trading a looser ping interval for a single repeating function."
  [tal-state ch-id]
  (delay/repeat-fn (:async-pool @tal-state)
                   (long (/ (:ping-ms @tal-state) 2))
                   #(when-let [ch-info (get-channel-info tal-state ch-id)]
                      (when (and (empty? @(:send-queue ch-info))
                                 (or (nil? (:last-send-time ch-info))
                                     (< (/ (:ping-ms @tal-state) 2)
                                        (- (.getTime (java.util.Date.))
                                           (.getTime (:last-send-time ch-info))))))
                        (queue-msg! tal-state ch-id {:op :tal/ping})))))

(defn cancel-ping-job [tal-state ch-id]
  (some-> (get-channel-info tal-state ch-id) :ping-job (.cancel false)))

(defn ws-open-handler [tal-state]
  (fn [ch]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)
          ping-job (setup-ping-job tal-state id)]
      (state/add-channel tal-state id ch ping-job)
      (handle-message tal-state {:op :tal/channel-open
                                 :tal/ch ch
                                 :tal/ch-id id
                                 :tal/ring-req (immutant/originating-request ch)}))))

(defn ws-error-handler [tal-state]
  (fn [ch throwable]
    (let [id (ch-id ch)]
      (log/errorf throwable "error for channel with id %s" id)
      (state/record-error tal-state id throwable))))

(defn remove-by-id [tal-state id data ring-req]
  (let [msg-ch (:msg-ch @tal-state)
        ch (get-channel-info tal-state id)]
    (state/remove-channel tal-state id)
    (handle-message tal-state
                    {:op :tal/channel-close
                     :data data
                     :tal/ch-id id
                     :tal/ch ch
                     :tal/ring-req ring-req})))

(defn ws-close-handler [tal-state]
  (fn [ch {:keys [code reason] :as args}]
    (let [id (ch-id ch)]
      (log/infof "channel with id %s closed %s" id args)
      (cancel-ping-job tal-state id)
      (remove-by-id tal-state id args (immutant/originating-request ch)))))


(defn ws-msg-handler [tal-state]
  (fn [ch msg]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)
          messages (decode-msg msg)]
      (state/record-msg tal-state id msg)
      (doseq [msg messages]
        (handle-message tal-state (assoc msg
                                         :tal/ch ch
                                         :tal/ch-id id
                                         :tal/ring-req (immutant/originating-request ch)))))))

(defn handle-ajax-msg [tal-state ch-id msg ring-req]
  (if-let [channel-info (get-channel-info tal-state ch-id)]
    (let [msg-ch (:msg-ch @tal-state)
          messages (decode-msg msg)]
      (state/record-msg tal-state ch-id msg)
      (doseq [msg messages]
        (handle-message tal-state (assoc msg
                                         :tal/ch (:channel channel-info)
                                         :tal/ch-id ch-id
                                         :tal/ring-req ring-req)))
      :sent)
    :channel-closed))

(defn schedule-ajax-cleanup [tal-state ch-id ring-req channel-count]
  (delay/delay-fn (:async-pool @tal-state)
                  5000
                  ;; remove the channel if it doesn't reconnect
                  #(when (= channel-count (get-in @tal-state [:connections ch-id :ajax-channel-count]))
                     (log/infof "channel with id %s closed" ch-id)
                     (cancel-ping-job tal-state ch-id)
                     (remove-by-id tal-state ch-id {} ring-req))))

(defn handle-ajax-open [tal-state ch-id ring-req]
  (let [msg-ch (:msg-ch @tal-state)]
    (let [ping-job (setup-ping-job tal-state ch-id)
          ch-count (get-in (state/add-channel tal-state ch-id nil ping-job)
                           [:connections ch-id :ajax-channel-count])]
      (schedule-ajax-cleanup tal-state ch-id ring-req ch-count))
    (handle-message tal-state {:op :tal/channel-open
                               :tal/ch nil
                               :tal/ch-id ch-id
                               :tal/ring-req ring-req})))

(defn ajax-channel-handler [tal-state ch-id]
  (fn [ch]
    (let [msg-ch (:msg-ch @tal-state)
          new-state (state/replace-ajax-channel tal-state ch-id ch)]
      (try
        (some-> new-state
          (get-in [:connections ch-id :previous-channel])
          (immutant/close))
        (catch java.lang.IllegalStateException e
          nil))
      (send-queued! tal-state ch-id))))

(defn ajax-close-handler [tal-state]
  (fn [ch {:keys [code reason] :as args}]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)]
      (let [new-state (state/remove-ajax-channel tal-state id ch)]
        (when-not (get-in new-state [:connections id :channel])
          (schedule-ajax-cleanup tal-state
                                 id
                                 (immutant/originating-request ch)
                                 (get-in new-state [:connections id :ajax-channel-count])))))))

;; Debug helpers
(defn send-all
  "Debug helper to send a message to every connected client"
  [tal-state msg]
  (doseq [[id _] (:connections @tal-state)]
    (send! tal-state id msg)))

(defn all-channels
  "Debug helper to get a list of all connected channels"
  [tal-state]
  (map (comp :channel second) (:connections @tal-state)))

(defn all-ch-ids
  "Debug helper to get all connected channel ids"
  [tal-state]
  (keys (:connections @tal-state)))
