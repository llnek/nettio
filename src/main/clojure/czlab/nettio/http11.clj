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
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
            [czlab.basal.core :as c])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression WebSocketServerCompressionHandler]
           [io.netty.handler.codec.http.websocketx WebSocketServerProtocolHandler]
           [io.netty.handler.codec DecoderResultProvider DecoderResult]
           [java.net HttpCookie URL InetAddress InetSocketAddress]
           [io.netty.handler.stream ChunkedWriteHandler]
           [czlab.nettio DuplexHandler]
           [czlab.niou.core Http1xMsg]
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
(c/defmacro- set-origin
  [rsp origin]
  `(nc/set-header ~rsp
                  (nc/h1hdr* ~'ACCESS_CONTROL_ALLOW_ORIGIN) ~origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- vary-header
  [rsp] `(nc/set-header ~rsp (nc/h1hdr* ~'VARY) (nc/h1hdr* ~'ORIGIN)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol RspObjAPI
  ""
  (allow-creds [_ corsCfg] "")
  (allow-methods [_ corsCfg] "")
  (allow-headers [_ corsCfg] "")
  (max-age [_ corsCfg] "")
  (expose-hdrs [_ corsCfg] "")
  (set-origin? [_ ctx corsCfg] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol RspObjAPI
  HttpResponse
  (allow-creds [rspObj corsCfg]
    (if (and (:credentials? corsCfg)
             (not= "*"
                   (nc/get-header rspObj
                                  (nc/h1hdr* ACCESS_CONTROL_ALLOW_ORIGIN))))
      (nc/set-header rspObj
                     (nc/h1hdr* ACCESS_CONTROL_ALLOW_CREDENTIALS) "true")))
  (allow-methods [rspObj corsCfg]
    (c/when-some+ [m (:allowed-methods corsCfg)]
      (nc/set-header rspObj
                     (nc/h1hdr* ACCESS_CONTROL_ALLOW_METHODS) m)))
  (allow-headers [rspObj corsCfg]
    (c/when-some+ [h (:allowed-headers corsCfg)]
      (nc/set-header rspObj
                     (nc/h1hdr* ACCESS_CONTROL_ALLOW_HEADERS) h)))
  (max-age [rspObj corsCfg]
    (if (number? (:max-age corsCfg))
      (nc/set-header rspObj
                     (nc/h1hdr* ACCESS_CONTROL_MAX_AGE) (:max-age corsCfg))))
  (expose-hdrs [rspObj corsCfg]
    (c/when-some+ [h (:exposed-headers corsCfg)]
      (nc/set-header rspObj
                     (nc/h1hdr* ACCESS_CONTROL_EXPOSE_HEADERS) h)))
  (set-origin? [rspObj ctx corsCfg]
    (let [req (nc/get-akey nc/h1msg-key ctx)
          origin (cc/msg-header req (nc/h1hdr* ORIGIN))]
      (when (cc/msg-header? req (nc/h1hdr* ORIGIN))
        (cond (and (= "null" origin)
                   (:nullable? corsCfg))
              (c/do#true (set-origin rspObj "null"))
              (:any-origin? corsCfg)
              (c/do#true (if (:credentials? corsCfg)
                           (do (vary-header rspObj)
                               (set-origin rspObj origin))
                           (set-origin rspObj "*")))
              (c/eq-any? origin (:origins corsCfg))
              (c/do#true (vary-header rspObj)
                         (set-origin rspObj origin))
              :else
              (c/do#false (l/warn "Origin %s not configured." origin)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ReqMsgAPI
  ""
  (valid-origin? [_ ctx corsCfg] "")
  (cors-preflight? [_] "")
  (reply-preflight [_ ctx] "")
  (mock-request<+> [_] "")
  (is-websock? [_] "")
  (toggle->websock [_ ctx this] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ReqMsgAPI
  Http1xMsg
  (cors-preflight? [req]
    (and (= (:method req)
            (.name HttpMethod/OPTIONS))
         (cc/msg-header? req (nc/h1hdr* ORIGIN))
         (cc/msg-header? req (nc/h1hdr* ACCESS_CONTROL_REQUEST_METHOD))))
  (valid-origin? [req ctx corsCfg]
    (let [allowed (:origins corsCfg)
          origin (cc/msg-header req (nc/h1hdr* ORIGIN))]
      (cond (or (:any-origin? corsCfg)
                (not (cc/msg-header? req (nc/h1hdr* ORIGIN))))
            true
            (and (= "null" origin)
                 (:nullable? corsCfg))
            true
            (nil? allowed) true
            :else (c/eq-any? origin allowed))))
  (reply-preflight [req c]
    (let [{:keys [cors-cfg]} (nc/get-akey nc/chcfg-key c)
          ctx (c/cast? ChannelHandlerContext c)
          rspObj (nc/http-reply<+>)
          {:keys [is-keep-alive?]} req]
      (when (set-origin? rspObj ctx cors-cfg)
        (map #(% rspObj cors-cfg)
             [allow-methods allow-headers allow-creds max-age]))
      (HttpUtil/setKeepAlive rspObj is-keep-alive?)
      (nc/content-length! rspObj 0)
      (nc/close-cf (.writeAndFlush ctx rspObj) is-keep-alive? )))
  (mock-request<+> [req]
    (let [{:keys [headers uri2
                  version method]} req]
      (c/do-with [rc (DefaultFullHttpRequest.
                       (HttpVersion/valueOf version)
                       (HttpMethod/valueOf method) uri2)]
        (assert (c/is? HttpHeaders headers))
        (.set (.headers rc) ^HttpHeaders headers))))
  (toggle->websock [req ctx this]
    (let [{:keys [uri]} req
          pp (nc/cpipe ctx)
          {:keys [wsock-path]} (nc/get-akey nc/chcfg-key ctx)]
      (if-not (if-not (set? wsock-path)
                (= wsock-path uri) (c/in? wsock-path uri))
        (nc/reply-status ctx
                         (nc/scode* FORBIDDEN))
        (do (nc/pp->after pp
                          (nc/ctx-name pp this) "WSSCH"
                          (WebSocketServerCompressionHandler.))
            (nc/pp->after pp
                          "WSSCH" "WSSPH"
                          (WebSocketServerProtocolHandler. ^String uri nil true))
            (nc/pp->after pp
                          "WSSPH" "wsock-aggregator" mg/wsock-aggregator<>)
            (nc/safe-remove-handler* pp
                                     [HttpContentDecompressor
                                      HttpContentCompressor
                                      ChunkedWriteHandler
                                      "msg-agg" this])
            (nc/dbg-pipeline pp)
            (mg/fire-msg ctx (mock-request<+> req))))))
  (is-websock? [req]
    (and (c/embeds? (->> (nc/h1hdr* CONNECTION)
                         (cc/msg-header req) str c/lcase) "upgrade")
         (= "GET" (:method req))
         (c/embeds? (->> (nc/h1hdr* UPGRADE)
                         (cc/msg-header req) str c/lcase) "websocket"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- process-request
  [^ChannelHandlerContext ctx this req]
  (let
    [origin (cc/msg-header req (nc/h1hdr* ORIGIN))
     o? (cc/msg-header? req (nc/h1hdr* ORIGIN))
     {:keys [cors-cfg]} (nc/get-akey nc/chcfg-key ctx)]
    (l/debug "processing request: %s." req)
    (nc/set-akey nc/h1msg-key ctx req)
    (cond (is-websock? req)
          (do (toggle->websock req ctx this)
              (l/debug "req %s, websocket!" (u/objid?? req)))
          (cors-preflight? req)
          (do (l/debug "req %s, cors-preflight!" (u/objid?? req))
              (if (:enabled? cors-cfg)
                (reply-preflight req ctx)
                (nc/reply-status ctx
                                 (nc/scode* METHOD_NOT_ALLOWED))))
          (and (:enabled? cors-cfg)
               (not (valid-origin? req ctx cors-cfg)))
          (do (l/debug "req %s, cors-cfg enabled." (u/objid?? req))
              (nc/reply-status ctx (nc/scode* FORBIDDEN)))
          :else
          (try (mg/fire-msg ctx req)
               (finally (nc/ref-del req))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1req-handler<>
  "" ^ChannelHandler []
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg]
      (if-not (c/is? Http1xMsg msg)
        (mg/fire-msg ctx msg)
        (process-request ctx this msg)))
    (write [ctx msgObj _]
      (let [{:keys [cors-cfg]}
            (nc/get-akey nc/chcfg-key ctx)]
        (if (and (:enabled? cors-cfg)
                 (c/is? HttpResponse msgObj))
          (when (set-origin? msgObj ctx cors-cfg)
            (map #(% msgObj cors-cfg)
                 [allow-creds expose-hdrs])
            (l/debug "cors-cfg, set-origin = true.")))
        (.write ^ChannelHandlerContext ctx msgObj _)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

