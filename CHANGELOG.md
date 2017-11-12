# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

# [Unstable]

### 11/11/17
- Modified the subscribe function parameter arguments.
- Updated the subscription functionality to latest GDAX requirements.

### 08/24/17
- Added get-trades for GDAX REST API
- Allow url-fn option to GDAX `get-client`
  - Reasoning: Default is live api, can supply function to choose something else.

### 08/21/17
- Exposed websocket events through async channels
  - connected
  - closed
  - errored

### 08/18/17
- Added REST API client.
- Added and `get-product-order-book` function.

### 08/17/2017
- Added metadata to the GDAX websocket subscription component
- Implemented various event functions for logging on the websocket client
  - on-connect
  - on-close
  - on-error
  - on-receive
- A core async buffer can be supplied to gdax/subscribe now. Default is still
  a sliding buffer of size 1000
- The error supplied by the on-error function now places the Throwable onto a
  core async channel called error.

### 08/15/2017
- Setup the repository
- Added GDAX product subscription functionality.
- Added number parsing for various GDAX entry events with tests.
