# keychain

Implementations and utilities for various cryptocurrency exchanges.

## Usage

## GDAX
You will find GDAX functionality in `keychain.exchange.gdax`

### API Client
Most GDAX REST API requests will require credentials.

```clojure
{:access-key "string"
 :access-secret "string"
 :access-passphrase "string"}
```

Keychain will look for three environment variables automatically:

- KEYCHAIN_GDAX_ACCESS_KEY
- KEYCHAIN_GDAX_ACCESS_SECRET
- KEYCHAIN_GDAX_ACCESS_PASSPHRASE

Or you can supply your own function to source your credentials to the `get-client` function.

Example:

```clojure
(use 'keychain.exchange.gdax)

(def gdax (get-client))
```

### REST API
#### Get Product Order Book
```clojure
(def product "ETH-USD")
;; Defaults to level 1
(get-product-order-book gdax product)

;; Level 2
(get-product-order-book gdax product :level 2)

;; Level 3
(get-product-order-book gdax product :level 3)
```

#### Get Trades

Returns a map containing the keys:
- `:data`
- `:pagination`

The pagination key is a map containing:
- `:before`
- `:after`

```clojure
(def product "ETH-USD")

;; Default request
(get-trades gdax product)

;; With Limit
(get-trades gdax product :limit 10)

;; With Before
(get-trades gdax product :before "123")

;; With After
(get-trades gdax product :after "123")
```




### Websockets Feed
#### Subscribe
The default implementation uses a sliding buffer with a of size 1000. You may provide your own core.async buffer with the `:buffer` option.

A message is a tuple where the first element is a string timestamp and the second is a type coerced, but unaltered, GDAX message.

By default the channels used are `heartbeat` and `full`. Custom channels can be provided as a vector of channel names only at this time to the subscribe arg map.

This function returns a map containing the following:

##### :feed
A core.async channel on which to receive messages

##### :errored
A core.async channel on which to receive a Throwable error.

##### :closed
A core.async channel on which to receive a code and reason for closing.

##### :connected
A core.async channel on which to receive an event upon initial connection.

##### :close
A zero argument function that will close the websocket subscription and all associated channels.

```clojure
(use 'clojure.core.async)

(def products ["ETH-USD" "ETH-BTC"])

(def subscription (subscribe {:products products}))

(poll! (:feed subscription))
```
