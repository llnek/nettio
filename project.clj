;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defproject io.czlab/nettio "2.2.0"

  :license {:url "https://www.apache.org/licenses/LICENSE-2.0.txt"
            :name "Apache License"}

  :description "Http client and server library using netty."
  :url "https://github.com/llnek/nettio"

  :dependencies [[io.netty/netty-tcnative-boringssl-static "2.0.69.Final"]
                 ;[jakarta.activation/jakarta.activation-api "2.1.3"]
                 ;[jakarta.servlet/jakarta.servlet-api "6.1.0"]
                 [commons-fileupload/commons-fileupload "1.5"]
                 ;[net.sourceforge.jregex/jregex "1.2_01"]
                 [javax.servlet/javax.servlet-api "4.0.1"]
                 [org.javassist/javassist "3.30.2-GA"]
                 [io.netty/netty-all "4.1.115.Final"]
                 [io.czlab/basal "2.2.0"]
                 [io.czlab/twisty "2.2.0"]]

  :plugins [[cider/cider-nrepl "0.50.2" :exclusions [nrepl/nrepl]]
            [lein-codox "0.10.8"]
            [lein-cljsbuild "1.1.8"]]

  :test-selectors {:niou :test-niou
                   :h1 :test-h1
                   :h2 :test-h2
                   :apps :test-apps
                   :wsock :test-wsock
                   :nettio :test-nettio}

  :profiles {:provided {:dependencies
                        [[org.clojure/clojure "1.12.0"]]}
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
  :javac-options ["-source" "16"
                  "-target" "22"
                  "-Xlint:unchecked" "-Xlint:-options" "-Xlint:deprecation"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


