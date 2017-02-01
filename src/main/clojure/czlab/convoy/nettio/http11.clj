;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.convoy.nettio.http11

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.convoy.nettio.core]
        [czlab.basal.str]
        [czlab.basal.io]
        [czlab.basal.core])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression WebSocketServerCompressionHandler]
           [io.netty.handler.codec.http.websocketx WebSocketServerProtocolHandler]
           [czlab.convoy.nettio H1ReqAggregator DuplexHandler]
           [io.netty.handler.codec DecoderResultProvider DecoderResult]
           [java.net HttpCookie URL InetAddress InetSocketAddress]
           [io.netty.handler.stream ChunkedWriteHandler]
           [czlab.convoy.net RouteCracker]
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
           [czlab.jasal XData]
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
;;
(defn- corsPreflight? "" [^HttpRequest req]
  (and (= (.name HttpMethod/OPTIONS)
          (getMethod req))
       (hasHeader? req HttpHeaderNames/ORIGIN)
       (hasHeader? req HttpHeaderNames/ACCESS_CONTROL_REQUEST_METHOD)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- validOrigin? "" [^ChannelHandlerContext ctx corsCfg]
  (let [^HttpRequest req (getAKey ctx h1msg-key)
        origin (getHeader req HttpHeaderNames/ORIGIN)
        o? (hasHeader? req HttpHeaderNames/ORIGIN)
        allowed (:origins corsCfg)]
    (cond
      (or (:anyOrigin? corsCfg)
          (not o?))
      true

      (and (= "null" origin)
           (:nullable? corsCfg))
      true

      (nil? allowed)
      true

      :else
      (eqAny? origin allowed))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private setOrigin "" [rsp origin]
  `(setHeader ~rsp HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro
  ^:private echoRequestOrigin "" [rsp origin] `(setOrigin ~rsp ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private setVaryHeader "" [rsp]
  `(setHeader ~rsp HttpHeaderNames/VARY HttpHeaderNames/ORIGIN))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private setNullOrigin "" [rsp] `(setOrigin ~rsp "null"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private setAnyOrigin "" [rsp] `(setOrigin ~rsp "*"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setAllowCredentials "" [^HttpResponse rsp corsCfg]
  (if (and (:credentials? corsCfg)
           (not= "*"
                 (getHeader rsp
                            HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN)))
     (setHeader rsp
                HttpHeaderNames/ACCESS_CONTROL_ALLOW_CREDENTIALS "true")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setAllowMethods "" [^HttpResponse rsp corsCfg]
  (when-some+ [m (:allowedMethods corsCfg)]
    (setHeader rsp
               HttpHeaderNames/ACCESS_CONTROL_ALLOW_METHODS m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setAllowHeaders "" [^HttpResponse rsp corsCfg]
  (when-some+ [h (:allowedHeaders corsCfg)]
    (setHeader rsp
               HttpHeaderNames/ACCESS_CONTROL_ALLOW_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setMaxAge "" [^HttpResponse rsp corsCfg]
  (if (number? (:maxAge corsCfg))
    (setHeader rsp
               HttpHeaderNames/ACCESS_CONTROL_MAX_AGE (:maxAge corsCfg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setExposeHeaders "" [^HttpResponse rsp corsCfg]
  (when-some+ [h (:exposedHeaders corsCfg)]
    (setHeader rsp
               HttpHeaderNames/ACCESS_CONTROL_EXPOSE_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setOrigin? "" [^ChannelHandlerContext ctx
                      ^HttpResponse rsp
                      corsCfg]
  (let
    [^HttpRequest req (getAKey ctx h1msg-key)
     origin (getHeader req HttpHeaderNames/ORIGIN)
     o? (hasHeader? req HttpHeaderNames/ORIGIN)]
    (if o?
      (cond
        (and (= "null" origin)
             (:nullable? corsCfg))
        (do->true
          (setNullOrigin rsp))

        (:anyOrigin? corsCfg)
        (do->true
          (if (:credentials? corsCfg)
            (do
              (echoRequestOrigin rsp origin)
              (setVaryHeader rsp))
            (setAnyOrigin rsp)))

        (eqAny? origin (:origins corsCfg))
        (do->true
          (setOrigin rsp origin)
          (setVaryHeader rsp))

        :else
        (do->false
          (log/warn "Origin %s not configured" origin)))
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- replyPreflight "" [^ChannelHandlerContext ctx
                          ^HttpRequest req]
  (let
    [{:keys [corsCfg]} (getAKey ctx chcfg-key)
     ka? (HttpUtil/isKeepAlive req)
     rsp (httpFullReply<>)]
    (when (setOrigin? ctx rsp corsCfg)
      (setAllowMethods rsp corsCfg)
      (setAllowHeaders rsp corsCfg)
      (setAllowCredentials rsp corsCfg)
      (setMaxAge rsp corsCfg))
    (HttpUtil/setKeepAlive rsp ka?)
    (closeCF (.writeAndFlush ctx rsp) ka?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- toggleToWebsock "" [^ChannelHandlerContext ctx
                           ^ChannelHandler this
                           ^HttpRequest req]
  (let [uri (.path (QueryStringDecoder. (.uri req)))
        r2 (mockFullRequest<> req)
        {:keys [websockPath]}
        (getAKey ctx chcfg-key)
        pp (.pipeline ctx)]
    (if (not= uri websockPath)
      (replyStatus ctx (.code HttpResponseStatus/FORBIDDEN))
      (do
        (.addAfter pp
                   (ctxName pp this)
                   "WSSCH"
                   (WebSocketServerCompressionHandler.))
        (.addAfter pp
                   "WSSCH"
                   "WSSPH"
                   (WebSocketServerProtocolHandler. uri nil true))
        (safeRemoveHandler pp HttpContentDecompressor)
        (safeRemoveHandler pp HttpContentCompressor)
        (safeRemoveHandler pp ChunkedWriteHandler)
        (safeRemoveHandler pp H1ReqAggregator)
        (.remove pp this)
        (.fireChannelRead ctx r2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- processRequest "" [^ChannelHandler this
                          ^ChannelHandlerContext ctx
                          ^HttpRequest req]
  (let
    [origin (getHeader req HttpHeaderNames/ORIGIN)
     o? (hasHeader? req HttpHeaderNames/ORIGIN)
     {:keys [corsCfg]} (getAKey ctx chcfg-key)
     ka? (HttpUtil/isKeepAlive req)
     _ (log/debug "processRequest: %s" req)
     _ (setAKey ctx h1msg-key req)
     rc
     (cond
       (isWEBSock? req)
       (do->false (toggleToWebsock ctx this req))

       (corsPreflight? req)
       (do->false
         (if (:enabled? corsCfg)
           (replyPreflight ctx req)
           (replyStatus ctx (.code HttpResponseStatus/METHOD_NOT_ALLOWED))))

       (and (:enabled? corsCfg)
            (not (validOrigin? ctx corsCfg)))
       (do->false
         (replyStatus ctx (.code HttpResponseStatus/FORBIDDEN)))

       :else
       (do->true
         (.fireChannelRead ctx req)))]
    (if-not rc
      (ReferenceCountUtil/release req))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- processWrite "" [^ChannelHandlerContext ctx msg _]
  (let [{:keys [corsCfg]}
        (getAKey ctx chcfg-key)]
    (if (and (:enabled? corsCfg)
             (inst? HttpResponse msg))
      (when (setOrigin? ctx msg corsCfg)
        (setAllowCredentials msg corsCfg)
        (setExposeHeaders msg corsCfg)))
    (.write ctx msg _)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- processOther
  "" [^ChannelHandlerContext ctx msg] (.fireChannelRead ctx msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1reqHandler<> "" ^ChannelHandler []
  (proxy [DuplexHandler][]
    (channelRead [ctx msg]
      (if (inst? HttpRequest msg)
        (processRequest this ctx msg)
        (processOther ctx msg)))
    (write [ctx msg _]
      (processWrite ctx msg _))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF
