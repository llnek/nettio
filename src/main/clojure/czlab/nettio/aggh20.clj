;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.aggh20

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.nettio.core]
        [czlab.convoy.core]
        [czlab.basal.str]
        [czlab.basal.io]
        [czlab.basal.core])

  (:import [io.netty.util AttributeKey ReferenceCountUtil]
           [io.netty.handler.codec.http2
            Http2FrameAdapter
            Http2Headers
            Http2FrameListener]
           [io.netty.handler.codec.http.multipart
            Attribute
            HttpDataFactory]
           [czlab.nettio.core NettyH2Msg]
           [java.nio.charset Charset]
           [czlab.jasal XData]
           [io.netty.buffer ByteBuf]
           [java.util HashMap Map]
           [io.netty.channel
            ChannelHandler
            ChannelPromise
            ChannelHandlerContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
(defonce ^:private ^AttributeKey h2msg-h-key (akey<> "h2msg-hdrs"))
(defonce ^:private ^AttributeKey h2msg-d-key (akey<> "h2msg-data"))
(def ^:private ^String body-attr-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- finito "" [^ChannelHandlerContext ctx sid]
  (let [^HttpDataFactory df (getAKey ctx dfac-key)
        ^Map hh (getAKey ctx h2msg-h-key)
        ^Map dd (getAKey ctx h2msg-d-key)
        [^HttpRequest fake
         ^Attribute attr] (.get dd sid)
        hds (.get hh sid)]
    (.removeHttpDataFromClean df fake attr)
    (.remove dd sid)
    (.remove hh sid)
    (let [x (xdata<>
              (if (.isInMemory attr)
                (.get attr)
                (doto->> (.getFile attr) (.renameTo attr))))]
      (.release attr)
      (->> (object<> NettyH2Msg {:headers hds :body x})
           (.fireChannelRead ctx)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrameEx "" [^ChannelHandlerContext ctx
                       sid
                       ^ByteBuf data end?]
  (let [^HttpDataFactory df (getAKey ctx dfac-key)
        ^Map m (getAKey ctx h2msg-d-key)
        [_ ^Attribute attr] (.get m sid)]
    (.addContent attr (.retain data) end?)
    (if end? (finito ctx sid))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrame
  "" [^ChannelHandlerContext ctx sid]

  (let [^HttpDataFactory df (getAKey ctx dfac-key)
        ^Map m (or (getAKey ctx h2msg-d-key)
                   (doto->> (HashMap.)
                            (setAKey ctx h2msg-d-key)))
        [fake attr] (.get m sid)]
    (if (nil? fake)
      (let [r (fakeARequest<>)]
        (.put m
              sid
              [r (.createAttribute df r body-attr-id)])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h20Aggregator<>
  "A handler which aggregates frames into a full message"
  ^Http2FrameListener
  [^ChannelPromise pm]
  (proxy [Http2FrameAdapter][]
    (onSettingsRead [ctx ss]
      (trye! nil (some-> pm .setSuccess)))
    (onDataRead [ctx sid data pad end?]
      (log/debug "rec'ved data: sid#%s, end?=%s" sid end?)
      (do-with [b (+ pad (.readableBytes ^ByteBuf data))]
        (readFrame ctx sid)
        (readFrameEx ctx sid data end?)))
    (onHeadersRead
      ([ctx sid hds pad end?]
       (log/debug "rec'ved headers: sid#%s, end?=%s" sid end?)
       (let [^Map m (or (getAKey ctx h2msg-h-key)
                        (doto->> (HashMap.)
                                 (setAKey ctx h2msg-h-key)))]
         (.put m sid hds)
         (if end? (finito ctx sid))))
      ([ctx sid hds
        dep wgt ex? pad end?]
       (.onHeadersRead ^Http2FrameAdapter this ctx sid hds pad end?)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

