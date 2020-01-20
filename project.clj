;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defproject io.czlab/nettio "2.0.1"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :description "Http client and server library using netty."
  :url "https://github.com/llnek/nettio"

  ;WARNING
  ;io.netty/netty-tcnative-boringssl-static "2.0.27.Final" crashes jvm
  :dependencies [[io.netty/netty-tcnative-boringssl-static "2.0.28.Final"]
                 [commons-fileupload/commons-fileupload "1.4"]
                 ;[net.sourceforge.jregex/jregex "1.2_01"]
                 [javax.servlet/javax.servlet-api "4.0.1"]
                 [org.javassist/javassist "3.26.0-GA"]
                 [io.netty/netty-all "4.1.45.Final"]
                 [io.czlab/basal "2.0.1"]
                 [io.czlab/twisty "2.0.1"]]

  :plugins [[cider/cider-nrepl "0.22.4"]
            [lein-javadoc "0.3.0"]
            [lein-codox "0.10.7"]]

  :test-selectors {:niou :test-niou
                   :h1 :test-h1
                   :h2 :test-h2
                   :apps :test-apps
                   :wsock :test-wsock
                   :nettio :test-nettio}

  :profiles {:provided {:dependencies
                        [[org.clojure/clojure "1.10.1" :scope "provided"]]}
             :uberjar {:aot :all}}

  :javadoc-opts {:package-names ["czlab.nettio"]
                 :output-dir "docs"}

  :global-vars {*warn-on-reflection* true}
  :target-path "out/%s"
  :aot :all

  :coordinate! "czlab"
  :omit-source true

  :java-source-paths ["src/main/java" "src/test/java"]
  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]
  :resource-paths ["src/main/resources"]

  :jvm-opts ["-Dlog4j.configurationFile=file:attic/log4j2.xml"]
  :javac-options ["-source" "11"
                  "-target" "11"
                  "-Xlint:unchecked" "-Xlint:-options" "-Xlint:deprecation"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


