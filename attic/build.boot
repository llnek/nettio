(set-env!

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :description ""
  :url "https://github.com/llnek/convoy"

  :exclusions '[javax.servlet/servlet-api]

  :dependencies '[

    [commons-fileupload/commons-fileupload "1.3.2"]
    [commons-net/commons-net "3.5"]
    [javax.servlet/javax.servlet-api "3.1.0"]
    [io.netty/netty-all "4.1.6.Final"]

    [net.sourceforge.jregex/jregex "1.2_01"]
    [org.javassist/javassist "3.21.0-GA"]

    [org.clojure/clojure "1.8.0"]
    [czlab/czlab-xlib "0.1.0"]
    [czlab/czlab-pariah "0.1.0" :scope "provided"]
  ]

  :source-paths #{"src/main/clojure" "src/main/java"}
  :test-runner "czlabtest.convoy.net.ClojureJUnit"
  :version "0.1.0"
  :debug true
  :project 'czlab/czlab-convoy)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(require
  '[czlab.pariah.boot
    :as b
    :refer [artifactID fp! ge]]
  '[clojure.tools.logging :as log]
  '[clojure.java.io :as io]
  '[clojure.string :as cs]
  '[czlab.pariah.antlib :as a])

(import '[java.io File])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(b/bootEnv!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;;  task defs below !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
;;
(deftask tst

  "for test only"
  []

  (comp (b/testJava)
        (b/testClj)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(deftask dev

  "for dev only"
  []

  (comp (b/initBuild)
        (b/libjars)
        (b/buildr)
        (b/pom!)
        (b/jar!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(deftask rel

  ""
  [d doco bool "Generate doc"]

  (b/toggleDoco doco)
  (comp (dev)
        (b/localInstall)
        (b/packDistro)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


