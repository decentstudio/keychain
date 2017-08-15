(ns keychain.exchange.gdax
  (:require [gniazdo.core :as ws]
            [clojure.data.json :as json]
            [clojure.core.async :as a]))

(defn ->json [x] (json/write-str x :key-fn name))

(defn json->edn [x] (json/read-str x :key-fn keyword))

(defn get-subscribe-event
  [products]
  (->json {:type "subscribe", :product_ids products}))

(defn subscribe [products & {:keys [buffer-size], :or {buffer-size 1}}]
  (let [feed (a/chan (a/sliding-buffer buffer-size))
        socket (ws/connect "wss://ws-feed.gdax.com"
                           :on-receive #(a/>!! feed (json->edn %)))
        _ (ws/send-msg socket (get-subscribe-event products))]
    {:feed feed
     :stop #(ws/close socket)}))
