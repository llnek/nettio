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

  (:require [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.nettio.core :as nc]
            [czlab.convoy.core :as cc]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

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
(defonce ^:private ^AttributeKey h2msg-h-key (nc/akey<> "h2msg-hdrs"))
(defonce ^:private ^AttributeKey h2msg-d-key (nc/akey<> "h2msg-data"))
(def ^:private ^String body-attr-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- finito "" [^ChannelHandlerContext ctx sid]
  (let [^HttpDataFactory df (nc/getAKey ctx dfac-key)
        ^Map hh (nc/getAKey ctx h2msg-h-key)
        ^Map dd (nc/getAKey ctx h2msg-d-key)
        [^HttpRequest fake
         ^Attribute attr]
        (some-> dd (.get sid))
        hds (some-> hh (.get sid))]
    (if fake
      (.removeHttpDataFromClean df fake attr))
    (some-> dd (.remove sid))
    (some-> hh (.remove sid))
    (let [x
          (if attr
            (i/xdata<> (if (.isInMemory attr)
                         (.get attr)
                         (c/doto->> (.getFile attr)
                                    (.renameTo attr))))
            (i/xdata<>))
          msg (c/object<> NettyH2Msg
                          {:headers hds :body x})]
      (some-> attr .release)
      (log/debug "finito: fire msg upstream: %s" msg)
      (.fireChannelRead ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrameEx "" [^ChannelHandlerContext ctx
                       sid
                       ^ByteBuf data end?]
  (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
        ^Map m (nc/getAKey ctx h2msg-d-key)
        [_ ^Attribute attr] (.get m sid)]
    (.addContent attr (.retain data) end?)
    (if end? (finito ctx sid))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readFrame
  "" [^ChannelHandlerContext ctx sid]

  (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
        ^Map m (or (nc/getAKey ctx h2msg-d-key)
                   (c/doto->> (HashMap.)
                              (nc/setAKey ctx h2msg-d-key)))
        [fake attr] (.get m sid)]
    (if (nil? fake)
      (let [r (nc/fakeARequest<>)]
        (.put m
              sid
              [r (.createAttribute df r body-attr-id)])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h20Aggregator<>
  "A handler which aggregates frames into a full message"
  {:tag Http2FrameListener}
  ([] (h20Aggregator<> nil))
  ([^ChannelPromise pm]
   (proxy [Http2FrameAdapter][]
     (onSettingsRead [ctx ss]
       (trye! nil (some-> pm .setSuccess)))
     (onDataRead [ctx sid data pad end?]
       (log/debug "rec'ved data: sid#%s, end?=%s" sid end?)
       (c/do-with [b (+ pad (.readableBytes ^ByteBuf data))]
                  (readFrame ctx sid)
                  (readFrameEx ctx sid data end?)))
     (onHeadersRead
       ([ctx sid hds pad end?]
        (log/debug "rec'ved headers: sid#%s, end?=%s" sid end?)
        (let [^Map m (or (nc/getAKey ctx h2msg-h-key)
                         (c/doto->> (HashMap.)
                                    (nc/setAKey ctx h2msg-h-key)))]
          (.put m sid hds)
          (if end? (finito ctx sid))))
       ([ctx sid hds
         dep wgt ex? pad end?]
        (.onHeadersRead ^Http2FrameAdapter this ctx sid hds pad end?))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

