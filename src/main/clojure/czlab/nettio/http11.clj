;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.http11

  (:require [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.nettio.aggwsk :as ws]
            [czlab.nettio.core :as nc]
            [czlab.convoy.core :as cc]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
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
(defn- corsPreflight? "" [req]
  (and (= (.name HttpMethod/OPTIONS)
          (:method req))
       (nc/msgHeader? req HttpHeaderNames/ORIGIN)
       (nc/msgHeader? req HttpHeaderNames/ACCESS_CONTROL_REQUEST_METHOD)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- validOrigin? "" [ctx corsCfg]
  (let [req (nc/getAKey ctx nc/h1msg-key)
        origin (nc/msgHeader req HttpHeaderNames/ORIGIN)
        o? (nc/msgHeader? req HttpHeaderNames/ORIGIN)
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
      (s/eqAny? origin allowed))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private setOrigin "" [rsp origin]
  `(nc/setHeader ~rsp
                 HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private
  echoRequestOrigin "" [rsp origin] `(setOrigin ~rsp ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private
  setVaryHeader "" [rsp]
  `(nc/setHeader ~rsp HttpHeaderNames/VARY HttpHeaderNames/ORIGIN))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private setNullOrigin "" [rsp] `(setOrigin ~rsp "null"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private setAnyOrigin "" [rsp] `(setOrigin ~rsp "*"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setAllowCredentials "" [rsp corsCfg]
  (if (and (:credentials? corsCfg)
           (not= "*"
                 (nc/getHeader rsp
                               HttpHeaderNames/ACCESS_CONTROL_ALLOW_ORIGIN)))
     (nc/setHeader rsp
                   HttpHeaderNames/ACCESS_CONTROL_ALLOW_CREDENTIALS "true")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setAllowMethods "" [rsp corsCfg]
  (c/when-some+ [m (:allowedMethods corsCfg)]
                (nc/setHeader rsp
                              HttpHeaderNames/ACCESS_CONTROL_ALLOW_METHODS m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setAllowHeaders "" [rsp corsCfg]
  (c/when-some+ [h (:allowedHeaders corsCfg)]
                (nc/setHeader rsp
                              HttpHeaderNames/ACCESS_CONTROL_ALLOW_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setMaxAge "" [rsp corsCfg]
  (if (number? (:maxAge corsCfg))
    (nc/setHeader rsp
                  HttpHeaderNames/ACCESS_CONTROL_MAX_AGE (:maxAge corsCfg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setExposeHeaders "" [rsp corsCfg]
  (c/when-some+ [h (:exposedHeaders corsCfg)]
                (nc/setHeader rsp
                              HttpHeaderNames/ACCESS_CONTROL_EXPOSE_HEADERS h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- setOrigin? "" [ctx rsp corsCfg]
  (let [req (nc/getAKey ctx nc/h1msg-key)
        origin (nc/msgHeader req HttpHeaderNames/ORIGIN)
        o? (nc/msgHeader? req HttpHeaderNames/ORIGIN)]
    (if o?
      (cond
        (and (= "null" origin)
             (:nullable? corsCfg))
        (c/do->true
          (setNullOrigin rsp))

        (:anyOrigin? corsCfg)
        (c/do->true
          (if (:credentials? corsCfg)
            (do
              (echoRequestOrigin rsp origin)
              (setVaryHeader rsp))
            (setAnyOrigin rsp)))

        (s/eqAny? origin (:origins corsCfg))
        (c/do->true
          (setOrigin rsp origin)
          (setVaryHeader rsp))

        :else
        (c/do->false
          (log/warn "Origin %s not configured" origin)))
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- replyPreflight "" [ctx req]
  (let [{:keys [corsCfg]} (nc/getAKey ctx
                                      nc/chcfg-key)
        {:keys [isKeepAlive?]}
        req
        rsp (nc/httpFullReply<>)]
    (when (setOrigin? ctx rsp corsCfg)
      (setAllowMethods rsp corsCfg)
      (setAllowHeaders rsp corsCfg)
      (setAllowCredentials rsp corsCfg)
      (setMaxAge rsp corsCfg))
    (HttpUtil/setKeepAlive rsp isKeepAlive?)
    (nc/closeCF (.writeAndFlush
                  ^ChannelHandlerContext ctx rsp) isKeepAlive? )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- toggleToWebsock
  "" [ctx this req]

  (let [{:keys [wsockPath]} (nc/getAKey ctx nc/chcfg-key)
        {:keys [uri]} req
        r2 (nc/mockFullRequest<> req)
        pp (nc/cpipe ctx)
        uri? (if (set? wsockPath)
               (c/in? wsockPath uri)
               (= wsockPath uri))]
    (if-not uri?
      (nc/replyStatus ctx
                      (nc/scode HttpResponseStatus/FORBIDDEN))
      (do
        (.addAfter pp
                   (nc/ctxName pp this)
                   "WSSCH"
                   (WebSocketServerCompressionHandler.))
        (.addAfter pp
                   "WSSCH"
                   "WSSPH"
                   (WebSocketServerProtocolHandler. uri nil true))
        (.addAfter pp
                   "WSSPH"
                   "wsock-aggregator" (ws/wsockAggregator<>))
        (nc/safeRemoveHandler pp HttpContentDecompressor)
        (nc/safeRemoveHandler pp HttpContentCompressor)
        (nc/safeRemoveHandler pp ChunkedWriteHandler)
        (nc/safeRemoveHandler pp "msg-agg")
        (.remove pp ^ChannelHandler this)
        (.fireChannelRead ^ChannelHandlerContext ctx r2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- processRequest
  "" [^ChannelHandler this ctx req]

  (let
    [origin (nc/msgHeader req HttpHeaderNames/ORIGIN)
     o? (nc/msgHeader? req HttpHeaderNames/ORIGIN)
     {:keys [corsCfg]} (nc/getAKey ctx nc/chcfg-key)
     ka? (:isKeepAlive? req)
     _ (log/debug "processRequest: %s" req)
     _ (nc/setAKey ctx nc/h1msg-key req)
     rc
     (cond
       (nc/isWEBSock? req)
       (c/do->false (toggleToWebsock ctx this req))

       (corsPreflight? req)
       (c/do->false
         (if (:enabled? corsCfg)
           (replyPreflight ctx req)
           (nc/replyStatus ctx
                           (nc/scode HttpResponseStatus/METHOD_NOT_ALLOWED))))

       (and (:enabled? corsCfg)
            (not (validOrigin? ctx corsCfg)))
       (c/do->false
         (nc/replyStatus ctx
                         (nc/scode HttpResponseStatus/FORBIDDEN)))

       :else
       (c/do->true
         (.fireChannelRead ^ChannelHandlerContext ctx req)))]
    (if-not rc
      (ReferenceCountUtil/release req))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- processWrite "" [ctx msg _]
  (let [{:keys [corsCfg]}
        (nc/getAKey ctx nc/chcfg-key)]
    (if (and (:enabled? corsCfg)
             (c/ist? HttpResponse msg))
      (when (setOrigin? ctx msg corsCfg)
        (setAllowCredentials msg corsCfg)
        (setExposeHeaders msg corsCfg)))
    (.write ^ChannelHandlerContext ctx msg _)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- processOther "" [ctx msg]
  (.fireChannelRead ^ChannelHandlerContext ctx msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1reqHandler<>
  "" ^ChannelHandler []

  (proxy [DuplexHandler][]
    (onRead [ctx msg]
      (if (satisfies? HttpMsgGist msg)
        (processRequest this ctx msg)
        (processOther ctx msg)))
    (onWrite [ctx msg _]
      (processWrite ctx msg _))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

