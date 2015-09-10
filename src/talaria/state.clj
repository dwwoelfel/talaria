(ns talaria.state
  "Functions that modify talaria state. All functions return the new version of the state."
  (:require [talaria.utils :as utils])
  (:import [java.util.Date]))

(defn add-channel
  "Adds channel to state, given an id. Updates global stats."
  [tal-state id ch ping-job]
  (dosync
   (commute tal-state (fn [s]
                        (assert (not (get-in s [:connections id :channel]))
                                (str "Tried to add a duplicate channel for " id))
                        (-> s
                          (assoc-in [:connections id] {:channel ch
                                                       ;; store delay here so that we can optimize based on
                                                       ;; latency
                                                       :send-delay (if (or (nil? ch)
                                                                           (utils/ajax-channel? ch))
                                                                     (:ajax-delay s)
                                                                     (:ws-delay s))
                                                       :delay-jobs (atom #{})
                                                       :send-queue (atom [])
                                                       :ping-job ping-job})
                          (update-in [:stats :connection-count] (fnil inc 0)))))))

(defn replace-ajax-channel
  "Replaces or adds new ajax channel to state, given an id. Updates global stats."
  [tal-state id ch]
  (dosync
   (commute tal-state (fn [s]
                        (let [previous-ch (get-in s [:connections id :channel])]
                          (assert (get-in s [:connections id]))
                          (-> s
                            (assoc-in [:connections id :channel] ch)
                            (assoc-in [:connections id :previous-channel] previous-ch)
                            (update-in [:connections id :ajax-channel-count] (fnil inc 0))))))))

(defn record-error
  "Records error for a given channel and updates global stats"
  [tal-state id error]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (assoc :last-error error)
                                                                               (update-in [:error-count] (fnil inc 0)))))
                          (assoc-in [:stats :last-error] error)
                          (update-in [:stats :error-count] (fnil inc 0)))))))

(defn remove-channel
  "Removes channel and updates global stats"
  [tal-state id]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (update-in [:connections] dissoc id)
                          (update-in [:stats :connection-count] (fnil dec 0)))))))

(defn remove-ajax-channel
  "Removes channel and updates global stats"
  [tal-state id ch]
  (dosync
   (commute tal-state (fn [s]
                        (if (= (get-in s [:connections id :channel]) ch)
                          (utils/dissoc-in s [:connections id :channel])
                          s)))))

(defn record-msg
  "Updates global stats for receiving a new message"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:receive-count] (fnil inc 0))
                                                                               (assoc :last-recv-time (java.util.Date.)))))
                          (update-in [:stats :receive-count] (fnil inc 0)))))))

(defn record-send
  "Updates global stats for sending a new message"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:send-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil inc 0))
                                                                               (assoc :last-send-time (java.util.Date.)))))
                          (update-in [:stats :send-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil inc 0)))))))

(defn record-send-success
  "Updates global stats for successfully sending a message"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:send-success-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil dec 0)))))
                          (update-in [:stats :send-success-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil dec 0)))))))

(defn record-send-error
  "Updates global stats for an error while sending a message"
  [tal-state id msg error]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (assoc :last-send-error error)
                                                                               (update-in [:send-error-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil dec 0)))))
                          (assoc-in [:stats :last-send-error] error)
                          (update-in [:stats :send-error-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil dec 0)))))))
