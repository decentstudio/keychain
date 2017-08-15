(ns keychain.exchange.gdax-test
  (:require [keychain.exchange.gdax :as gdax]
            [clojure.test :refer :all]))

(def received-limit
  {:type "received",
   :time "2014-11-07T08:19:27.028459Z",
   :product_id "BTC-USD",
   :sequence 10,
   :order_id "d50ec984-77a8-460a-b958-66f114b0de9b",
   :size "1.34",
   :price "502.1",
   :side "buy",
   :order_type "limit"})

(def received-market
  {:type "received",
   :time "2014-11-09T08:19:27.028459Z",
   :product_id "BTC-USD",
   :sequence 12,
   :order_id "dddec984-77a8-460a-b958-66f114b0de9b",
   :funds "3000.234",
   :side "buy",
   :order_type "market"})

(def open
  {:type "open",
   :time "2014-11-07T08:19:27.028459Z",
   :product_id "BTC-USD",
   :sequence 10,
   :order_id "d50ec984-77a8-460a-b958-66f114b0de9b",
   :price "200.2",
   :remaining_size "1.00",
   :side "sell"})

(def done
  {:type "done",
   :time "2014-11-07T08:19:27.028459Z",
   :product_id "BTC-USD",
   :sequence 10,
   :price "200.2",
   :order_id "d50ec984-77a8-460a-b958-66f114b0de9b",
   :reason "filled",
   :side "sell",
   :remaining_size "0.2"})

(def done-market (dissoc done :price :remaining_size))

(def match
  {:type "match",
   :trade_id 10,
   :sequence 50,
   :maker_order_id "ac928c66-ca53-498f-9c13-a110027a60e8",
   :taker_order_id "132fb6ae-456b-4654-b4e0-d681ac05cea1",
   :time "2014-11-07T08:19:27.028459Z",
   :product_id "BTC-USD",
   :size "5.23512",
   :price "400.23",
   :side "sell"})

(def change
  {:type "change",
   :time "2014-11-07T08:19:27.028459Z",
   :sequence 80,
   :order_id "ac928c66-ca53-498f-9c13-a110027a60e8",
   :product_id "BTC-USD",
   :new_size "5.23512",
   :old_size "12.234412",
   :price "400.23",
   :side "sell"})

(def change-null-price (assoc change :price nil))

;;;;;;;;;;;;;;;
;;;; Tests ;;;;
;;;;;;;;;;;;;;;

(deftest parse-numbers-tests
  (testing "received-limit"
    (is (= 1.34 (:size (gdax/parse-numbers received-limit))))
    (is (= 502.1 (:price (gdax/parse-numbers received-limit)))))
  (testing "received-market"
    (is (= 3000.234 (:funds (gdax/parse-numbers received-market)))))
  (testing "open"
    (is (= 200.2 (:price (gdax/parse-numbers open))))
    (is (= 1.00 (:remaining_size (gdax/parse-numbers open)))))
  (testing "done"
    (is (= 200.2 (:price (gdax/parse-numbers done))))
    (is (= 0.2 (:remaining_size (gdax/parse-numbers done)))))
  (testing "done-market"
    (is (= done-market (gdax/parse-numbers done-market))))
  (testing "match"
    (is (= 5.23512 (:size (gdax/parse-numbers match))))
    (is (= 400.23 (:price (gdax/parse-numbers match)))))
  (testing "change"
    (is (= 5.23512 (:new_size (gdax/parse-numbers change))))
    (is (= 12.234412 (:old_size (gdax/parse-numbers change))))
    (is (= 400.23 (:price (gdax/parse-numbers change)))))
  (testing "change-null-price"
    (is (= nil (:price (gdax/parse-numbers change-null-price))))))
