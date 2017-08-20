(defproject keychain "0.0.1-SNAPSHOT"
  :description "Crypto Exchange Keychain"
  :url "http://example.com/FIXME"
  :license {}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [stylefruits/gniazdo "1.0.1"]
                 [org.clojure/core.async "0.3.443"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.codec "0.1.0"]
                 [clj-http "3.6.1"]]
  :profiles {:dev {:aot :all, :plugins [[lein-gorilla "0.4.0"]]}})
