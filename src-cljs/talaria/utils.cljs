(ns talaria.utils
  (:require [clojure.string :as str])
  (:import [goog.ui IdGenerator]))

(defonce id-generator (.getInstance IdGenerator))

(defn gen-id []
  (.getNextUniqueId id-generator))

(defn apply-map [f & args]
  (apply f (apply concat (butlast args) (last args))))

;; TODO: have to do something about this, remove it?
(defn mlog [& messages]
  (.apply (.-log js/console) js/console (clj->js messages)))
