(ns keychain.exchange.gdax
  (:require [gniazdo.core :as ws]
            [clojure.data.json :as json]
            [clojure.core.async :as a]))

(defn ->json [x] (json/write-str x :key-fn name))

(defn json->edn [x] (json/read-str x :key-fn keyword))

(defn get-subscribe-event
  [products]
  (->json {:type "subscribe", :product_ids products}))

(defmulti parse-numbers
  (fn [entry] (-> entry
                  :type
                  keyword)))

(defn parse-and-merge-numbers
  [entry keys]
  (->> (select-keys entry keys)
       (map (fn [[k v]] {k (try
                            (read-string v)
                            (catch NullPointerException e nil))}))
       (reduce merge entry)))

(defmethod parse-numbers :received
  [entry]
  (case (keyword (:order_type entry))
    :limit (parse-and-merge-numbers entry [:size :price])
    :market (parse-and-merge-numbers entry [:funds])))

(defmethod parse-numbers :open
  [entry]
  (parse-and-merge-numbers entry [:price :remaining_size]))

(defmethod parse-numbers :done
  [entry]
  (parse-and-merge-numbers entry [:price :remaining_size]))

(defmethod parse-numbers :match
  [entry]
  (parse-and-merge-numbers entry [:size :price]))

(defmethod parse-numbers :change
  [entry]
  (parse-and-merge-numbers entry [:new_size :old_size :price]))

(defn subscribe [products & {:keys [buffer-size], :or {buffer-size 1}}]
  (let [feed (a/chan (a/sliding-buffer buffer-size))
        socket (ws/connect "wss://ws-feed.gdax.com"
                           :on-receive #(a/>!! feed (-> %
                                                        json->edn
                                                        parse-numbers)))
        _ (ws/send-msg socket (get-subscribe-event products))]
    {:feed feed
     :stop (fn []
            (ws/close socket)
            (a/close! feed))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Realtime Orderbook ;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-book-entry
  [book {:keys [product_id order_id] :as entry}]
  (cond
    (= "match" (:type entry)) (swap! book update-in [product_id :matches] #(conj % entry))
    :else (swap! book update-in [product_id order_id] #(conj % entry))))

(defn create-realtime-orderbook
  [products & {:keys [] :as opt}]
  (let [{:keys [feed stop] :as subscription} (subscribe products :buffer-size 1000)
        book (atom {})]

    (a/go-loop
      [x (a/<! feed)]
      (when x
        (add-book-entry book x)
        (recur (a/<! feed))))

    {:book book, :stop #(stop)}))

(defn get-orders
  [snapshot product filter-fn]
  (filter filter-fn (get snapshot product)))
