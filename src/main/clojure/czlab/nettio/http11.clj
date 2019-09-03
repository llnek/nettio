;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.http11

  (:require [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.nettio.msgs :as mg]
            [czlab.nettio.core :as nc]
            [czlab.niou.core :as cc]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
            [czlab.basal.core :as c])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression WebSocketServerCompressionHandler]
           [io.netty.handler.codec.http.websocketx WebSocketServerProtocolHandler]
           [io.netty.handler.codec DecoderResultProvider DecoderResult]
           [java.net HttpCookie URL InetAddress InetSocketAddress]
           [io.netty.handler.stream ChunkedWriteHandler]
           [czlab.nettio DuplexHandler]
           [java.nio.charset Charset]
           [java.io OutputStream]
           [io.netty.handler.codec.http.multipart
            HttpDataFactory
            Attribute
            HttpPostRequestDecoder]
           [io.netty.util
            CharsetUtil
            AttributeKey
            ReferenceCounted
            ReferenceCountUtil]
           [czlab.basal XData]
           [io.netty.buffer
            Unpooled
            ByteBuf
            ByteBufHolder
            ByteBufAllocator]
           [io.netty.handler.codec.http
            HttpContentDecompressor
            HttpContentCompressor
            DefaultCookie
            Cookie
            HttpVersion
            HttpMethod
            HttpUtil
            FullHttpResponse
            FullHttpRequest
            LastHttpContent
            HttpHeaderValues
            HttpHeaderNames
            HttpContent
            HttpMessage
            HttpResponse
            DefaultFullHttpResponse
            DefaultFullHttpRequest
            DefaultHttpResponse
            DefaultHttpRequest
            HttpRequest
            HttpResponseStatus
            HttpHeaders
            QueryStringDecoder]
           [java.security KeyStore]
           [clojure.lang
            APersistentMap
            APersistentSet
            APersistentVector]
           [javax.net.ssl
            KeyManagerFactory
            TrustManagerFactory]
           [io.netty.channel
            ChannelOutboundInvoker
            ChannelDuplexHandler
            ChannelPipeline
            ChannelFuture
            ChannelOption
            ChannelHandler
            Channel
            ChannelHandlerContext
            ChannelFutureListener]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cors-preflight?
  [req]
  (and (= (.name HttpMethod/OPTIONS)
          (:method req))
       (cc/msg-header? req HttpHeaderNames/ORIGIN)
       (cc/msg-header? req HttpHeaderNames/ACCESS_CONTROL_REQUEST_METHOD)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- valid-origin?
  [ctx corsCfg]
  (let [req (nc/get-akey nc/h1msg-key ctx)
        allowed (:origins corsCfg)
        o? (cc/msg-header? req HttpHeaderNames/ORIGIN)
        origin (cc/msg-header req HttpHeaderNames/ORIGIN)]
    (cond (or (:any-origin? corsCfg) (not o?)) true
          (and (= "null" origin) (:nullable? corsCfg)) true
          (nil? allowed) true
          :else (s/eq-any? origin allowed))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private set-origin
  [rsp origin]
  `(nc/set-header ~rsp
                  HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private echo-request-origin
  [rsp origin] `(set-origin ~rsp ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private set-vary-header
  [rsp] `(nc/set-header ~rsp HttpHeaderNames/VARY HttpHeaderNames/ORIGIN))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private set-null-origin
  [rsp] `(set-origin ~rsp "null"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private set-any-origin
  [rsp] `(set-origin ~rsp "*"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-credentials
  [rspObj corsCfg]
  (if (and (:credentials? corsCfg)
           (not= "*"
                 (nc/get-header rspObj
                                HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN)))
     (nc/set-header rspObj
                    HttpHeaderNames/ACCESS_CONTROL_ALLOW_CREDENTIALS "true")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-methods
  "" [rspObj corsCfg]
  (c/when-some+ [m (:allowed-methods corsCfg)]
                (nc/set-header rspObj
                               HttpHeaderNames/ACCESS_CONTROL_ALLOW_METHODS m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-headers
  "" [rspObj corsCfg]
  (c/when-some+ [h (:allowed-headers corsCfg)]
                (nc/set-header rspObj
                               HttpHeaderNames/ACCESS_CONTROL_ALLOW_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-max-age
  "" [rspObj corsCfg]
  (if (number? (:max-age corsCfg))
    (nc/set-header rspObj
                   HttpHeaderNames/ACCESS_CONTROL_MAX_AGE (:max-age corsCfg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-expose-headers
  [rspObj corsCfg]
  (c/when-some+ [h (:exposed-headers corsCfg)]
    (nc/set-header rspObj
                   HttpHeaderNames/ACCESS_CONTROL_EXPOSE_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-origin?
  [ctx rspObj corsCfg]
  (let [req (nc/get-akey nc/h1msg-key ctx)
        o? (cc/msg-header? req HttpHeaderNames/ORIGIN)
        origin (cc/msg-header req HttpHeaderNames/ORIGIN)]
    (if o?
      (cond
        (and (= "null" origin)
             (:nullable? corsCfg))
        (c/do#true
          (set-null-origin rspObj))

        (:any-origin? corsCfg)
        (c/do#true
          (if (:credentials? corsCfg)
            (do (echo-request-origin rspObj origin)
                (set-vary-header rspObj))
            (set-any-origin rspObj)))

        (s/eq-any? origin (:origins corsCfg))
        (c/do#true
          (set-origin rspObj origin)
          (set-vary-header rspObj))

        :else
        (c/do#false
          (l/warn "Origin %s not configured." origin))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- reply-preflight
  [^ChannelHandlerContext ctx req]
  (let [{:keys [cors-cfg]}
        (nc/get-akey nc/chcfg-key ctx)
        rspObj (nc/http-reply<+>)
        {:keys [is-keep-alive?]} req]
    (when (set-origin? ctx rspObj cors-cfg)
      (set-allow-methods rspObj cors-cfg)
      (set-allow-headers rspObj cors-cfg)
      (set-allow-credentials rspObj cors-cfg)
      (set-max-age rspObj cors-cfg))
    (HttpUtil/setKeepAlive rspObj is-keep-alive?)
    (nc/content-length! rspObj 0)
    (nc/close-cf (.writeAndFlush ctx rspObj) is-keep-alive? )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- mock-request<+>
  ^FullHttpRequest [req]
  (let [{:keys [headers uri2
                version method]} req
        rc (DefaultFullHttpRequest.
              (HttpVersion/valueOf version)
              (HttpMethod/valueOf method) uri2)]
    (assert (c/is? HttpHeaders headers))
    (.set (.headers rc) ^HttpHeaders headers) rc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- toggle->websock
  [ctx this req]
  (let [{:keys [wsock-path] :as cfg}
        (nc/get-akey nc/chcfg-key ctx)
        {:keys [uri] :as msg} req
        _ (l/debug "===> ch-config = %s." cfg)
        _ (l/debug "===> req-msg = %s." msg)
        ^ChannelPipeline pp (nc/cpipe ctx)
        r2 (mock-request<+> req)
        uri? (if (set? wsock-path)
               (c/in? wsock-path uri)
               (= wsock-path uri))]
    (if-not uri?
      (nc/reply-status ctx
                       (.code HttpResponseStatus/FORBIDDEN))
      (do
        (nc/pp->after pp
                      (nc/ctx-name pp this) "WSSCH"
                      (WebSocketServerCompressionHandler.))
        (nc/pp->after pp
                      "WSSCH" "WSSPH"
                      (WebSocketServerProtocolHandler. ^String uri nil true))
        (nc/pp->after pp
                      "WSSPH" "wsock-aggregator" mg/wsock-aggregator<>)
        (nc/safe-remove-handler pp HttpContentDecompressor)
        (nc/safe-remove-handler pp HttpContentCompressor)
        (nc/safe-remove-handler pp ChunkedWriteHandler)
        (nc/safe-remove-handler pp "msg-agg")
        (.remove pp ^ChannelHandler this)
        (nc/dbg-pipeline pp)
        (.fireChannelRead ^ChannelHandlerContext ctx r2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- is-websock?
  [req]
  (let [cn (->> HttpHeaderNames/CONNECTION
                (cc/msg-header req) str s/lcase)
        ws (->> HttpHeaderNames/UPGRADE
                (cc/msg-header req) str s/lcase)]
    ;(l/debug "checking if it's a websock request......")
    (and (= "GET" (:method req))
         (s/embeds? cn "upgrade")
         (s/embeds? ws "websocket"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- process-request
  [^ChannelHandlerContext ctx this req]
  (let
    [origin (cc/msg-header req HttpHeaderNames/ORIGIN)
     o? (cc/msg-header? req HttpHeaderNames/ORIGIN)
     {:keys [cors-cfg]}
     (nc/get-akey nc/chcfg-key ctx)]
    (l/debug "processing request: %s." req)
    (nc/set-akey nc/h1msg-key ctx req)
    (cond (is-websock? req)
          (do (toggle->websock ctx this req)
              (l/debug "req %s, websocket!" (u/objid?? req)))
          (cors-preflight? req)
          (do (l/debug "req %s, cors-preflight!" (u/objid?? req))
              (if (:enabled? cors-cfg)
                (reply-preflight ctx req)
                (nc/reply-status ctx
                                 (.code HttpResponseStatus/METHOD_NOT_ALLOWED))))
          (and (:enabled? cors-cfg)
               (not (valid-origin? ctx cors-cfg)))
          (do (l/debug "req %s, cors-cfg enabled." (u/objid?? req))
              (nc/reply-status ctx
                               (.code HttpResponseStatus/FORBIDDEN)))
          :else
          (try (.fireChannelRead ctx req)
               (finally (nc/ref-del req))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- process-write
  [ctx msgObj _]
  (let [{:keys [cors-cfg]}
        (nc/get-akey nc/chcfg-key ctx)]
    (if (and (:enabled? cors-cfg)
             (c/is? HttpResponse msgObj))
      (when (set-origin? ctx msgObj cors-cfg)
        (set-allow-credentials msgObj cors-cfg)
        (set-expose-headers msgObj cors-cfg)
        (l/debug "cors-cfg enabled, set-origin = true.")))
    (.write ^ChannelHandlerContext ctx msgObj _)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1req-handler<>
  "" ^ChannelHandler []
  (proxy [DuplexHandler][false]
    (write [ctx msg _]
      (process-write ctx msg _))
    (readMsg [ctx msg]
      (if (satisfies? cc/HttpMsgGist msg)
        (process-request ctx this msg)
        (.fireChannelRead ^ChannelHandlerContext ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

