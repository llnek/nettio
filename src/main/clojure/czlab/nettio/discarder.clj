;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty app - accepts and discards the request."
      :author "Kenneth Leung"}

  czlab.nettio.discarder

  (:gen-class)

  (:require [czlab.basal.process :refer [exitHook]]
            [czlab.basal.logging :as log])

  (:use [czlab.nettio.server]
        [czlab.basal.core]
        [czlab.basal.str]
        [czlab.nettio.core])

  (:import [io.netty.handler.codec.http HttpResponseStatus]
           [io.netty.handler.codec.http LastHttpContent]
           [czlab.nettio.server NettyWebServer]
           [clojure.lang APersistentMap]
           [czlab.jasal CU LifeCycle]
           [czlab.nettio
            WholeMessage
            WholeRequest
            InboundHandler]
           [io.netty.channel
            ChannelPipeline
            ChannelHandler
            Channel
            ChannelHandlerContext]
           [io.netty.bootstrap ServerBootstrap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

(defonce ^:private svr (atom nil))

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

  ([cb] (discardHTTPD<> cb nil))
  ([cb args]
   (do-with [^LifeCycle w (mutable<> NettyWebServer)]
     (.init w (assoc args
                     :hh1 (h1proxy cb))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn finzServer "" []
  (.stop ^LifeCycle @svr) (reset! svr nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -main "" [& args]
  (cond
    (< (count args) 2)
    (println "usage: discard host port")
    :else
    (let [^LifeCycle s (discardHTTPD<>
                         #(println "hello, poked by discarder"))]
      (.start s {:host (nth args 0)
                 :port (convInt (nth args 1) 8080)})
      (exitHook #(.stop s))
      (reset! svr s)
      (CU/block))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


