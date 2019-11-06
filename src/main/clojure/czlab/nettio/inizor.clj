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

  czlab.nettio.inizor

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.io :as i]
            [czlab.basal.log :as l]
            [czlab.basal.util :as u]
            [czlab.niou.routes :as cr]
            [czlab.nettio.core :as n]
            [czlab.nettio.http :as h1]
            [czlab.nettio.http2 :as h2])

  (:import [javax.net.ssl KeyManagerFactory TrustManagerFactory]
           [io.netty.handler.stream ChunkedWriteHandler]
           [io.netty.handler.ssl.util SelfSignedCertificate]
           [io.netty.handler.logging LogLevel]
           [io.netty.handler.codec.http2
            Http2SecurityUtil
            Http2FrameLogger
            DefaultHttp2Connection
            InboundHttp2ToHttpAdapterBuilder
            HttpToHttp2ConnectionHandlerBuilder
            DelegatingDecompressorFrameListener]
           [io.netty.handler.codec.http
            FullHttpResponse
            HttpClientCodec
            HttpServerCodec
            DefaultHttpHeaders
            HttpObjectAggregator]
           [czlab.nettio
            ChannelInizer
            InboundHandler
            DuplexHandler
            APNHttp2Handler
            APNHttpXHandler]
           [io.netty.handler.ssl
            SslContext
            OpenSsl
            SslProvider
            SslContextBuilder
            ApplicationProtocolConfig
            ApplicationProtocolConfig$Protocol
            ApplicationProtocolNames
            SupportedCipherSuiteFilter
            ApplicationProtocolNegotiationHandler
            ApplicationProtocolConfig$SelectorFailureBehavior
            ApplicationProtocolConfig$SelectedListenerFailureBehavior]
           [java.security KeyStore]
           [java.util List]
           [io.netty.util
            AttributeKey]
           [io.netty.channel
            ChannelInitializer
            ChannelPipeline
            ChannelPromise
            ChannelFuture
            Channel
            ChannelHandler
            ChannelHandlerContext]
           [io.netty.handler.codec.http.websocketx
            CloseWebSocketFrame
            WebSocketVersion
            WebSocketClientHandshakerFactory
            WebSocketClientHandshaker]
           [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketClientCompressionHandler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(defrecord WSInizor [user])
(defrecord H2Inizor [])
(defrecord H1Inizor [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce- ^AttributeKey rsp-key  (n/akey<> :cli-resp))
(c/defonce- ^AttributeKey cf-key  (n/akey<> :wsock-future))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce- ^{:tag ChannelHandler}
  client-hdlr
  (proxy [InboundHandler][]
    (readMsg [ctx msg]
      (when-some
        [p (n/akey?? ctx rsp-key)]
        (deliver p msg)
        (n/akey- ctx rsp-key)))
    (exceptionCaught [ctx err]
      (try (when-some
             [p (n/akey?? ctx rsp-key)]
             (deliver p err)
             (n/akey- ctx rsp-key))
           (finally (n/close! ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn webc-ssl-inizor<>
  ^ChannelHandler
  [server-cert rcp {:keys [protocol max-msg-size] :as args}]
  (letfn
    [(h2hdlr []
       (let [co (DefaultHttp2Connection. false)]
         (.build
           (-> (HttpToHttp2ConnectionHandlerBuilder.)
               (.connection co)
               (.frameListener
                 (->> (.build
                        (-> (InboundHttp2ToHttpAdapterBuilder. co)
                            (.propagateSettings true)
                            (.maxContentLength (int max-msg-size))))
                      (DelegatingDecompressorFrameListener. co)))
               (.frameLogger (Http2FrameLogger. LogLevel/INFO))))))]
    (proxy [ChannelInizer][]
      (onHandlerAdded [cx]
        (deliver rcp (n/ch?? cx)))
      (onError [_ e]
        (deliver rcp e))
      (onInitChannel [ch ^ChannelPipeline pp]
        (n/client-ssl?? pp server-cert args)
        (if-not (.equals "2" protocol)
          (do
            (n/pp->last pp "codec" (HttpClientCodec.))
            (n/pp->last pp "aggregator" (h1/http-adder args))
            (n/pp->last pp "chunker" (ChunkedWriteHandler.))
            (n/pp->last pp "user-cb" client-hdlr))
          (do
            (n/pp->last pp
                        (proxy [APNHttp2Handler][]
                          (cfgH2 [pp]
                            (n/pp->last pp "h2" (h2hdlr))
                            (n/pp->last pp "user-cb" client-hdlr))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn webc-inizor<>
  ^ChannelHandler
  [rcp args]
  (proxy [ChannelInizer][]
    (onHandlerAdded [cx]
      (deliver rcp (n/ch?? cx)))
    (onError [_ e]
      (deliver rcp e))
    (onInitChannel [ch pp]
      (n/pp->last pp "codec" (HttpClientCodec.))
      (n/pp->last pp "aggregator" (h1/http-adder args))
      (n/pp->last pp "chunker" (ChunkedWriteHandler.))
      (n/pp->last pp "user-cb" client-hdlr))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn websock-inizor<>
  ^ChannelHandler
  [rcp server-cert {:keys [uri user-cb] :as args}]
  (letfn
    [(wsock-hdlr []
       (proxy [InboundHandler][]
         (exceptionCaught [ctx err] (n/close! ctx))
         (readMsg [ctx msg] (user-cb (n/akey?? ctx n/cc-key) msg))))
     (wsh<> [^WebSocketClientHandshaker handshaker]
       (letfn
         [(cb [^ChannelFuture ff]
            (deliver rcp
                     (if (.isSuccess ff)
                       (.channel ff)
                       (or (.cause ff) (Exception. "Conn error!")))))]
         (proxy [InboundHandler][true]
           (handlerAdded [^ChannelHandlerContext ctx]
             (-> ^ChannelPromise (n/akey+ ctx
                                          cf-key
                                          (.newPromise ctx))
                 (.addListener (n/cfop<> cb)))
             (l/debug "wsc handler-added."))
           (channelActive [ctx]
             (l/debug "wsc starting hand-shake...")
             (.handshake handshaker (n/ch?? ctx)))
           (exceptionCaught [ctx err]
             (if-some [^ChannelPromise
                       f (n/akey?? ctx cf-key)]
               (if-not (.isDone f)
                 (.setFailure f ^Throwable err)))
             (n/close! ctx)
             (l/warn "%s." (.getMessage ^Throwable err)))
           (readMsg [ctx msg]
             (let [ch (n/ch?? ctx)
                   ^ChannelPromise
                   f (n/akey?? ctx cf-key)]
               (cond
                 (not (.isHandshakeComplete handshaker))
                 (do (l/debug "finzing the hand-shake...")
                     (.finishHandshake handshaker
                                       ch ^FullHttpResponse msg)
                     (.setSuccess f)
                     (l/debug "finz'ed the hand-shake... success!"))
                 (c/is? FullHttpResponse msg)
                 (do (u/throw-ISE
                       "Unexpected Response (rc=%s)."
                       (.status ^FullHttpResponse msg)))
                 (c/is? CloseWebSocketFrame msg)
                 (do (n/close! ctx)
                     (l/debug "received close frame."))
                 :else
                 (n/fire-msg ctx (n/ref-add msg))))))))]
    (proxy [ChannelInizer][]
      (onInitChannel [ch ^ChannelPipeline pp]
        (n/client-ssl?? pp server-cert args)
        (n/pp->last pp "codec" (HttpClientCodec.))
        (n/pp->last pp "aggregator" (HttpObjectAggregator. 96000))
        (n/pp->last pp "wcc" WebSocketClientCompressionHandler/INSTANCE)
        (n/pp->last pp
                    "wsh"
                    (wsh<>
                      (WebSocketClientHandshakerFactory/newHandshaker
                        uri WebSocketVersion/V13 nil true (DefaultHttpHeaders.))))
        (n/pp->last pp "ws-agg" h1/websock-adder)
        (n/pp->last pp "ws-user" (wsock-hdlr))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-inizor<>
  ^ChannelHandler
  [args]
  (proxy [ChannelInizer][]
    (onInitChannel [ch pp]
      (n/pp->last pp "user-func" nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn web-inizor<>
  ^ChannelHandler
  [args]
  (proxy [ChannelInizer][]
    (onInitChannel [ch pp]
      (h1/h1-pipeline pp args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn web-ssl-inizor<>
  "Detects and negotiate http or http 2."
  ^ChannelHandler
  [keyfile passwd {:keys [h2-frames?] :as args}]
  (letfn
    [(ssl-negotiator []
       (proxy [APNHttpXHandler][]
         (cfgH1 [pp]
           (h1/h1-pipeline pp args))
         (cfgH2 [pp]
           (if h2-frames?
             (h2/h2-pipeline pp args)
             (h2/hx-pipeline pp args)))))]
    (proxy [ChannelInizer][]
      (onInitChannel [ch pp]
        (n/server-ssl?? pp keyfile passwd args)
        (n/pp->last pp "neg" (ssl-negotiator))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

