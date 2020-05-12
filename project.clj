(defproject reporter-daemon "0.1.2"
  :description "Monitoring service for CTest"
  :license {:name "Eclipse Public License v2.0"
            :url "https://www.eclipse.org/legal/epl-2.0"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojars.guv/postal "2.0.4.1"]
                 [clj-http "3.10.0"]
                 [cheshire "5.10.0"]
                 [seancorfield/next.jdbc "1.0.409"]
                 [org.apache.derby/derby "10.14.2.0"]
                 [com.mchange/c3p0 "0.9.5.5"]
                 [org.clojure/tools.cli "1.0.194"]
                 [com.stuartsierra/component "1.0.0"]
                 [org.clojure/tools.logging "1.0.0"]       ; logging, e.g. for fail2ban usage
                 [org.slf4j/slf4j-api "1.7.30"]
                 [org.slf4j/slf4j-log4j12 "1.7.30"]
                 [org.clojars.guv/darkstar "0.1.0"]]

  :profiles {:dev {:dependencies [[reloaded.repl "0.2.4"]
                                  [clj-debug "0.7.6"]
                                  [metasoarous/oz "1.6.0-alpha6"]]
                   :source-paths ["dev"]}
             :uberjar {:main reporter.main
                       :aot :all}}

  :jar-name "reporter-daemon-lib-%s.jar"
  :uberjar-name "reporter-daemon-%s.jar"

  :repl-options {:init-ns dev})
