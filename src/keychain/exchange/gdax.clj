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

(defn get-before
  [headers]
  (get headers "CB-BEFORE"))

(defn get-after
  [headers]
  (get headers "CB-AFTER"))

(defn get-pagination
  [headers]
  {:pagination {:before (get-before headers)
                :after (get-after headers)}})

(defn paginated?
  [headers]
  (or (get-before headers)
      (get-after headers)))

(defn send-request
  [{:keys [credentials url] :as client} request & {:keys [debug]}]
  (try
    (let [request (assoc request :url url)
          {:keys [headers body]} (http/request (->http (sign-request credentials request)))]
      (if-not (paginated? headers)
        body
        (merge {:data body} (get-pagination headers))))
    (catch ExceptionInfo e
      (let [{:keys [status body]} (ex-data e)]
        (when debug (clojure.pprint/pprint body))
        (throw (ex-info (str status " " (get errors status "Unknown Error Code"))
                        {:status status
                         :message (or (:message (json->edn body)) "")}))))))

;;;;;;;;;;;;;;;;;;
;;;; REST API ;;;;
;;;;;;;;;;;;;;;;;;
(defn get-api-url [] "https://api.gdax.com")

(defn get-client
  [& {:keys [credential-fn url-fn] :or {credential-fn get-credentials
                                        url-fn get-api-url}}]
  {:credentials (credential-fn)
   :url (url-fn)})

(defn get-product-order-book
  [client product & {:keys [level] :or {level 1} :as params}]
  (send-request
    client
    {:method "GET"
     :path (format "/products/%s/book" product)
     :query-params params}))

(defn get-trades
 [client product & {:keys [before after limit] :as params}]
 (send-request
   client
   {:method "GET"
    :path (format "/products/%s/trades" product)
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

(defn now [] (str (java.time.Instant/now)))

(defn on-connect
  [log-fn ^org.eclipse.jetty.websocket.api.Session s]
  (log-fn [(now) :connected]))

(defn on-close
  [shutdown-fn log-fn ^Integer code ^String reason]
  (log-fn [(now) :closed code reason])
  (shutdown-fn))

(defn on-error
  [log-fn ^java.lang.Throwable t]
  (log-fn [(now) :error (.getMessage t)]))

(defn on-receive
  [log-fn ^String message]
  (log-fn [(now) (-> message json->edn parse-numbers)]))

(defn subscribe [products & {:keys [buffer] :or {buffer (a/sliding-buffer 1000)}}]
  (let [connect (a/chan)
        close   (a/chan)
        error   (a/chan)
        feed    (a/chan buffer)
        publish (fn [chan] #(a/put! chan %))
        shutdown #(doseq [chan [feed close connect error]] (a/close! chan))
        socket (ws/connect "wss://ws-feed.gdax.com"
                           :on-connect (partial on-connect (publish connect))
                           :on-close   (partial on-close shutdown (publish close))
                           :on-error   (partial on-error (publish error))
                           :on-receive (partial on-receive (publish feed)))
        _ (ws/send-msg socket (get-subscribe-event products))]
    {:feed feed
     :errored error
     :closed close
     :connected connect
     :close #(ws/close socket)}))
