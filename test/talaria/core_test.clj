(ns talaria.core-test
  (:require [clojure.test :refer :all]
            [talaria.core :as tal]))

;; websocket tests
(deftest connecting-works
  (is (= 1 0))
  (testing "returns error if not websocket"))

(deftest receiving-messages-works
  (is (= 1 0)))

(deftest sending-messages-works
  (is (= 1 0)))

;; long-polling tests
(deftest long-polling-setup-works
  (is (= 1 0)))

(deftest long-polling-receiving-messages-works
  (is (= 1 0)))

(deftest long-polling-sending-messages-works
  (is (= 1 0)))

(deftest long-polling-reconnects
  (is (= 1 0)))
