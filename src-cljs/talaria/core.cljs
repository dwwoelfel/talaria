(ns talaria.core
  (:require [cljs.core.async :as async]
            [cljs-http.client :as http]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [goog.Uri.QueryData]
            [goog.Uri :as uri]
            [goog.events :as gevents]
            [talaria.utils :as utils]
            [talaria.queue :as queue])
  (:import [goog.net.WebSocket.EventType])
  (:require-macros [cljs.core.async.macros :refer (go go-loop)]))

(defn decode-msg [reader msg]
  (transit/read reader msg))

(defn encode-msg [writer msg]
  (transit/write writer msg))

(defrecord AjaxSocket [recv-url send-url csrf-token on-open on-close on-error on-message on-reconnect]
  Object
  (send [ch msg]
    (if (.-open ch)
      (go
        (let [res (async/<! (http/post send-url {:body msg
                                                 :headers {"X-CSRF-Token" csrf-token
                                                           "Content-Type" "text/plain"}}))]
          (when-not (= 200 (:status res))
            (when (fn? on-error)
              (on-error res))
            (when (= "channel-closed" (:body res))
              (.close ch)))))
      (throw "Channel is closed")))
  (open [ch]
    (go
      (when (= "connected"
               (:body (async/<! (http/get recv-url {:query-params {:open? true}
                                                    :headers {"Content-Type" "text/plain"}}))))
        (set! (.-open ch) true)
        (go-loop []
          (let [resp (async/<! (http/get recv-url {:headers {"Content-Type" "text/plain"}
                                                   :timeout (* 1000 30)}))]
            (cond
              (keyword-identical? :timeout (:error-code resp)) nil

              (or (str/blank? (:body resp))
                  (not (:success resp)))
              (.close ch)

              (= "replace-existing" (:body resp)) nil

              :else (on-message (clj->js {:data (:body resp)})))
            (when-not (.-closed ch)
              (recur))))
        (when (fn? on-open)
          (on-open)))))
  (close [ch data]
    (when-not (.-closed ch)
      (set! (.-closed ch) true)
      (when (fn? on-close)
        (on-close data)))))

(defn pop-callback [tal-state cb-id]
  (loop [val @tal-state]
    (if (compare-and-set! tal-state val (update-in val [:callbacks] dissoc cb-id))
      (get-in val [:callbacks cb-id])
      (recur @tal-state))))

(defn run-callback [tal-state cb-id cb-data]
  (when-let [cb-fn (pop-callback tal-state cb-id)]
    (cb-fn cb-data)))

(defn queue-msg! [tal-state msg & [timeout-ms callback]]
  (let [queue (:send-queue @tal-state)
        cb-id (when callback (utils/gen-id))]
    (when callback
      (swap! tal-state assoc-in [:callbacks cb-id] callback)
      ;; runs the callback with an error if no response before the timeout, else noops
      (js/setTimeout #(run-callback tal-state cb-id {:tal/error :tal/timeout
                                                     :tal/status :error})
                     timeout-ms))
    (queue/enqueue! queue (merge msg
                                 (when callback
                                   {:tal/cb-id cb-id})))))

(defn pop-all [coll-atom]
  (loop [val @coll-atom]
    (if (compare-and-set! coll-atom val (empty val))
      val
      (recur @coll-atom))))

(defn send-msg [tal-state ws msg]
  (.send ws (encode-msg (:transit-writer @tal-state) msg))
  (swap! tal-state assoc :last-send-time (js/Date.)))

(defn filter-repeats [messages filter-ops]
  (->> (reduce (fn [acc msg]
                 (if (contains? filter-ops (:op msg))
                   (if (contains? (:seen-filtered-ops acc) (:op msg))
                     acc
                     (assoc acc
                            :results (conj (:results acc) msg)
                            :seen-filtered-ops (conj (:seen-filtered-ops acc) (:op msg))))
                   (assoc acc :results (conj (:results acc) msg))))
               {:results '() ; list for inserting at beginning
                :seen-filtered-ops #{}}
               (rseq messages))
    :results
    vec))

(defn consume-send-queue [tal-state timer-atom]
  (let [send-queue (:send-queue @tal-state)]
    (when-let [ws (:ws @tal-state)]
      (doseq [timer-id (pop-all timer-atom)]
        (js/clearTimeout timer-id))
      (let [messages (vec (queue/pop-all! send-queue))]
        (when (seq messages)
          (send-msg tal-state ws (filter-repeats messages #{:frontend/mouse-position
                                                                           :tal/ping})))))))

(defn start-send-queue [tal-state delay-ms]
  (let [send-queue (:send-queue @tal-state)
        timer-atom (atom #{})]
    (queue/add-consumer send-queue ::send-watcher
                        (fn [q]
                          (let [timer-id (js/setTimeout #(consume-send-queue tal-state timer-atom)
                                                        delay-ms)]
                            (swap! timer-atom conj timer-id))))
    (consume-send-queue tal-state timer-atom)))

(defn shutdown-send-queue [tal-state]
  (queue/remove-consumer (:send-queue @tal-state) ::send-watcher))

(defn close-connection [tal-state]
  (.close (:ws @tal-state)))

(defn consume-recv-queue [tal-state handler]
  (let [recv-queue (:recv-queue @tal-state)]
    (doseq [msg (queue/pop-all! recv-queue)]
      (cond
        (keyword-identical? :tal/reply (:op msg))
        (run-callback tal-state (:tal/cb-id msg) (:data msg))

        (keyword-identical? :tal/close (:op msg))
        (close-connection tal-state)

        :else
        (handler msg)))))

(defn ping-loop [tal-state]
  (let [ka-ms (:keep-alive-ms @tal-state)
        last-send (:last-send-time @tal-state)
        next-send-ms (when last-send
                       (- ka-ms
                          (- (.getTime (js/Date.))
                             (.getTime last-send))))]
    (if (and next-send-ms (pos? next-send-ms))
      (js/setTimeout #(ping-loop tal-state) next-send-ms)
      (do
        (queue-msg! tal-state {:op :tal/ping})
        (js/setTimeout #(ping-loop tal-state) ka-ms)))))

(defn start-ping [tal-state]
  (js/window.setTimeout #(ping-loop tal-state) (:keep-alive-ms @tal-state)))

(defn make-url [{:keys [port path host secure? ws? params tab-id csrf-token]}]
  (let [scheme (if ws?
                 (if secure? "wss" "ws")
                 (if secure? "https" "http"))]
    (str (doto (goog.Uri.)
           (.setScheme scheme)
           (.setDomain host)
           (.setPort port)
           (.setPath path)
           (.setQueryData  (-> params
                               (assoc :csrf-token csrf-token)
                               (assoc :tab-id tab-id)
                               clj->js
                               (goog.Uri.QueryData/createFromMap)))))))

(defn setup-ajax [tal-state {:keys [on-open on-close on-error on-reconnect reconnecting? url-parts]
                             :as opts}]
  (let [url (make-url (assoc url-parts :path "/talaria" :ws? false))
        w (map->AjaxSocket {:recv-url (make-url (assoc url-parts :path "/talaria/ajax-poll"))
                            :send-url (make-url (assoc url-parts :path "/talaria/ajax-send"))
                            :csrf-token (:csrf-token url-parts)
                            :on-open #(do
                                        (utils/mlog "opened" %)
                                        (let [timer-id (start-ping tal-state)]
                                          (swap! tal-state (fn [s]
                                                             (-> s
                                                                 (assoc :open? true
                                                                        :keep-alive-timer timer-id)
                                                                 (dissoc :closed? :close-code :close-reason)))))
                                        (start-send-queue tal-state 30)
                                        (when (and reconnecting? (fn? on-reconnect))
                                          (on-reconnect tal-state))

                                        (when (fn? on-open)
                                          (on-open tal-state)))
                            :on-close #(do
                                         (utils/mlog "closed" %)
                                         (shutdown-send-queue tal-state)
                                         (swap! tal-state assoc
                                                :ws nil
                                                :open? false
                                                :closed? true
                                                :close-code (:status %)
                                                :close-reason (:body %))
                                         (js/clearInterval (:keep-alive-timer @tal-state))
                                         (when (fn? on-close)
                                           (on-close tal-state %))
                                         (js/setTimeout (fn []
                                                          (when-not (:ws @tal-state)
                                                            (setup-ajax tal-state (assoc opts :reconnecting? true))))
                                                        ;; TODO: back-off
                                                        1000))
                            :on-error #(do (utils/mlog "error" %)
                                           (swap! tal-state assoc :last-error-time (js/Date.))
                                           (when (fn? on-error)
                                             (on-error tal-state)))
                            :on-message #(do
                                           ;;(utils/mlog "message" %)
                                           (swap! tal-state assoc :last-recv-time (js/Date.))
                                           (swap! (:recv-queue @tal-state) (fn [q] (apply conj q (decode-msg (:transit-reader @tal-state) (.-data %))))))
                            :on-reconnect on-reconnect})]
    (swap! tal-state assoc :ws w)
    (start-send-queue tal-state 150)
    (.open w)))

(defn setup-ws [tal-state {:keys [on-open on-close on-error on-reconnect reconnecting? delay url-parts]
                           :as opts}]
  (let [url (make-url (assoc url-parts :path "/talaria" :ws? true))
        w (js/WebSocket. url)]
    (swap! tal-state assoc :ws w)
    (aset w "onopen"
          #(do
             (utils/mlog "opened" %)
             (let [timer-id (start-ping tal-state)]
               (swap! tal-state (fn [s]
                                  (-> s
                                      (assoc :open? true
                                             :keep-alive-timer timer-id
                                             :supports-websockets? true)
                                      (dissoc :closed? :close-code :close-reason)))))
             (start-send-queue tal-state 30)
             (when (and reconnecting? (fn? on-reconnect))
               (on-reconnect tal-state))

             (when (fn? on-open)
               (on-open tal-state))))
    (aset w "onclose"
          #(let [code (.-code %)
                 reason (.-reason %)]
             (utils/mlog "closed" %)
             (shutdown-send-queue tal-state)
             (swap! tal-state assoc
                    :ws nil
                    :open? false
                    :closed? true
                    :close-code code
                    :close-reason reason)
             (js/clearInterval (:keep-alive-timer @tal-state))
             (when (fn? on-close)
               (on-close tal-state {:code code
                                    :reason reason}))
             (if (and (= 1006 code)
                      (not (:supports-websockets? @tal-state)))
               ;; ws failed, lets try ajax
               (setup-ajax tal-state opts)
               (js/setTimeout (fn []
                                (when-not (:open? @tal-state)
                                  (setup-ws tal-state (assoc opts :reconnecting? true))))
                              ;; TODO: back-off
                              1000))))
    (aset w "onerror"
          #(do (utils/mlog "error" %)
               (swap! tal-state assoc :last-error-time (js/Date.))
               (when (fn? on-error)
                 (on-error tal-state))))
    (aset w "onmessage"
          #(do
             ;;(utils/mlog "message" %)
             (swap! tal-state assoc :last-recv-time (js/Date.))
             (swap! (:recv-queue @tal-state) (fn [q] (apply conj q (decode-msg (:transit-reader @tal-state) (.-data %)))))))))
