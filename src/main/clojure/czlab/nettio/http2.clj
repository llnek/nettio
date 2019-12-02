;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.nettio.http2

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.niou.core :as cc]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
            [czlab.basal.core :as c]
            [czlab.nettio.http :as h1]
            [czlab.nettio.core :as n])

  (:import [io.netty.handler.stream ChunkedWriteHandler]
           [io.netty.util AttributeKey]
           [java.net URI InetSocketAddress]
           [java.util Map HashMap]
           [io.netty.buffer ByteBuf]
           [java.io OutputStream]
           [czlab.basal XData]
           [czlab.niou Headers]
           [czlab.niou.core Http2xMsg]
           [czlab.nettio
            InboundH2ToH1
            InboundHandler
            CPAggregator
            H2Handler
            H2HandlerBuilder]
           [io.netty.channel
            ChannelHandler
            ChannelPromise
            Channel
            ChannelHandlerContext]
           [io.netty.handler.codec
            HeadersUtils]
           [io.netty.handler.codec.http
            HttpRequest]
           [io.netty.handler.codec.http2
            HttpConversionUtil
            Http2CodecUtil
            Http2FrameAdapter
            Http2Settings
            Http2Headers
            Http2FrameListener
            DefaultHttp2Connection
            DefaultHttp2Headers
            InboundHttp2ToHttpAdapterBuilder
            HttpToHttp2ConnectionHandlerBuilder
            HttpConversionUtil$ExtensionHeaderNames]
           [io.netty.handler.codec.http.cors
            CorsConfig
            CorsHandler]
           [io.netty.handler.codec.http.multipart
            Attribute]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(c/defonce- ^AttributeKey h2msgHkey (n/akey<> :h2msg-h))
(c/defonce- ^AttributeKey h2msgDkey (n/akey<> :h2msg-d))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpResultMsgReplyer
  Http2xMsg
  (reply-result
    ([theRes] (cc/reply-result theRes nil))
    ([theRes _] (let [ch (:socket theRes)]
                  (some-> ch (n/write-msg theRes))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h2xhdr*

  [name]
  `(.toString
     (.text ~(symbol
               (str "HttpConversionUtil$ExtensionHeaderNames/"  (str name))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- std->headers

  ^DefaultHttp2Headers
  [^Headers hds]

  (reduce
    #(let [^Http2Headers acc %1
           ^String n %2
           vs (.get hds n)]
       (if (c/one? vs)
         (.set acc n (first vs))
         (doseq [v vs] (.add acc n v))) acc)
    (DefaultHttp2Headers.) (.keySet hds)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- headers->std

  [^Http2Headers hds]

  (reduce
    (fn [^Headers acc ^String n]
      (let [arr (HeadersUtils/getAllAsString hds n)]
        (if (c/one? arr)
          (.set acc n (first arr))
          (doseq [v arr]
            (.add acc n v)))
        acc))
    (Headers.) (HeadersUtils/namesAsString hds)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def h2-settings<>

  (proxy [InboundHandler][]
    (onRead [ctx ch msg]
      (cond (c/is? Http2Settings msg)
            (do
              (n/ref-del msg)
              (l/debug "just ate h2 settings!"))
            :else (n/fire-msg ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- on-h2-write

  [^H2Handler self
   ^ChannelHandlerContext ctx msg ^ChannelPromise cp]

  (let
    [{:keys [request-method
             uri uri2
             ^XData body
             ^Headers headers]} msg
     mtd (c/ucase (name request-method))
     ch (n/ch?? ctx)
     body? (.hasContent body)
     ^bytes ct (if body? (i/x->bytes body) nil)
     clen (if ct (alength ct) 0)
     _ (.set headers "content-length" (str clen))
     _ (if-not (c/hgl? (.getFirst headers "content-type"))
         (.set headers "content-type" "application/octet-stream"))
     h2hds (std->headers headers)
     ssl? (some? (n/get-ssl?? ch))
     enc (.encoder self)
     pagg (CPAggregator. cp ch (.executor ctx))
     depid (n/hv->int headers (h2xhdr* STREAM_DEPENDENCY_ID) 0)
     weight (n/hv->int headers
                       (h2xhdr* STREAM_WEIGHT)
                       Http2CodecUtil/DEFAULT_PRIORITY_WEIGHT)
     sid (int (or (n/hv-int headers (h2xhdr* STREAM_ID))
                  (.. enc connection local incrementAndGetNextStreamId)))]
    (.authority h2hds (.getHostName ^InetSocketAddress (.remoteAddress ch)))
    (.scheme h2hds (if ssl? "https" "http"))
    (.method h2hds mtd)
    (.path h2hds (.getRawPath ^URI uri2))
    (try
      (.writeHeaders enc ctx sid h2hds
                     depid weight
                     ;exclusive padding endstream?
                     false 0 (not (pos? clen)) (.newPromise pagg))
      (when (pos? clen)
        (.writeData enc ctx sid
                    (n/bbuf?? ct ch) 0 true (.newPromise pagg)))
      (catch Throwable t
        (.onError self ctx true t)
        (.setFailure pagg t))
      (finally
        (.doneAllocatingPromises pagg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-handler<>

  [rcp max-mem-size]

  (letfn
    [(finz [ctx sid]
       (let [hh (n/akey?? ctx h2msgHkey)
             dd (n/akey?? ctx h2msgDkey)
             attr (c/mdel! dd sid)
             hds (c/mdel! hh sid)
             b (n/get-http-data attr true)
             msg (assoc (cc/h2-msg<> :get "/poo" (headers->std hds) b)
                        :socket (n/ch?? ctx))]
         (try (n/fire-msg ctx msg)
              (finally (.release ^Attribute attr)))))
     (read0 [ctx sid]
       (let [m (or (n/akey?? ctx h2msgDkey)
                   (n/akey+ ctx h2msgDkey (HashMap.)))
             attr (c/mget m sid)]
         (if (nil? attr)
           (c/mput! m
                    sid
                    (n/data-attr<> max-mem-size)))))
     (read1 [ctx sid data end?]
       (let [m (n/akey?? ctx h2msgDkey)
             attr (c/mget m sid)]
         (.addContent ^Attribute
                      attr
                      (.retain ^ByteBuf data) (boolean end?))
         (if end? (finz ctx sid))))]
    (let [c (DefaultHttp2Connection. (nil? rcp))]
      (.buildEx
        (proxy [H2HandlerBuilder][c]
          (newHandler [d e s]
            (proxy [H2Handler][d e s]
              (write [ctx msg cp]
                (if (c/is? Http2xMsg msg)
                  (on-h2-write this ctx msg cp)
                  (.parWrite ^H2Handler this ctx msg cp)))
              (onSettingsRead [ctx ss]
                (if rcp (deliver rcp (n/ch?? ctx)))
                (l/debug "%s h2 settings: received."
                         (if rcp "client" "server")))
              (onData [ctx sid data pad end?]
                (l/debug "rec'ved h2-data: sid#%s, end?=%s." sid end?)
                (c/do-with [b (+ pad (.readableBytes ^ByteBuf data))]
                  (read0 ctx sid)
                  (read1 ctx sid data end?)))
              (onHeaders [ctx sid hds pad end?]
                (l/debug "rec'ved h2-headers: sid#%s, end?=%s." sid end?)
                (let [m (or (n/akey?? ctx h2msgHkey)
                            (n/akey+ ctx h2msgHkey (HashMap.)))]
                  (c/mput! m sid hds)
                  (if end? (finz ctx sid)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn hx-pipeline

  [p args]

  (let [{:keys [user-cb
                cors-cfg max-frame-size]} args
        co (DefaultHttp2Connection. true)
        f (proxy [InboundH2ToH1]
                 [co (int max-frame-size) false false]
            (onSettings [ctx ch msg]
              (l/debug "server h2 settings received.")))]
    (n/pp->last p "svr-h2x" (-> (HttpToHttp2ConnectionHandlerBuilder.)
                                (.connection co)
                                ;(.server true) MUST NOT CALL THIS
                                (.frameListener f)
                                (.build)))
    (when (not-empty cors-cfg)
      (->> (h1/config->cors cors-cfg)
           CorsHandler. (n/pp->last p "cors")))
    (n/pp->last p "h1" h1/h1-simple<>)
    (n/pp->last p n/user-cb (n/app-handler user-cb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-pipeline

  [p args]

  (let [{:keys [user-cb
                max-mem-size]} args]
    (n/pp->last p "svr-h2f" (h2-handler<> nil max-mem-size))
    (n/pp->last p "user-func" (n/app-handler user-cb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

