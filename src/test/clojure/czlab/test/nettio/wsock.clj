;; Copyright ©  2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.test.nettio.wsock

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.xpis :as po]
            [czlab.niou.core :as cc]
            [czlab.nettio.resp :as nr]
            [czlab.nettio.client :as cl]
            [czlab.nettio.server :as sv]
            [czlab.basal.core :as c
             :refer [ensure?? ensure-thrown??]])

  (:import [czlab.basal XData]))

 ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce- HELLO-BYTES (i/x->bytes "hello"))


(c/defonce- MODULE (cl/web-client-module<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-wsock

  (ensure??
    "websock/bad-uri"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:wsock-path "/websock"
                 :user-cb #(println "msg = " %1)})
              (po/start {:port 5556}))
          _ (u/pause 888)
          c (cc/hc-ws-conn MODULE host port {:uri "/crap"})]
      (u/pause 500)
      (po/stop w)
      (u/pause 500)
      (c/is? Throwable c)))

  (ensure??
    "websock/remote-port"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<> #(println "msg = " %1))
              (po/start {:port 5556}))
          _ (u/pause 888)
          c (cc/hc-ws-conn MODULE host port {:uri "/websock"})]
      (u/pause 500)
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and c (== 5556
                 (cc/cc-remote-port c)))))

  (ensure??
    "websock/stop"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<> #(println "msg = " %1))
              (po/start {:port 5556}))
          _ (u/pause 888)
          c (cc/hc-ws-conn MODULE host port {:uri "/websock"})
          ok? (and (c/is? czlab.niou.core.ClientConnect c)
                   (cc/cc-is-open? c))]
      (u/pause 500)
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and ok?
           (not (cc/cc-is-open? c)))))

  (ensure??
    "websock/text"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:server-key "*"
                 :user-cb #(cc/reply-result %1)})
              (po/start {:port 8443}))
          _ (u/pause 888)
          out (atom nil)
          c (cc/hc-ws-conn
                MODULE
                host port
                {:server-cert "*"
                 :uri "/websock"
                 :user-cb #(reset! out (:body %1))})]
      (cc/cc-write c
                   (cc/ws-text<> "hello"))
      (u/pause 666)
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (.equals "hello" (i/x->str @out))))


  (ensure??
    "websock/blob"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<> #(cc/reply-result %1))
              (po/start {:port 5556}))
          _ (u/pause 888)
          out (atom nil)
          c (cc/hc-ws-conn MODULE
                           host port
                           {:uri "/websock"
                            :user-cb #(reset! out (:body %1))})]
      (cc/cc-write c (cc/ws-bytes<> HELLO-BYTES))
      (u/pause 666)
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (u/obj-eq? HELLO-BYTES (i/x->bytes @out))))

  (ensure??
    "websock/ping"
    (let [pong (atom false)
          ping (atom false)
          {:keys [host port] :as w}
          (-> (sv/web-server-module<>
                 #(when (:is-ping? %1)
                    (reset! ping true)))
              (po/start {:port 5556}))
          _ (u/pause 888)
          c (cc/hc-ws-conn MODULE
                           host port
                           {:uri "/websock"
                            :user-cb
                            #(when (:is-pong? %1)
                               (reset! pong true))})]
      (cc/cc-write c cc/ws-ping<>)
      (u/pause 666)
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and (false? @ping) (true? @pong))))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-wsock nettio-test-wsock
  (ct/is (c/clj-test?? test-wsock)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


