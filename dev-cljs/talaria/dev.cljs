(ns talaria.dev
  (:require [cljs.pprint]
            [cljs.reader :as reader]
            [om.core :as om]
            [om.dom :as dom]
            [talaria.api :as tal]
            [talaria.utils :as utils]))

(enable-console-print!)

(defn render-tal-messages [messages owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
        (dom/pre nil
                 "Last 10 messages received:\n"
                 (with-out-str (cljs.pprint/print-table [:op :data :date] messages)))))))

(defn render-sent-messages [messages owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
        (dom/pre nil
                 "Last 10 messages sent:\n"
                 (with-out-str (cljs.pprint/print-table [:op :data :date] messages)))))))

(defn send-tal-message [tal-state owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [op data]}]
      (dom/div nil
        "Send message:"
        (let [submit (fn [e]
                       (println "Hello")
                       (.stopPropagation e)
                       (.preventDefault e)
                       (tal/queue-msg! tal-state {:op (keyword (if (= \: (first op))
                                                                 (subs op 1)
                                                                 op))
                                                  :data (reader/read-string data)})
                       (om/update-state! owner (fn [s]
                                                 (assoc s :op "" :data "")))
                       (.focus (om/get-node owner "op")))]
          (dom/form #js {:onSubmit submit}
                    (dom/input #js {:ref "op"
                                    :placeholder "op (e.g. :tal/ping)"
                                    :type "text"
                                    :value (or op "")
                                    :onChange #(om/set-state! owner :op (.. % -target -value))
                                    :onSubmit submit})
                    (dom/input #js {:ref "data"
                                    :placeholder "data (e.g. {:a :b})"
                                    :type "text"
                                    :value (or data "")
                                    :onChange #(om/set-state! owner :data (.. % -target -value))
                                    :onSubmit submit})
                    (dom/input #js {:type "submit"
                                    :value "Send"})))))))

(defn send-echo-message [tal-state owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [data reply async-data inc-data]}]
      (dom/div nil
        (dom/div nil
          "Echo message with callback:"
          (let [submit (fn [e]
                         (.stopPropagation e)
                         (.preventDefault e)
                         (tal/queue-msg! tal-state {:op :tal-dev/echo
                                                    :data (reader/read-string data)}
                                         1000
                                         (fn [reply]
                                           (om/set-state! owner :reply reply)))
                         (om/update-state! owner (fn [s]
                                                   (assoc s :data ""))))]
            (dom/form #js {:onSubmit submit}
                      (dom/input #js {:ref "data"
                                      :placeholder "data (e.g. {:a :b})"
                                      :type "text"
                                      :value (or data "")
                                      :onChange #(om/set-state! owner :data (.. % -target -value))
                                      :onSubmit submit})
                      (dom/input #js {:type "submit"
                                      :value "Send"})))
          (dom/pre nil
                   "echo reply:\n"
                   (pr-str reply)))
        (dom/div nil
          "Async echo"
          (let [submit (fn [e]
                         (.stopPropagation e)
                         (.preventDefault e)
                         (tal/queue-msg! tal-state {:op :tal-dev/async-echo
                                                    :data (reader/read-string async-data)})
                         (om/update-state! owner (fn [s]
                                                   (assoc s :async-data ""))))]
            (dom/form #js {:onSubmit submit}
                      (dom/input #js {:ref "data"
                                      :placeholder "data (e.g. {:a :b})"
                                      :type "text"
                                      :value (or async-data "")
                                      :onChange #(om/set-state! owner :async-data (.. % -target -value))
                                      :onSubmit submit})
                      (dom/input #js {:type "submit"
                                      :value "Send"}))))
        (dom/div nil
          "Increment (result is in messages received)"
          (let [submit (fn [e]
                         (.stopPropagation e)
                         (.preventDefault e)
                         (tal/queue-msg! tal-state {:op :tal-dev/inc
                                                    :data inc-data})
                         (om/update-state! owner (fn [s]
                                                   (assoc s :inc-data ""))))]
            (dom/form #js {:onSubmit submit}
                      (dom/input #js {:ref "data"
                                      :placeholder "number (e.g. 10)"
                                      :type "text"
                                      :value (or inc-data "")
                                      :onChange #(om/set-state! owner :inc-data (.. % -target -value))
                                      :onSubmit submit})
                      (dom/input #js {:type "submit"
                                      :value "Send"}))))))))

(defn main-component [app owner]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:style #js {:display "flex"
                                :justifyContent "space-between"}}

        (dom/div #js {:style #js {:flexGrow "0"}}
          (om/build render-tal-messages (:messages app))
          (om/build render-sent-messages (:sent-messages app))
          (dom/div nil
            "tal-state:"
            (dom/pre nil
                     (with-out-str
                       (cljs.pprint/pprint (dissoc @(:tal-state app) :callbacks :ws))))))
        (dom/div #js {:style #js {:flexGrow "0"}}
          (om/build send-tal-message (:tal-state app))
          (om/build send-echo-message (:tal-state app)))))))

(defonce app-state (atom {}))

(defn setup-om []
  (om/root main-component app-state {:target (js/document.getElementById "tal-app")}))

(defn setup-tal-queue [tal-state app-state]
  (tal/start-message-consumer tal-state (fn [msg]
                                          (let [msg (assoc msg :date (js/Date.))]
                                            (swap! app-state update :messages (fn [messages]
                                                                                (if (seq messages)
                                                                                  (take 10 (conj messages msg))
                                                                                  (list msg)))))))
  (add-watch (:send-queue @tal-state) :record-messages (fn [_ _ old new]
                                                         (when (> (count new) (count old))
                                                           (let [msg (assoc (last new) :date (js/Date.))]
                                                             (swap! app-state update :sent-messages
                                                                    (fn [messages]
                                                                      (if (seq messages)
                                                                        (take 10 (conj messages msg))
                                                                        (list msg)))))))))

(defn ^:export init []
  (let [uri (goog.Uri. js/window.location.href)
        tal-state (tal/init)]
    (swap! app-state assoc :tal-state tal-state)
    (setup-tal-queue tal-state app-state)
    (add-watch tal-state nil (fn [& args]
                               (swap! app-state update :tal-updates (fnil inc 0))))
    (setup-om)))

(defonce setup (init))

(defn reload []
  (setup-om))
