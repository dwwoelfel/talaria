(defproject talaria "0.1.1-SNAPSHOT"
  :description "Clojure library for real-time client/server communication"
  :url "https://github.com/PrecursorApp/talaria"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [com.cognitect/transit-clj "0.8.275"]
                 [com.cognitect/transit-cljs "0.8.220"]

                 [org.clojure/clojurescript "1.7.48"]
                 [com.cognitect/transit-cljs "0.8.220"]

                 [org.clojure/tools.logging "0.3.1"]

                 ;; TODO: remove dependency on these
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [cljs-http "0.1.35"]]
  :source-paths ["src" "src-cljs"]

  :profiles {:dev {:source-paths ["src" "dev"]
                   :plugins [[lein-cljsbuild "1.0.6"]
                             [lein-figwheel "0.3.9"]]
                   :dependencies [[http.async.client "0.6.1"] ;; tests
                                  [org.immutant/web "2.0.1"]
                                  [org.clojure/tools.nrepl "0.2.10"]
                                  [org.clojure/tools.reader "0.10.0-alpha1"]
                                  [cider/cider-nrepl "0.9.1" :exclusions [org.clojure/tools.reader
                                                                          org.clojure/tools.nrepl]]
                                  [compojure "1.3.4"]
                                  [hiccup "1.0.5"]
                                  [org.omcljs/om "0.8.8"]]
                   :main talaria.dev}}
  :figwheel {:server-port 3434}
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src-cljs" "dev-cljs"]
                        :figwheel {:websocket-host "localhost"
                                   :on-jsload "talaria.dev/reload"}
                        :compiler {;; Datascript https://github.com/tonsky/datascript/issues/57
                                   :warnings {:single-segment-namespace false}
                                   :output-to "resources/public/cljs/dev/tal.js"
                                   :output-dir "resources/public/cljs/dev"
                                   :optimizations :none
                                   :source-map "resources/public/cljs/dev/rr.map"}}
                       {:id "production"
                        :source-paths ["src-cljs" "dev-cljs"]

                        :compiler {:pretty-print false
                                   ;; Datascript https://github.com/tonsky/datascript/issues/57
                                   :warnings {:single-segment-namespace false}
                                   :output-to "resources/public/cljs/production/rr.js"
                                   :output-dir "resources/public/cljs/production"
                                   :optimizations :advanced
                                   :source-map "resources/public/cljs/production/rr.map"}}]}
  )
