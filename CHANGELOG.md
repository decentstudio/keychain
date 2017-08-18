# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

# [Unstable]

### 08/17/2017
- Added metadata to the GDAX websocket subscription component
- Implemented various event functions for logging on the websocket client
  - on-connect
  - on-close
  - on-error
  - on-receive
- A core async buffer can be supplied to gdax/subscribe now. Default is still
  a sliding buffer of size 1000

### 08/16/2017
- Added order book calculations
  - A (Get Ask Side)
  - B (Get Bid Side)
  - a (Get Current Ask)
  - b (Get Current Bid)
  - s (Get Current Spread)
  - m (Get Current mid price)
  - nbpt (Get Current Bid Side Depth For Price P)
  - napt (Get Current Ask Side Depth For Price P)
  - nbpt-profile (Get Current Bid Side Depth Profile)
  - napt-profile (Get Current Ask Side Depth Profile)

### 08/15/2017
- Setup the repository
- Added GDAX production subscription functionality.
- Added number parsing for various GDAX entry events with tests.
