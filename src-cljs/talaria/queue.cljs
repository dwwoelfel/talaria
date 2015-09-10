(ns talaria.queue
  (:refer-clojure :exclude (pop!))
  (:require [talaria.utils :as utils]))

;; Create a queue with `new-queue`, add things with `enqueue!` and remove things with `pop!` or `pop-all!`
;; Get notified when new things are added to the queue with `add-consumer`.
;; It might be surprising that your consumer is given the queue instead of an item from the queue.
;; This makes it easier for the consumer to empty the queue after a delay
;; Example:
;; (def q (new-queue))
;; (add-consumer q #(println (pop-all! %))
;; (enqueue! q "hello")
;; => ["hello"] ;; printed to console

(defn new-queue []
  (atom cljs.core/PersistentQueue.EMPTY))

(defn pop! [queue-atom]
  (loop [queue-val @queue-atom]
    (if (compare-and-set! queue-atom queue-val (pop queue-val))
      (peek queue-val)
      (recur @queue-atom))))

(defn pop-all! [queue-atom]
  (loop [queue-val @queue-atom]
    (if (compare-and-set! queue-atom queue-val (empty queue-val))
      queue-val
      (recur @queue-atom))))

(defn enqueue! [queue-atom & items]
  (swap! queue-atom (fn [q] (apply conj q items))))

(defn add-consumer
  "Takes an optional consumer id for removing the consumer later. Will return
   consumer id. Handler will be given the queue atom, it's up to the consumer to
   use safe operations (like pop! or pop-all!) to consume the queue."
  ([queue handler]
   (add-consumer queue (utils/gen-id) handler))
  ([queue consumer-id handler]
   (add-watch queue consumer-id (fn [_ _ old new]
                                  (when (> (count new) (count old))
                                    (handler queue))))))

(defn remove-consumer [queue consumer-id]
  (remove-watch queue consumer-id))
