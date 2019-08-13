;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty app - accepts and discards the request."
      :author "Kenneth Leung"}

  czlab.nettio.apps.discard

  (:gen-class)

  (:require [czlab.basal.proc :as p]
            [czlab.basal.log :as l]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.str :as s]
            [czlab.nettio.core :as nc]
            [czlab.nettio.server :as sv])


  (:import [io.netty.handler.codec.http HttpResponseStatus]
           [io.netty.handler.codec.http LastHttpContent]
           [czlab.nettio InboundHandler]
           [clojure.lang APersistentMap]
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
(defn- h1proxy "" [cb]
  (proxy [InboundHandler][true]
    (readMsg [ctx _]
      (nc/reply-status ctx) (c/try! (cb)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn discard-httpd<>
  "Drops the req and returns OK"

  ([cb] (discard-httpd<> cb nil))
  ([cb args]
   (sv/netty-web-server<> (assoc args
                                 :hh1 (h1proxy cb)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn finz-server "" []
  (when @svr
    (sv/stop-server! @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main "" [& args]
  (cond
    (< (count args) 2)
    (println "usage: discard host port")
    :else
    (let [s (discard-httpd<>
              #(println "hello, poked by discarder"))]
      (sv/start-web-server! s
                            {:host (nth args 0)
                             :port (c/s->int (nth args 1) 8080)})
      (p/exit-hook #(sv/stop-server! s))
      (reset! svr s)
      (u/block!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


