(ns keychain.exchange.gdax
  (:require [gniazdo.core :as ws]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.data.codec.base64 :as b64]
            [clojure.core.async :as a])
  (:import javax.crypto.Mac
           javax.crypto.spec.SecretKeySpec
           clojure.lang.ExceptionInfo))

;;;;;;;;;;;;;;;;;
;;;; Utility ;;;;
;;;;;;;;;;;;;;;;;

(defn ->json [x] (json/write-str x :key-fn name))

(defn json->edn [x] (json/read-str x :key-fn keyword))

(defn get-timestamp
  []
  (.. (java.time.Instant/now)
      getEpochSecond))

;;;;;;;;;;;;;;;;;;;;;;;
;;;; Credentialing ;;;;
;;;;;;;;;;;;;;;;;;;;;;;

(def gdax-access-key "KEYCHAIN_GDAX_ACCESS_KEY")
(def gdax-access-secret "KEYCHAIN_GDAX_ACCESS_SECRET")
(def gdax-access-passphrase "KEYCHAIN_GDAX_ACCESS_PASSPHRASE")

(defn get-credentials
  []
  {:access-key (System/getenv gdax-access-key)
   :access-secret (System/getenv gdax-access-secret)
   :access-passphrase (System/getenv gdax-access-passphrase)})

;;;;;;;;;;;;;;
;;;; HMAC ;;;;
;;;;;;;;;;;;;;

(defn decode-access-secret
  [^String access-secret]
  (b64/decode (.getBytes access-secret)))

(defn get-secret-key
  [^String access-secret ^String algorithm]
  (SecretKeySpec. (decode-access-secret access-secret) algorithm))

(defn get-mac
  [secret-key algorithm]
  (doto
    (Mac/getInstance algorithm)
    (.init secret-key)))

(defn sign
  [mac message]
  (-> message
      (.getBytes)
      (#(.doFinal mac %))
      (b64/encode)
      (String. "UTF-8")))

(defn get-signature
  [^String access-secret ^String message & {:keys [algorithm] :or {algorithm "HmacSHA256"}}]
  (let [secret-key (get-secret-key access-secret algorithm)
        mac (get-mac secret-key algorithm)]
    (sign mac message)))

;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Request Signing ;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-signature-headers
  [access-key access-passphrase signature timestamp]
  {:headers {:CB-ACCESS-KEY access-key
             :CB-ACCESS-SIGN signature
             :CB-ACCESS-TIMESTAMP timestamp
             :CB-ACCESS-PASSPHRASE access-passphrase}})

(defn sign-request
  [{:keys [access-key access-passphrase access-secret] :as credentials}
   {:keys [method path body] :as request}]
  (let [timestamp (get-timestamp)]
    (merge request
           (get-signature-headers access-key
                                  access-passphrase
                                  (get-signature access-secret (str timestamp method path (when body (->json body))))
                                  timestamp))))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Request Building ;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn attach-body-maybe
  [{:keys [body] :as request}]
  (if-not (empty? body)
    (assoc request :body (->json body))
    request))

(defn ->http
  [{:keys [url path body] :as request}]
  (-> request
      (dissoc :path)
      (assoc :url (str url path))
      (assoc :content-type :json)
      (assoc :as :json)
      (attach-body-maybe)))

;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Request Sending ;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(def errors
  {400 "Bad Request – Invalid request format"
   401 "Unauthorized – Invalid API Key"
   403 "Forbidden – You do not have access to the requested resource"
   404 "Not Found"
   500 "Internal Server Error – We had a problem with our server"})

(defn send-request
  [{:keys [credentials] :as client} request & {:keys [debug]}]
  (try
    (:body (http/request (->http (sign-request credentials request))))
    (catch ExceptionInfo e
      (let [{:keys [status body]} (ex-data e)]
        (when debug (clojure.pprint/pprint body))
        (throw (ex-info (str status " " (get errors status "Unknown Error Code"))
                        {:status status
                         :message (or (:message (json->edn body)) "")}))))))

;;;;;;;;;;;;;;;;;;
;;;; REST API ;;;;
;;;;;;;;;;;;;;;;;;
(defn get-client
  [& {:keys [credential-fn] :or {credential-fn get-credentials}}]
  {:credentials (credential-fn)})

(defn get-product-order-book
  [client product & {:keys [level] :or {level 1} :as params}]
  (send-request
    client
    {:method "GET"
     :url "https://api.gdax.com"
     :path (format "/products/%s/book" product)
     :query-params params}))

;;;;;;;;;;;;;;;;;;;;;;;
;;;; Websocket API ;;;;
;;;;;;;;;;;;;;;;;;;;;;;

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
     :stop #(ws/close socket)}))
