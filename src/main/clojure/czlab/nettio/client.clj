;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.nettio.client

  "Http client using netty."

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.niou.util :as ct]
            [czlab.niou.core :as cc]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.nettio.core :as n]
            [czlab.nettio.http :as h1]
            [czlab.nettio.iniz :as iz]
            [czlab.nettio.http2 :as h2])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketClientCompressionHandler]
           [io.netty.handler.ssl.util InsecureTrustManagerFactory]
           [czlab.nettio.iniz WSInizor H2Inizor H1Inizor]
           [czlab.niou.core Http1xMsg Http2xMsg WsockMsg]
           [czlab.niou Headers]
           [java.util ArrayList List]
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
            DefaultHttp2Connection
            DefaultHttp2Headers
            DefaultHttp2HeadersFrame
            DefaultHttp2DataFrame
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
            H2Handler
            DuplexHandler
            H1DataFactory
            InboundHandler]
           [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- boot!

  [args]

  (c/info "client bootstrap ctor().")
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
                              (i/file-repo)))
        bs (Bootstrap.)
        [^EventLoopGroup g z] (n/group+channel threads :tcpc)]
    (n/config-disk-files true temp-dir)
    (c/info "setting client options...")
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

  "Set up connection for various protocols - h1, wsock, h2."
  [module host port hint]

  (letfn
    [(connect [bs ssl?]
       (let [port' (if-not (neg? port)
                     port (if ssl? 443 80))
             _ (c/debug "connecting to: %s@%s." host port')
             ^ChannelFuture
             cf (some-> (.connect ^Bootstrap bs
                                  (InetSocketAddress. (str host)
                                                      (int port'))) .sync)
             rc (try (or (and (.isSuccess cf)
                              (.channel cf))
                         (.cause cf))
                     (catch Throwable _ _))]
         ;add generic key to hold response promises
         (if (c/!is? Channel rc)
           {:error rc}
           (try (n/akey+ rc iz/rsp-key (ArrayList.))
                {:channel rc :host host :port port'}
                (finally (c/debug "client connected: %s@%s." host port'))))))
     (cconn<> [bs {:keys [^Channel channel host port ssl?]}]
       (reify cc/HClient
         (remote-port [_] port)
         (remote-host [_] host)
         (module [_] module)
         (is-ssl? [_] ssl?)
         (is-open? [_] (.isOpen channel))
         (channel [_] channel)
         (write-msg [_ msg] (cc/write-msg _ msg nil))
         (write-msg [_ msg args]
           (c/condp?? instance? hint
             H2Inizor (cc/h2-send module _ msg args)
             H1Inizor (cc/h1-send module _ msg args)
             WSInizor (cc/ws-send module _ msg args)))
         c/Finzable
         (finz [_] (n/nobs! bs channel))))
     (h1c-finz [bs info]
       ;prepare for shutdown upon CLOSE
       (n/cf-cb (.closeFuture ^Channel (:channel info))
                #(do %1 (n/nobs! bs nil)
                        (c/debug "client shutdown: netty client."))) info)
     (ret-conn [bs ssl? rcp ms info]
       ;if http just return the channel, else wait for handshake
       (let [rc (if (c/is? H1Inizor hint)
                  (:channel info) (deref rcp ms nil))]
           (if (c/!is? Channel rc)
             rc
             (n/akey+ rc n/cc-key (cconn<> bs
                                           (assoc info
                                                  :ssl? ssl?
                                                  :channel rc))))))]
    (let [{:keys [uri sync-wait server-cert]
           :as args'
           :or {sync-wait 5000}} (:args hint)
          ssl? (c/hgl? server-cert)
          ;return this back to caller
          rcp (promise)
          [^Bootstrap bs args]
          (boot! (assoc args'
                        :protocol
                        (if (c/is? H2Inizor hint) "2" "1.1")))
          args (if (c/!is? WSInizor hint)
                 args
                 (assoc args
                        :uri2
                        (.toURI (URL. (c/fmt "%s://%s:%d%s"
                                             (if ssl? "https" "http") host port uri)))))
          _ (.handler bs
                      (if (c/!is? WSInizor hint)
                        (iz/webc-inizor<> rcp args)
                        (iz/websock-inizor<> rcp args)))
          {:keys [channel error] :as rc} (connect bs ssl?)]
      (if (nil? channel)
        error
        (->> (h1c-finz bs rc)
             (ret-conn bs ssl? rcp sync-wait))))))

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
(extend-protocol cc/HClientModule

  NettyClientModule

  (h2-conn [_ host port args]
    (hx-conn _ host port (H2Inizor. args)))

  (h1-conn [_ host port args]
    (hx-conn _ host port (H1Inizor. args)))

  (ws-conn [_ host port args]
    (hx-conn _ host port (WSInizor. args)))

  (ws-send
    ([_ conn msg] (cc/ws-send _ conn msg nil))
    ([_ conn msg args]
     (let [ch (cc/channel conn)]
       (u/assert-BadArg (c/is? WsockMsg msg) "not wsmsg.")
       (n/write-msg ch msg))))

  (h2-send
    ([_ conn msg] (cc/h2-send _ conn msg nil))
    ([_ conn msg args]
     (condp instance? msg
       Http2xMsg
       (let [ch (cc/channel conn)]
         (c/do-with [out (promise)]
           (let [pl (n/akey?? ch iz/rsp-key)]
             (.add ^List pl out)
             (n/write-msg ch msg))))
       Http1xMsg
       (let [{:keys [^Headers headers]} msg]
         (.add headers (h2/h2xhdr* SCHEME)
               (if (cc/is-ssl? conn) "https" "http"))
         (cc/h1-send _ conn msg args))
       (u/throw-BadArg "Invalid msg type %s" msg))))

  (h1-send
    ([_ conn msg] (cc/h1-send _ conn msg nil))
    ([_ conn msg args]
     (let [{:keys [request-method
                   uri2 body headers]} msg
           {:keys [keep-alive?
                   encoding override]
            :or {keep-alive? true}} args
          ^Channel ch (cc/channel conn)
          [_ target] (cc/encoded-path uri2)
          ssl? (some? (n/get-ssl?? ch))
          body (n/bbuf?? body ch encoding)
          host (cc/remote-host conn)
          port (cc/remote-port conn)
          req (if-not (or (nil? body)
                          (c/is? ByteBuf body))
                (http-req<> request-method target)
                (http-req<+> request-method target body))
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
      (n/add-headers req (h1/std->headers headers))
      (c/if-some+ [mo (c/stror override "")]
        (n/set-header req "X-HTTP-Method-Override" mo))
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
      (c/debug (str "about to flush out req (headers), "
                    "isKeepAlive= %s, content-length= %s") keep-alive? clen)
      (c/do-with [out (promise)]
        (let [pl (n/akey?? ch iz/rsp-key)
              _ (.add ^List pl out)
              cf (n/write-msg* ch req)
              cf (condp instance? body
                   File
                   (n/write-msg* ch
                                 (HttpChunkedInput.
                                   (ChunkedFile. ^File body)))
                   InputStream
                   (n/write-msg* ch
                                 (HttpChunkedInput.
                                   (ChunkedStream. ^InputStream body))) cf)] (.flush ch)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn web-client-module<>

  ([] (web-client-module<> nil))

  ([args] (c/object<> NettyClientModule args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

