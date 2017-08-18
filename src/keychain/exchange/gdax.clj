(ns keychain.exchange.gdax
  (:require [gniazdo.core :as ws]
            [clojure.data.json :as json]
            [clojure.core.async :as a])
  (:import java.time.Instant))

;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Websocket Feed ;;;;
;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn now [] (str (Instant/now)))

(defn subscribe [products & {:keys [buffer-size], :or {buffer-size 1}}]
  (let [metadata (atom {})
        feed (a/chan (a/sliding-buffer buffer-size))
        socket (ws/connect "wss://ws-feed.gdax.com"
                           :on-connect
                           (fn [^org.eclipse.jetty.websocket.api.Session s]
                             (swap! metadata update-in [:log] #(conj % [(now) :connected])))
                           :on-close
                           (fn [code reason]
                             (swap! metadata update-in [:log] #(conj % [(now) :closed code reason])))
                           :on-error
                           (fn [^java.lang.Throwable t]
                             (swap! metadata update-in [:log] #(conj % [(now) :error (.getMessage t)])))
                           :on-receive
                           (fn [m]
                             (swap! metadata update-in [:log] #(conj % [(now) :receive]))
                             (a/>!! feed (-> m json->edn parse-numbers))))
        _ (ws/send-msg socket (get-subscribe-event products))]
    {:feed feed
     :metadata metadata
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
  (into {} (filter filter-fn (get snapshot product))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Snapshot Operations ;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn open?
  [order]
  (= "open" (-> order val first :type)))

(defn buy?
  [order]
  (= "buy" (-> order val first :side)))

(defn sell?
  [order]
  (= "sell" (-> order val first :side)))

(defn open-x-order?*
  "General predicate for open sell/buy order"
  [pred-fn order]
  (and (open? order)
       (pred-fn order)))

(def open-buy-order?
  (partial open-x-order?* buy?))

(def open-sell-order?
  (partial open-x-order?* sell?))

(defn B
  "Open bid limit orders."
  [snapshot product]
  (->> (get-orders
         snapshot
         product
         open-buy-order?)))

(defn A
  "Open ask limit orders."
  [snapshot product]
  (->> (get-orders
         snapshot
         product
         open-sell-order?)))

(defn get-price
  [order]
  (->> order
       val
       first
       :price))

(defn get-prices
  [orders]
  (map get-price orders))

(defn b
  "Current bid price."
  [snapshot product]
  (->> (B snapshot product)
       (get-prices)
       (apply max)))

(defn a
  "Current ask price."
  [snapshot product]
  (->> (A snapshot product)
       (get-prices)
       (apply min)))

(defn s
  "Current bid-ask spread."
  [snapshot product]
  (- (a snapshot product) (b snapshot product)))

(defn m
  "Current mid price."
  [snapshot product]
  (/ (+ (a snapshot product) (b snapshot product)) 2))

(defn nxpt*
  "General function for nbpt and napt."
  [side-fn snapshot product price]
  (->> (side-fn snapshot product)
       (filter #(= price (get-price %)))
       (map val)
       (map first)
       (map #(or (:size %) (:remaining_size %)))
       (reduce +)))

(defn nbpt
  "Current bid-side depth at a given price."
  [snapshot product price]
  (nxpt* B snapshot product price))

(defn napt
  "Current ask-side depth at a given price."
  [snapshot product price]
  (nxpt* A snapshot product price))

(defn nxpt-profile*
  "General function for nbpt-profile and napt-profile."
  [side-fn snapshot product]
  (->> (side-fn snapshot product)
       (get-prices)
       (into #{})
       (pmap (fn [p] {p (nxpt* side-fn snapshot product p)}))
       (reduce merge)))

(defn nbpt-profile
  "Current bid-side depth profile for a given snapshot."
  [snapshot product]
  (nxpt-profile* B snapshot product))

(defn napt-profile
  "Current ask-side depth profile for a given snapshot."
  [snapshot product]
  (nxpt-profile* A snapshot product))
