;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "Http client using netty."
    :author "Kenneth Leung"}

  czlab.nettio.client

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.niou.util :as ct]
            [czlab.niou.core :as cc]
            [czlab.niou.module :refer [web-client-module<>]]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.nettio.core :as n]
            [czlab.nettio.http :as h1]
            [czlab.nettio.http2 :as h2]
            [czlab.nettio.iniz :as iz])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketClientCompressionHandler]
           [io.netty.handler.ssl.util InsecureTrustManagerFactory]
           [czlab.nettio.iniz WSInizor H2Inizor H1Inizor]
           [czlab.niou.core WsockMsg]
           [io.netty.channel.socket.nio NioSocketChannel]
           [io.netty.handler.codec.http.websocketx
            ContinuationWebSocketFrame
            BinaryWebSocketFrame
            TextWebSocketFrame
            CloseWebSocketFrame
            PongWebSocketFrame
            WebSocketFrame
            WebSocketVersion
            WebSocketClientHandshaker
            WebSocketClientHandshakerFactory]
           [io.netty.channel.nio NioEventLoopGroup]
           [clojure.lang IDeref APersistentVector]
           [java.security.cert X509Certificate CertificateFactory]
           [java.util.concurrent TimeUnit]
           [io.netty.handler.codec.http2
            HttpConversionUtil$ExtensionHeaderNames
            HttpConversionUtil
            Http2SecurityUtil
            Http2FrameAdapter
            Http2Settings
            Http2ConnectionHandler
            HttpToHttp2ConnectionHandlerBuilder
            AbstractHttp2ConnectionHandlerBuilder]
           [io.netty.handler.ssl
            ApplicationProtocolNames
            OpenSsl
            SslContext
            SslContextBuilder
            SslProvider
            SupportedCipherSuiteFilter
            ApplicationProtocolConfig
            ApplicationProtocolConfig$Protocol
            ApplicationProtocolNegotiationHandler
            ApplicationProtocolConfig$SelectorFailureBehavior
            ApplicationProtocolConfig$SelectedListenerFailureBehavior]
           [io.netty.util ReferenceCountUtil AttributeKey]
           [java.io InputStream File IOException]
           [io.netty.buffer ByteBuf Unpooled]
           [java.net InetSocketAddress URI URL]
           [io.netty.bootstrap Bootstrap]
           [io.netty.handler.stream
            ChunkedFile
            ChunkedStream
            ChunkedWriteHandler]
           [java.nio.charset Charset]
           [io.netty.handler.codec.http
            HttpHeaderValues
            HttpClientCodec
            HttpHeaderNames
            HttpHeaders
            HttpUtil
            HttpMethod
            HttpRequest
            HttpVersion
            FullHttpResponse
            FullHttpRequest
            HttpChunkedInput
            DefaultHttpRequest
            DefaultHttpHeaders
            HttpObjectAggregator
            DefaultFullHttpRequest]
           [io.netty.channel
            ChannelPipeline
            ChannelHandler
            EventLoopGroup
            Channel
            ChannelOption
            ChannelPromise
            ChannelFuture
            ChannelInitializer
            ChannelHandlerContext]
           [czlab.nettio
            ChannelInizer
            DuplexHandler
            H1DataFactory
            InboundHandler]
           [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^AttributeKey h2s-key  (n/akey<> :h2settings-promise))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- boot!
  [args]
  (l/info "client bootstrap ctor().")
  (let [{:as ARGS
         :keys [max-msg-size max-mem-size
                protocol temp-dir server-cert
                scheme threads rcv-buf options]}
        (merge {:max-mem-size i/*membuf-limit*
                :rcv-buf (* 2 c/MegaBytes)
                :threads 0
                :max-msg-size Integer/MAX_VALUE
                :max-frame-size (* 32 c/MegaBytes)} args)
        threads (if (pos? threads) threads 0)
        temp-dir (u/fpath (or temp-dir
                              i/*file-repo*))
        bs (Bootstrap.)
        [^EventLoopGroup g z] (n/group+channel threads :tcpc)]
    (n/config-disk-files true temp-dir)
    (l/info "setting client options...")
    (doseq [[k v] (partition 2 (or options
                                   [:SO_KEEPALIVE true
                                    :TCP_NODELAY true
                                    :SO_RCVBUF (int rcv-buf)]))]
      (.option bs (n/chopt* k) v))
    ;;assign generic attributes for all channels
    (.attr bs n/chcfg-key ARGS)
    [(doto bs (.channel z) (.group g)) ARGS]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- hx-conn
  [module host port hint]
  (letfn
    [(connect [^Bootstrap bs ssl?]
       (let [port' (if (neg? port)
                     (if ssl? 443 80) port)
             _ (l/debug "connecting to: %s@%s." host port')
             ^ChannelFuture
             cf (some-> (.connect bs
                                  (InetSocketAddress. (str host)
                                                      (int port'))) .sync)]
         (u/assert-IOE (some? cf) "client connect failed.")
         (let [ch (.channel cf)]
           (u/assert-IOE (and ch
                              (.isSuccess cf)) (u/emsg (.cause cf)))
           (l/debug "client connected: %s@%s." host port')
           {:channel ch :host host :port port'})))
     (cconn<> [bs ch info]
       (reify cc/ClientConnect
         (cc-remote-port [_] (:port info))
         (cc-remote-host [_] (:host info))
         (cc-module [_] module)
         (cc-is-open? [_]
           (and ch (.isOpen ^Channel ch)))
         (cc-channel [_] ch)
         (cc-write [_ msg]
           (c/condp?? instance? hint
             H2Inizor (cc/hc-h2-send module _ msg)
             H1Inizor (cc/hc-h1-send module _ msg)
             WSInizor (cc/hc-ws-send module _ msg)))
         (cc-finz [_]
           (if (c/is? WSInizor hint)
             (c/try! (some-> (and ch (.isOpen ^Channel ch) ch)
                             (n/write-msg {:is-close? true}))))
           (n/nobs! bs ch))))
     (h1c-finz [bs info]
       (n/cf-cb (.closeFuture ^Channel (:channel info))
                (c/fn_1 (n/nobs! bs nil)
                        (l/debug "client shutdown: netty client.")))
       info)
     (ret-conn [bs rcp info]
       (reify cc/ClientConnectPromise
         (cc-sync-get-connect [_]
           (cc/cc-sync-get-connect _ 5000))
         (cc-sync-get-connect [_ ms]
           (let [r (deref rcp ms nil)]
             ;(l/debug "r === %s" r)
             (cond (c/!is? Channel r)
                   r
                   (c/is? H2Inizor hint)
                   (try (let [^ChannelPromise
                              pm (n/akey?? r h2s-key)]
                          (l/debug "client waits %s[ms] for h2-settings." ms)
                          (u/assert-ISE
                            (.awaitUninterruptibly pm
                                                   ms TimeUnit/MILLISECONDS)
                            "Time out waiting for h2-settings.")
                          (if-not (.isSuccess pm)
                            (.cause pm)
                            (n/akey+ r n/cc-key (cconn<> bs r info))))
                        (catch Throwable e e))
                   :else
                   (n/akey+ r n/cc-key (cconn<> bs r info)))))))]
    (let [{:keys [uri
                  sync-wait
                  server-cert] :as args'
           :or {sync-wait 5000}} (:args hint)
          ws? (c/is? WSInizor hint)
          [^Bootstrap bs args]
          (boot! (assoc args'
                        :protocol
                        (if (c/is? H2Inizor hint) "2" "1.1")))
          scheme (if (c/hgl? server-cert)
                   (if ws? "wss" "https") (if ws? "ws" "http"))
          uri2 (c/fmt "%s://%s:%d%s" scheme host port uri)
          ;return this back to caller
          rcp (promise)
          args (assoc args :uri2 uri2)]
      (.handler bs
                (if-not (c/is? WSInizor hint)
                  (iz/webc-inizor<> rcp args)
                  (iz/websock-inizor<> rcp args)))
      (cc/cc-sync-get-connect
        (->> (c/hgl? server-cert)
             (connect bs)
             (h1c-finz bs)
             (ret-conn bs rcp)) sync-wait))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- http-req<>
  ^HttpRequest [mt uri]
  (DefaultHttpRequest. HttpVersion/HTTP_1_1
                       (HttpMethod/valueOf (c/ucase (name mt))) (str uri)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- http-req<+>
  {:tag FullHttpRequest}
  ([mt uri] (http-req<+> mt uri nil))
  ([mt uri ^ByteBuf body]
   (let [op (HttpMethod/valueOf (c/ucase (name mt)))]
     (if (nil? body)
       (c/do-with [x (DefaultFullHttpRequest.
                       HttpVersion/HTTP_1_1 op (str uri))]
         (HttpUtil/setContentLength x 0))
       (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 op (str uri) body)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- http-post<+>
  ^FullHttpRequest
  [^String uri ^ByteBuf body] (http-req<+> :post uri body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- http-post<>
  ^HttpRequest [uri] (http-req<> :post uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- http-get<>
  ^FullHttpRequest [uri] (http-req<+> :get uri nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyClientModule [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod web-client-module<>
  :czlab.nettio.client/netty
  [args]
  (c/object<> NettyClientModule args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpClientModule

  NettyClientModule

  (hc-h2-conn [_ host port args]
    (hx-conn _ host port (H2Inizor. args)))

  (hc-h1-conn [_ host port args]
    (hx-conn _ host port (H1Inizor. args)))

  (hc-ws-conn [_ host port args]
    (hx-conn _ host port (WSInizor. args)))

  (hc-ws-send [_ conn msg]
    (let [ch (cc/cc-channel conn)]
      (u/assert-BadArg (c/is? WsockMsg msg) "not wsmsg.")
      (n/write-msg ch msg)))

  (hc-h2-send [_ conn msg] );op uri data args

  (hc-h1-send [_ conn args];op uri data args
    (let [{:keys [request-method
                  encoding
                  uri host
                  body headers
                  keep-alive? override]} args
          ^Channel ch (cc/cc-channel conn)
          body (n/bbuf?? body ch encoding)
          ^URI uri (or (c/cast? URI uri)
                       (URI. ^String uri))
          path (.getPath uri)
          qy (.getQuery uri)
          uriStr (if (c/hgl? qy)
                   (str path "?" qy) path)
          req (if-not (or (nil? body)
                          (c/is? ByteBuf body))
                (http-req<> request-method uriStr)
                (http-req<+> request-method uriStr body))
          clen (cond (c/is? ByteBuf body)
                     (.readableBytes ^ByteBuf body)
                     (c/is? File body)
                     (.length ^File body)
                     (c/is? InputStream body)
                     -1
                     (nil? body)
                     0
                     :else
                     (u/throw-IOE "Bad type %s." (class body)))]
      (c/if-some+ [mo (c/stror override "")]
        (n/set-header req "X-HTTP-Method-Override" mo))
      (doseq [[k v] (seq headers)
              :let [kw (name k)]]
        (if (seq? v)
          (doseq [vv (seq v)]
            (n/add-header req kw vv))
          (n/set-header req kw v)))
      (n/set-header req (n/h1hdr* HOST) host)
      (n/set-header req
                    (n/h1hdr* CONNECTION)
                    (if-not keep-alive?
                      (n/h1hdv* CLOSE)
                      (n/h1hdv* KEEP_ALIVE)))
      (if (zero? clen)
        (HttpUtil/setContentLength req 0)
        (do (if-not (c/is? FullHttpRequest req)
              (HttpUtil/setTransferEncodingChunked req true))
            (if-not (n/has-header? req "content-type")
              (n/set-header req
                            (n/h1hdr* CONTENT_TYPE)
                            "application/octet-stream"))
            (if (c/spos? clen)
              (HttpUtil/setContentLength req clen))))
      (l/debug (str "about to flush out req (headers), "
                    "isKeepAlive= %s, content-length= %s") keep-alive? clen)
      (c/do-with [out (n/akey+ ch iz/rsp-key (promise))]
        (let [cf (n/write-msg* ch req)
              cf (condp instance? body
                   File
                   (n/write-msg* ch
                                 (HttpChunkedInput.
                                   (ChunkedFile. ^File body)))
                   InputStream
                   (n/write-msg* ch
                                 (HttpChunkedInput.
                                   (ChunkedStream. ^InputStream body))) cf)] (.flush ch))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

