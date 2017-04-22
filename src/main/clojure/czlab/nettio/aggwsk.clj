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

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.nettio.core]
        [czlab.convoy.core]
        [czlab.basal.str]
        [czlab.basal.io]
        [czlab.basal.core])

  (:import [io.netty.util AttributeKey ReferenceCountUtil]
           [czlab.convoy.core WebsocketMessageObj]
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
           [czlab.nettio H1Aggregator]
           [java.nio.charset Charset]
           [czlab.jasal XData]
           [io.netty.buffer ByteBuf]
           [io.netty.channel
            ChannelHandler
            ChannelDuplexHandler
            ChannelHandlerContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
(defonce ^:private ^AttributeKey wsock-res-key (akey<> "wsock-res"))
(def ^:private ^String body-attr-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrameEx "" [^ChannelHandlerContext ctx
                       ^ContinuationWebSocketFrame msg]

  (let [^HttpDataFactory df (getAKey ctx dfac-key)
        last? (.isFinalFragment msg)
        {:keys [^Attribute attr fake] :as rc}
        (getAKey ctx wsock-res-key)]
    (.addContent attr (.. msg content retain) last?)
    (ReferenceCountUtil/release msg)
    (when last?
      (.removeHttpDataFromClean df fake attr)
      (let [x (xdata<>
                (if (.isInMemory attr)
                  (.get attr)
                  (doto->> (.getFile attr) (.renameTo attr))))]
        (.release attr)
        (->> (assoc (dissoc rc :attr :fake) :body x)
             (object<> WebsocketMessageObj )
             (.fireChannelRead ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrame
  "" [^ChannelHandlerContext ctx ^WebSocketFrame msg]

  (let [rc {:isText? (ist? TextWebSocketFrame msg)
            :charset (Charset/forName "utf-8")} ]
    (cond
      (ist? PongWebSocketFrame msg)
      (->> (object<> WebSocketMessageObj
                     (assoc rc :pong? true))
           (.fireChannelRead ctx ))
      (.isFinalFragment msg)
      (->> (object<> WebsocketMessageObj
                     (assoc rc
                            :body (xdata<>
                                    (toByteArray
                                      (.content msg)))))
           (.fireChannelRead ctx ))
      :else
      (let [^HttpDataFactory df (getAKey ctx dfac-key)
            req (fakeARequest<>)
            ^Attribute a (.createAttribute df
                                           req body-attr-id)
            rc (assoc rc :attr a)]
        (.addContent a (.. msg content retain) false)
        (setAKey ctx wsock-res-key rc)))
    (ReferenceCountUtil/release msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- aggRead "" [^ChannelHandlerContext ctx msg]

  (cond
    (ist? ContinuationWebSocketFrame msg)
    (readFrameEx ctx msg)
    (ist? TextWebSocketFrame msg)
    (readFrame ctx msg)
    (ist? BinaryWebSocketFrame msg)
    (readFrame ctx msg)
    (ist? PongWebSocketFrame msg)
    (readFrame ctx msg)
    :else
    (.fireChannelRead ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private wsock-agg
  (proxy [H1Aggregator][]
    (channelRead [ctx msg] (aggRead ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wsockAggregator<>
  "A handler which aggregates frames into a full message"
  ^ChannelHandler
  []
  wsock-agg)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

