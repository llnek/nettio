;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.aggwsk

  (:require [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.nettio.core :as nc]
            [czlab.convoy.core :as cc]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

  (:import [io.netty.util AttributeKey ReferenceCountUtil]
           [io.netty.handler.codec.http.multipart
            Attribute
            HttpDataFactory]
           [io.netty.handler.codec.http.websocketx
            BinaryWebSocketFrame
            TextWebSocketFrame
            CloseWebSocketFrame
            WebSocketFrame
            PongWebSocketFrame
            PingWebSocketFrame
            ContinuationWebSocketFrame]
           [czlab.nettio.core NettyWsockMsg]
           [czlab.nettio DuplexHandler]
           [java.nio.charset Charset]
           [czlab.jasal XData]
           [io.netty.buffer ByteBuf]
           [io.netty.channel
            ChannelHandler
            ChannelDuplexHandler
            ChannelHandlerContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
(defonce ^:private ^AttributeKey wsock-res-key (nc/akey<> "wsock-res"))
(def ^:private ^String body-attr-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrameEx "" [^ChannelHandlerContext ctx
                       ^ContinuationWebSocketFrame msg]

  (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
        last? (.isFinalFragment msg)
        {:keys [^Attribute attr fake] :as rc}
        (nc/getAKey ctx wsock-res-key)]
    (.addContent attr (.. msg content retain) last?)
    (ReferenceCountUtil/release msg)
    (when last?
      (.removeHttpDataFromClean df fake attr)
      (let [x (i/xdata<>
                (if (.isInMemory attr)
                  (.get attr)
                  (c/doto->> (.getFile attr) (.renameTo attr))))]
        (.release attr)
        (->> (assoc (dissoc rc :attr :fake) :body x)
             (c/object<> NettyWsockMsg )
             (.fireChannelRead ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrame
  "" [^ChannelHandlerContext ctx ^WebSocketFrame msg]

  (let [rc {:isText? (c/ist? TextWebSocketFrame msg)
            :charset (Charset/forName "utf-8")} ]
    (cond
      (ist? PongWebSocketFrame msg)
      (->> (c/object<> NettyWsockMsg
                       (assoc rc :pong? true))
           (.fireChannelRead ctx ))
      (.isFinalFragment msg)
      (->> (c/object<> NettyWsockMsg
                       (assoc rc
                              :body (i/xdata<>
                                      (toByteArray
                                        (.content msg)))))
           (.fireChannelRead ctx ))
      :else
      (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
            req (nc/fakeARequest<>)
            ^Attribute a (.createAttribute df
                                           req body-attr-id)
            rc (assoc rc :attr a)]
        (.addContent a (.. msg content retain) false)
        (nc/setAKey ctx wsock-res-key rc)))
    (ReferenceCountUtil/release msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private wsock-agg
  (proxy [DuplexHandler][]
    (onRead [ctx msg]
      (cond
        (c/ist? ContinuationWebSocketFrame msg)
        (readFrameEx ctx msg)
        (c/ist? TextWebSocketFrame msg)
        (readFrame ctx msg)
        (c/ist? BinaryWebSocketFrame msg)
        (readFrame ctx msg)
        (c/ist? PongWebSocketFrame msg)
        (readFrame ctx msg)
        :else
        (.fireChannelRead ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wsockAggregator<>
  "A handler that
  aggregates frames into a full message" ^ChannelHandler [] wsock-agg)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

