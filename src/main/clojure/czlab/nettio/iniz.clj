;; Copyright © 2013-2020, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.nettio.iniz

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.io :as i]
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
            Http2Settings
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
            InboundH2ToH1
            InboundHandler
            DuplexHandler
            APNHttp2Handler
            APNHttpXHandler
            PipelineConfigurator]
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
           [java.net URI]
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
            WebSocketClientHandshaker
            WebSocketFrameAggregator
            WebSocketClientHandshakerFactory]
           [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketClientCompressionHandler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(defrecord WSInizor [args])
(defrecord H2Inizor [args])
(defrecord H1Inizor [args])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce ^AttributeKey rsp-key  (n/akey<> :client-rsp-results))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def-
  ^{:tag ChannelHandler}
  client-hdlr
  (proxy [InboundHandler][]
    (onRead [ctx ch msg]
      (let [pl (n/akey?? ctx rsp-key)
            p (.remove ^List pl 0)]
        (some-> p (deliver msg))))
    (onError [ctx err]
      (let [pl (n/akey?? ctx rsp-key)
            p (.remove ^List pl 0)]
        (some-> p (deliver err))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- webc-ssl-inizor<>

  "Initialize a web client SSL pipeline."
  [rcp server-cert
   {:keys [h2-frames?
           protocol
           max-mem-size
           max-frame-size] :as args}]

  (letfn
    [(h2hdlr []
       (let [co (DefaultHttp2Connection. false)
             f (proxy [InboundH2ToH1]
                      [co (int max-frame-size) false false]
                 (onSettings [ctx ch msg]
                   (deliver rcp ch)))]
         (.build (-> (HttpToHttp2ConnectionHandlerBuilder.)
                     (.connection co)
                     ;(.server false) MUST NOT CALL THIS!
                     (.frameListener f)
                     (.frameLogger (Http2FrameLogger. LogLevel/INFO))))))]
    (proxy [PipelineConfigurator][]
      (onActive [ctx]
        (if-not (.equals "2" protocol)
          (deliver rcp (n/ch?? ctx))))
      (onError [_ e]
        (deliver rcp e))
      (onInitChannel [pp]
        (n/client-ssl?? pp server-cert args)
        (cond
          (and h2-frames?
               (n/isH2? protocol))
          (n/pp->last
            pp "cli-h2f-neg"
            (proxy [APNHttp2Handler][]
              (cfgH2 [pp]
                (n/pp->last
                  pp "cli-h2f"
                  (h2/h2-handler<> rcp max-mem-size))
                (n/pp->last pp n/user-cb client-hdlr))))
          (n/isH2? protocol)
          (n/pp->last
            pp "cli-h2x-neg"
            (proxy [APNHttp2Handler][]
              (cfgH2 [pp]
                (n/pp->last pp "cli-h2x" (h2hdlr))
                (n/pp->last pp "h1" h1/h1-simple<>)
                (n/pp->last pp n/user-cb client-hdlr))))
          :else
          (do (n/pp->last pp "1" (HttpClientCodec.))
              (n/pp->last pp "2" h1/h1-simple<>)
              (n/pp->last pp "3" (ChunkedWriteHandler.))
              (n/pp->last pp n/user-cb client-hdlr)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn webc-inizor<>

  "Initialize pipeline for a http client."
  {:tag PipelineConfigurator
   :arglists '([rcp args])}
  [rcp {:keys [server-cert] :as args}]

  (if (c/hgl? server-cert)
    (webc-ssl-inizor<> rcp server-cert args)
    (proxy [PipelineConfigurator][]
      (onActive [ctx]
        (deliver rcp (n/ch?? ctx)))
      (onError [_ e]
        (deliver rcp e))
      (onInitChannel [pp]
        (n/pp->last pp "1" (HttpClientCodec.))
        (n/pp->last pp "2" h1/h1-simple<>)
        (n/pp->last pp "3" (ChunkedWriteHandler.))
        (n/pp->last pp n/user-cb client-hdlr)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- hshaker

  ^WebSocketClientHandshaker [^URI uri]

  (c/debug "wsc handshake uri= %s." (.toString uri))
  (WebSocketClientHandshakerFactory/newHandshaker
    uri WebSocketVersion/V13 nil true (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn websock-inizor<>

  "Initialize pipeline for a websock client."
  {:tag PipelineConfigurator
   :arglists '([rcp args])}
  [rcp
   {:keys [uri2 user-cb
           server-cert max-frame-size] :as args}]

  (letfn
    [(wsh<> []
       (let [hs (hshaker uri2)]
         (proxy [InboundHandler][true]
           (onActive [ctx]
             (c/debug "about to start wsc handshake...")
             (.handshake hs (n/ch?? ctx)))
           (onRead [ctx ch msg]
             (cond
               (not (.isHandshakeComplete hs))
               (try
                 (->> (c/cast? FullHttpResponse msg)
                      (.finishHandshake hs ch))
                 (deliver rcp ch)
                 (n/dbg-pipeline (n/cpipe?? ch))
                 (catch Exception e (deliver rcp e)))

               (c/is? FullHttpResponse msg)
               (u/throw-ISE
                 "Unexpected Response (rc=%s)."
                 (.status ^FullHttpResponse msg))

               (c/is? CloseWebSocketFrame msg)
               (do (n/close! ctx)
                   (c/debug "received close frame."))

               :else
               (n/fire-msg ctx (n/ref-add msg)))))))]
    (proxy [PipelineConfigurator][]
      (onInitChannel [pp]
        (n/client-ssl?? pp server-cert args)
        (n/pp->last pp "1" (HttpClientCodec.))
        (n/pp->last pp "2"
                    (HttpObjectAggregator. 96000))
        (n/pp->last pp "3" WebSocketClientCompressionHandler/INSTANCE)
        (n/pp->last pp "4" (wsh<>))
        (n/pp->last pp "5"
                    (WebSocketFrameAggregator. max-frame-size))
        (n/pp->last pp "6" h1/ws-monolith<>)
        (n/pp->last pp n/user-cb
                    (proxy [InboundHandler][]
                      (onRead [ctx _ msg] (user-cb msg))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-inizor<>

  "Initialize pipeline for UDP."
  {:tag PipelineConfigurator
   :arglists '([args])}
  [{:keys [user-cb] :as args}]

  (proxy [PipelineConfigurator][]
    (onInitChannel [pp]
      (n/pp->last pp n/user-cb (n/app-handler user-cb)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- web-ssl-inizor<>

  "Detects and negotiate http1 or http2."
  [keyfile
   {:keys [passwd h2-frames?] :as args}]

  (c/debug "server-ssl, h2? = %s" h2-frames?)
  (letfn
    [(ssl-negotiator []
       (proxy [APNHttpXHandler][]
         (cfgH1 [pp]
           (h1/h1-pipeline pp args))
         (cfgH2 [pp]
           (if h2-frames?
             (h2/h2-pipeline pp args)
             (h2/hx-pipeline pp args)))))]
    (proxy [PipelineConfigurator][]
      (onInitChannel [pp]
        (n/server-ssl?? pp keyfile passwd args)
        (n/pp->last pp "svr-neg" (ssl-negotiator))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn web-inizor<>

  "Pipeline initializer for http and ssl."
  {:tag PipelineConfigurator
   :arglists '([args])}
  [{:keys [server-key] :as args}]

  (if (c/nichts? server-key)
    (proxy [PipelineConfigurator][]
      (onInitChannel [pp]
        (h1/h1-pipeline pp args)))
    (web-ssl-inizor<> server-key args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

