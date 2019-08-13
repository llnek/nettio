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
            [czlab.convoy.core :as cc]
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
(defn- cors-preflight? "" [req]
  (and (= (.name HttpMethod/OPTIONS)
          (:method req))
       (cc/msg-header? req HttpHeaderNames/ORIGIN)
       (cc/msg-header? req HttpHeaderNames/ACCESS_CONTROL_REQUEST_METHOD)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- valid-origin? "" [ctx corsCfg]
  (let [req (nc/get-akey ctx nc/h1msg-key)
        allowed (:origins corsCfg)
        o? (cc/msg-header? req HttpHeaderNames/ORIGIN)
        origin (cc/msg-header req HttpHeaderNames/ORIGIN)]
    (cond
      (or (:any-origin? corsCfg) (not o?)) true
      (and (= "null" origin) (:nullable? corsCfg)) true
      (nil? allowed) true
      :else
      (s/eq-any? origin allowed))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private set-origin "" [rsp origin]
  `(nc/set-header ~rsp
                  HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private
  echo-request-origin "" [rsp origin] `(set-origin ~rsp ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private
  set-vary-header "" [rsp]
  `(nc/set-header ~rsp HttpHeaderNames/VARY HttpHeaderNames/ORIGIN))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private set-null-origin "" [rsp] `(set-origin ~rsp "null"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private set-any-origin "" [rsp] `(set-origin ~rsp "*"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-credentials "" [rsp corsCfg]
  (if (and (:credentials? corsCfg)
           (not= "*"
                 (nc/get-header rsp
                                HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN)))
     (nc/set-header rsp
                    HttpHeaderNames/ACCESS_CONTROL_ALLOW_CREDENTIALS "true")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-methods "" [rsp corsCfg]
  (c/when-some+ [m (:allowed-methods corsCfg)]
                (nc/set-header rsp
                               HttpHeaderNames/ACCESS_CONTROL_ALLOW_METHODS m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-headers "" [rsp corsCfg]
  (c/when-some+ [h (:allowed-headers corsCfg)]
                (nc/set-header rsp
                               HttpHeaderNames/ACCESS_CONTROL_ALLOW_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-max-age "" [rsp corsCfg]
  (if (number? (:max-age corsCfg))
    (nc/set-header rsp
                   HttpHeaderNames/ACCESS_CONTROL_MAX_AGE (:max-age corsCfg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-expose-headers "" [rsp corsCfg]
  (c/when-some+ [h (:exposed-headers corsCfg)]
                (nc/set-header rsp
                               HttpHeaderNames/ACCESS_CONTROL_EXPOSE_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-origin? "" [ctx rsp corsCfg]
  (let [req (nc/get-akey ctx nc/h1msg-key)
        o? (cc/msg-header? req HttpHeaderNames/ORIGIN)
        origin (cc/msg-header req HttpHeaderNames/ORIGIN)]
    (if o?
      (cond
        (and (= "null" origin)
             (:nullable? corsCfg))
        (c/do#true
          (set-null-origin rsp))

        (:any-origin? corsCfg)
        (c/do#true
          (if (:credentials? corsCfg)
            (do (echo-request-origin rsp origin)
                (set-vary-header rsp))
            (set-any-origin rsp)))

        (s/eq-any? origin (:origins corsCfg))
        (c/do#true
          (set-origin rsp origin)
          (set-vary-header rsp))

        :else
        (c/do#false
          (l/warn "Origin %s not configured." origin))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- reply-preflight "" [ctx req]
  (let [{:keys [cors-cfg]} (nc/get-akey ctx nc/chcfg-key)
        {:keys [is-keep-alive?]} req
        rsp (nc/http-reply<+>)]
    (when (set-origin? ctx rsp cors-cfg)
      (set-allow-methods rsp cors-cfg)
      (set-allow-headers rsp cors-cfg)
      (set-allow-credentials rsp cors-cfg)
      (set-max-age rsp cors-cfg))
    (HttpUtil/setKeepAlive rsp is-keep-alive?)
    (nc/content-length! rsp 0)
    (nc/close-cf (.writeAndFlush
                   ^ChannelHandlerContext ctx rsp) is-keep-alive? )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- toggle->websock
  "" [ctx this req]
  (let [{:keys [wsock-path] :as cfg} (nc/get-akey ctx nc/chcfg-key)
        {:keys [uri] :as msg} req
        _ (l/debug "===> ch-config = %s." cfg)
        _ (l/debug "===> req-msg = %s." msg)
        r2 (nc/mock-request<+> req)
        pp (nc/cpipe ctx)
        uri? (if (set? wsock-path)
               (c/in? wsock-path uri)
               (= wsock-path uri))]
    (if-not uri?
      (nc/reply-status ctx
                       (nc/scode HttpResponseStatus/FORBIDDEN))
      (do
        (.addAfter pp
                   (nc/ctx-name pp this)
                   "WSSCH"
                   (WebSocketServerCompressionHandler.))
        (.addAfter pp
                   "WSSCH"
                   "WSSPH"
                   (WebSocketServerProtocolHandler. ^String uri nil true))
        (.addAfter pp
                   "WSSPH"
                   "wsock-aggregator" mg/wsock-aggregator<>)
        (nc/safe-remove-handler pp HttpContentDecompressor)
        (nc/safe-remove-handler pp HttpContentCompressor)
        (nc/safe-remove-handler pp ChunkedWriteHandler)
        (nc/safe-remove-handler pp "msg-agg")
        (.remove pp ^ChannelHandler this)
        (.fireChannelRead ^ChannelHandlerContext ctx r2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- process-request
  "" [^ChannelHandler this ctx req]
  (let
    [origin (cc/msg-header req HttpHeaderNames/ORIGIN)
     o? (cc/msg-header? req HttpHeaderNames/ORIGIN)
     {:keys [cors-cfg]} (nc/get-akey ctx nc/chcfg-key)
     ka? (:is-keep-alive? req)
     _ (l/debug "process-request: %s." req)
     _ (nc/set-akey ctx nc/h1msg-key req)
     rc
     (cond
       (nc/is-websock? req)
       (c/do#false
         (toggle->websock ctx this req)
         (l/debug "req %s, websocket!" (u/objid?? req)))

       (cors-preflight? req)
       (c/do#false
         (l/debug "req %s, cors-preflight!" (u/objid?? req))
         (if (:enabled? cors-cfg)
           (reply-preflight ctx req)
           (nc/reply-status ctx
                            (nc/scode HttpResponseStatus/METHOD_NOT_ALLOWED))))

       (and (:enabled? cors-cfg)
            (not (valid-origin? ctx cors-cfg)))
       (c/do#false
         (l/debug "req %s, cors-cfg enabled." (u/objid?? req))
         (nc/reply-status ctx
                          (nc/scode HttpResponseStatus/FORBIDDEN)))

       :else
       (c/do#true
         (.fireChannelRead ^ChannelHandlerContext ctx req)))]
    (if-not rc
      (nc/ref-del req))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- process-write "" [ctx msg _]
  (let [{:keys [cors-cfg]}
        (nc/get-akey ctx nc/chcfg-key)]
    (if (and (:enabled? cors-cfg)
             (c/is? HttpResponse msg))
      (when (set-origin? ctx msg cors-cfg)
        (set-allow-credentials msg cors-cfg)
        (set-expose-headers msg cors-cfg)
        (l/debug "cors-cfg enabled, set-origin = true.")))
    (.write ^ChannelHandlerContext ctx msg _)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- process-other "" [ctx msg]
  (.fireChannelRead ^ChannelHandlerContext ctx msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1req-handler<>
  "" ^ChannelHandler []

  (proxy [DuplexHandler][false]
    (readMsg [ctx msg]
      (if (satisfies? cc/HttpMsgGist msg)
        (process-request this ctx msg)
        (process-other ctx msg)))
    (Write [ctx msg _]
      (process-write ctx msg _))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

