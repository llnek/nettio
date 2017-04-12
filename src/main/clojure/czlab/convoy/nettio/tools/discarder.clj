;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty app - accepts and discards the request."
      :author "Kenneth Leung"}

  czlab.convoy.nettio.tools.discarder

  (:gen-class)

  (:require [czlab.basal.process :refer [exitHook]]
            [czlab.basal.logging :as log])

  (:use [czlab.convoy.nettio.server]
        [czlab.convoy.net.server]
        [czlab.basal.core]
        [czlab.basal.str]
        [czlab.convoy.nettio.core])

  (:import [io.netty.handler.codec.http HttpResponseStatus]
           [io.netty.handler.codec.http LastHttpContent]
           [clojure.lang APersistentMap]
           [czlab.convoy.nettio
            WholeMessage
            WholeRequest
            InboundHandler]
           [czlab.jasal CU]
           [io.netty.channel
            ChannelPipeline
            ChannelHandler
            Channel
            ChannelHandlerContext]
           [io.netty.bootstrap ServerBootstrap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

(defonce ^:private svrboot (atom nil))
(defonce ^:private svrchan (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h1proxy "" [cb]
  (proxy [InboundHandler][]
    (channelRead0 [ctx msg]
      (replyStatus ctx) (try! (cb)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn discardHTTPD<>
  "Drops the req and returns OK"
  {:tag ServerBootstrap}

  ([cb] (discardHTTPD<> cb nil))
  ([cb args]
   (createServer<> :netty/http (fn [_] {:h1 (h1proxy cb)}) args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn finzServer "" []
  (stopServer @svrchan)
  (reset! svrboot nil)
  (reset! svrchan nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -main "" [& args]
  (cond
    (< (count args) 2)
    (println "usage: discard host port")
    :else
    (let [bs (discardHTTPD<> #(println "hello, poked by discarder"))
          ch (startServer bs {:host (nth args 0)
                              :port (convInt (nth args 1) 8080)})]
      (exitHook #(stopServer ch))
      (reset! svrboot bs)
      (reset! svrchan ch)
      (CU/block))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


