;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; Copyright © 2013-2022, Kenneth Leung. All rights reserved.

(ns czlab.test.nettio.wsock

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
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
              (c/start {:port 5556}))
          _ (u/pause 888)
          c (cc/ws-conn MODULE host port {:uri "/crap"})]
      (u/pause 500)
      (c/stop w)
      (u/pause 500)
      (c/is? Throwable c)))

  (ensure??
    "websock/remote-port"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<> #(println "msg = " %1))
              (c/start {:port 5556}))
          _ (u/pause 888)
          c (cc/ws-conn MODULE host port {:uri "/websock"})]
      (u/pause 500)
      (c/stop w)
      (c/finz c)
      (u/pause 500)
      (and c (== 5556
                 (cc/remote-port c)))))

  (ensure??
    "websock/stop"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<> #(println "msg = " %1))
              (c/start {:port 5556}))
          _ (u/pause 888)
          c (cc/ws-conn MODULE host port {:uri "/websock"})
          ok? (and (c/is? czlab.niou.core.HClient c)
                   (cc/is-open? c))]
      (u/pause 500)
      (c/stop w)
      (c/finz c)
      (u/pause 500)
      (and ok?
           (not (cc/is-open? c)))))

  (ensure??
    "websock/text"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:server-key "*"
                 :user-cb #(cc/reply-result %1)})
              (c/start {:port 8443}))
          _ (u/pause 888)
          out (atom nil)
          c (cc/ws-conn
                MODULE
                host port
                {:server-cert "*"
                 :uri "/websock"
                 :user-cb #(reset! out (:body %1))})]
      (cc/write-msg c
                    (cc/ws-text<> "hello"))
      (u/pause 666)
      (c/stop w)
      (c/finz c)
      (u/pause 500)
      (.equals "hello" (i/x->str @out))))


  (ensure??
    "websock/blob"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<> #(cc/reply-result %1))
              (c/start {:port 5556}))
          _ (u/pause 888)
          out (atom nil)
          c (cc/ws-conn MODULE
                           host port
                           {:uri "/websock"
                            :user-cb #(reset! out (:body %1))})]
      (cc/write-msg c (cc/ws-bytes<> HELLO-BYTES))
      (u/pause 666)
      (c/stop w)
      (c/finz c)
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
              (c/start {:port 5556}))
          _ (u/pause 888)
          c (cc/ws-conn MODULE
                           host port
                           {:uri "/websock"
                            :user-cb
                            #(when (:is-pong? %1)
                               (reset! pong true))})]
      (cc/write-msg c cc/ws-ping<>)
      (u/pause 666)
      (c/stop w)
      (c/finz c)
      (u/pause 500)
      (and (false? @ping) (true? @pong))))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-wsock nettio-test-wsock
  (ct/is (c/clj-test?? test-wsock)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


