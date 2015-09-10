(ns talaria.view
  (:require [hiccup.page]))

(defn body []
  (hiccup.page/html5
   [:head
    [:title "Talaria Dev"]
    [:meta {:charset "utf-8"}]]
   [:body
    [:div#tal-app]
    [:script {:type "text/javascript" :src "/cljs/dev/goog/base.js"}]
    [:script {:type "text/javascript" :src "/cljs/dev/tal.js"}]
    [:script {:type "text/javascript"} "goog.require(\"talaria.dev\");"]]))
