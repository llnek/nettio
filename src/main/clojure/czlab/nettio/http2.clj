;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.nettio.http2

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.niou.core :as cc]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
            [czlab.basal.core :as c]
            [czlab.nettio.core :as n])

  (:import [io.netty.handler.stream ChunkedWriteHandler]
           [io.netty.util AttributeKey]
           [java.util Map HashMap]
           [io.netty.buffer ByteBuf]
           [java.io OutputStream]
           [czlab.basal XData]
           [czlab.nettio
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
            Http2FrameAdapter
            Http2Headers
            Http2FrameListener
            DefaultHttp2Connection
            InboundHttp2ToHttpAdapterBuilder
            HttpToHttp2ConnectionHandlerBuilder]
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
(defn- netty-headers->ring
  [^Http2Headers hds]
  (c/preduce<map>
    (fn [acc ^String n]
      (assoc! acc
              n
              (mapv #(str %)
                    (HeadersUtils/getAllAsString hds n))))
    (HeadersUtils/namesAsString hds)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-aggregator<>
  {:tag ChannelHandler}
  ([] (h2-aggregator<> nil))
  ([^ChannelPromise pm]
   (letfn
     [(finito [ctx ^String sid]
        (let [^Map hh (n/get-akey ctx h2msgHkey)
              ^Map dd (n/get-akey ctx h2msgDkey)
              [^HttpRequest fake ^Attribute attr]
              (some-> dd (.get sid))
              hds (some-> hh (.get sid))]
          (some-> dd (.remove sid))
          (some-> hh (.remove sid))
          (if fake
            (.removeHttpDataFromClean (n/dfac?? ctx) fake attr))
          (try (n/fire-msg ctx
                           (c/object<> czlab.niou.core.Http2xMsg
                                       :body (n/gattr attr)
                                       :headers (netty-headers->ring hds)))
               (finally (some-> attr .release)))))
      (read-frame [ctx ^String sid]
        (let [^Map m (or (n/get-akey ctx h2msgDkey)
                         (n/set-akey ctx h2msgDkey (HashMap.)))
              [fake _] (.get m sid)]
          (if (nil? fake)
            (let [r (n/fake-req<>)]
              (.put m
                    sid
                    [r (.createAttribute (n/dfac?? ctx) r n/body-id)])))))
      (read-frame-ex [ctx ^String sid data end?]
        (let [^Map m (n/get-akey ctx h2msgDkey)
              ^Attribute attr (c/_2 (.get m sid))]
          (.addContent attr
                       (.retain ^ByteBuf data) (boolean end?))
          (if end? (finito ctx sid))))]
     (.build
       (proxy [H2HandlerBuilder][]
         (build [d e s]
           (proxy [H2Handler][d e s]
             (onSettingsRead [ctx ss]
               (c/try! (some-> pm .setSuccess)))
             (onDataRead [ctx sid data pad end?]
               (l/debug "rec'ved h2-data: sid#%s, end?=%s." sid end?)
               (c/do-with [b (+ pad (.readableBytes ^ByteBuf data))]
                 (read-frame ctx sid)
                 (read-frame-ex ctx sid data end?)))
             (onHeadersRead [ctx sid hds pad end?]
               (l/debug "rec'ved h2-headers: sid#%s, end?=%s." sid end?)
               (let [m (or (n/get-akey h2msgHkey ctx)
                           (n/set-akey h2msgHkey ctx (HashMap.)))]
                 (.put ^Map m sid hds)
                 (if end? (finito ctx sid)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn hx-pipeline
  [ch args]
  (let [{:keys [user-handler user-cb
                cors-cfg max-msg-size]} args
        conn (DefaultHttp2Connection. true)
        p (n/cpipe?? ch)
        listener (-> (InboundHttp2ToHttpAdapterBuilder. conn)
                     (.maxContentLength (int max-msg-size))
                     (.validateHttpHeaders false)
                     (.propagateSettings true)
                     (.build))]
    (n/pp->last p (-> (HttpToHttp2ConnectionHandlerBuilder.)
                      (.frameListener listener)
                      (.connection conn)
                      (.build)))
    (if cors-cfg
      (n/pp->last p "cors" (CorsHandler. cors-cfg)))
    (comment
      (n/pp->last p
                   "continue-expected??"
                   (continue-expected?? max-msg-size)))
    (n/pp->last p "chunker" (ChunkedWriteHandler.))
    (n/pp->last p "user-func" (n/app-handler user-handler user-cb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-pipeline
  [ch args]
  (let [p (n/cpipe?? ch)
        {:keys [user-handler user-cb]} args]
    (n/pp->last p "codec" (h2-aggregator<>))
    (n/pp->last p "user-func" (n/app-handler user-handler user-cb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


