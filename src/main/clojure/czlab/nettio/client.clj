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
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.nettio.core :as n]
            [czlab.nettio.http :as h1]
            [czlab.nettio.http2 :as h2]
            [czlab.nettio.inizor :as iz])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketClientCompressionHandler]
           [io.netty.handler.ssl.util InsecureTrustManagerFactory]
           [czlab.nettio.inizor WSInizor H2Inizor H1Inizor]
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
(c/def- ^AttributeKey rsp-key  (n/akey<> :rsp-result))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- bootstrap!
  [args]
  (l/info "client bootstrap ctor().")
  (let [{:as ARGS
         :keys [max-msg-size max-mem-size
                protocol temp-dir server-cert
                scheme threads rcv-buf options]}
        (merge {:max-mem-size i/*membuf-limit*
                :rcv-buf (* 2 c/MegaBytes)
                :threads 0
                :max-msg-size Integer/MAX_VALUE} args)
        temp-dir (u/fpath (or temp-dir
                              i/*tempfile-repo*))
        bs (Bootstrap.)
        [^EventLoopGroup g
         ^ChannelHandler z] (n/group+channel threads :tcpc)]
    (n/config-disk-files true temp-dir)
    (l/info "setting client options...")
    (doseq [[k v] (partition 2
                             (or options
                                 [:SO_KEEPALIVE true
                                  :TCP_NODELAY true
                                  :SO_RCVBUF (int rcv-buf)]))]
      (.option bs (n/chopt* k) v))
    ;;assign generic attributes for all channels
    (.attr bs n/chcfg-key ARGS)
    (.attr bs
           n/dfac-key
           (H1DataFactory.
             (int max-mem-size)))
    [(doto bs (.channel z) (.group g)) ARGS]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- hx-conn
  [module host port args hint]
  (letfn
    [(connect [^Bootstrap bs ssl?]
       (let [port' (if (neg? port)
                     (if ssl? 443 80) port)
             _ (l/debug "connecting to: %s@%s." host port')
             ^ChannelFuture
             cf (some->> (InetSocketAddress. (str host)
                                             (int port'))
                         (.connect bs) .sync)]
         (u/assert-IOE (some? cf) "connect failed.")
         (c/do-with [ch (.channel cf)]
           (u/assert-IOE (and ch
                              (.isSuccess cf)) (.cause cf))
           (l/debug "connected: %s@%s." host port'))))
     (cconn<> [bs ^Channel ch]
       (reify cc/ClientConnect
         (cc-module [_] module)
         (cc-channel [_] ch)
         (cc-remote-port [_] port)
         (cc-remote-host [_] host)
         (cc-ws-write [_ msg]
           (cc/hc-ws-send module _ msg))
         (cc-finz [_]
           (if (c/is? WSInizor hint)
             (c/try! (if (.isOpen ch)
                       (n/write-msg ch
                                    (CloseWebSocketFrame.)))))
           (n/nobs! bs ch))))
     (h1c-finz [bs ^Channel ch]
       (let [f (n/get-akey ch n/dfac-key)]
         (n/cf-cb (.closeFuture ch)
                  (c/fn_1 (.cleanAllHttpData ^H1DataFactory f)
                          (n/nobs! bs nil)
                          (l/debug "shutdown: netty h1-client.")))))
     (ret-conn [bs rcp]
       (reify cc/ClientConnectPromise
         (cc-sync-get-connect [_]
           (cc/cc-sync-get-connect _ 5000))
         (cc-sync-get-connect [_ ms]
           (let [r (deref rcp ms nil)]
             (cond (not (c/is? Channel r))
                   r
                   (c/is? H2Inizor hint)
                   (try (let [^ChannelPromise
                              pm (n/get-akey r h2s-key)]
                          (l/debug "client waits %s[ms] for h2-settings." ms)
                          (u/assert-ISE
                            (.awaitUninterruptibly pm
                                                   ms TimeUnit/MILLISECONDS)
                            "Time out waiting for h2-settings.")
                          (if-not
                            (.isSuccess pm)
                            (.cause pm) (cconn<> bs r)))
                        (catch Throwable e e))
                   :else
                   (let [x (cconn<> bs r)]
                     (if (c/is? WSInizor hint)
                       (n/set-akey r n/cc-key x)) x))))))]
    (let [[^Bootstrap bs args]
          (bootstrap! (assoc args
                             :protocol
                             (if (c/is? H2Inizor hint) "2" "1.1")))
          rcp (promise) ;return this back to caller
          {:keys [server-cert]} args]
      (.handler bs
                (cond
                  (c/is? WSInizor hint)
                  (iz/websock-inizor<> rcp server-cert args)
                  :else
                  (if-not server-cert
                    (iz/webc-inizor<> rcp args)
                    (iz/webc-ssl-inizor<> rcp server-cert args))))
      (h1c-finz bs (connect bs (c/hgl? server-cert)))
      (ret-conn bs rcp))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-req<>
  ^HttpRequest [^HttpMethod mt ^String uri]
  (DefaultHttpRequest. HttpVersion/HTTP_1_1 mt uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-req<+>
  {:tag FullHttpRequest}
  ([mt uri] (http-req<+> mt uri nil))
  ([^HttpMethod mt ^String uri ^ByteBuf body]
   (if (nil? body)
     (c/do-with [x (DefaultFullHttpRequest.
                     HttpVersion/HTTP_1_1 mt uri)]
       (HttpUtil/setContentLength x 0))
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-post<+>
  ^FullHttpRequest
  [^String uri ^ByteBuf body] (http-req<+> HttpMethod/POST uri body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-post<>
  ^HttpRequest [uri] (http-req<> HttpMethod/POST uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-get<>
  ^FullHttpRequest [uri] (http-req<+> HttpMethod/GET uri nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyModule [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-module<>
  []
  (c/object<> NettyModule {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpClientModule
  NettyModule
  (hc-send-http [_ conn op uri data args]
    (let [mt (HttpMethod/valueOf (c/ucase (name op)))
          ^URI uri (or (c/cast? URI uri)
                       (URI. uri))
          {:keys [encoding headers
                  keep-alive?
                  protocol override]} args
          ^Channel ch (cc/cc-channel conn)
          body (n/bbuf?? data ch encoding)
          path (.getPath uri)
          qy (.getQuery uri)
          uriStr (if (c/hgl? qy)
                   (str path "?" qy) path)
          req (if-not (or (nil? body)
                          (c/is? ByteBuf body))
                (http-req<> mt uriStr)
                (http-req<+> mt uriStr body))
          clen (cond (c/is? ByteBuf body) (.readableBytes ^ByteBuf body)
                     (c/is? File body) (.length ^File body)
                     (c/is? InputStream body) -1
                     (nil? body) 0
                     :else (u/throw-IOE "Bad type %s." (class body)))]
      (doseq [[k v] (seq headers)
              :let [kw (name k)]]
        (if (seq? v)
          (doseq [vv (seq v)]
            (n/add-header req kw vv))
          (n/set-header req kw v)))
      (n/set-header req (n/h1hdr* HOST) (:host args))
      (if (.equals "2" protocol)
        (n/set-header req
                      (.text HttpConversionUtil$ExtensionHeaderNames/SCHEME)
                      (.getScheme uri))
        (n/set-header req
                      (n/h1hdr* CONNECTION)
                      (if-not keep-alive?
                        (n/h1hdv* CLOSE)
                        (n/h1hdv* KEEP_ALIVE))))
      (c/if-some+ [mo (c/stror override "")]
        (n/set-header req "X-HTTP-Method-Override" mo))
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
      (c/do-with [out (n/set-akey ch rsp-key (promise))]
        (let [cf (.write ch req)
              cf (condp instance? body
                   File
                   (->> (ChunkedFile. ^File body)
                        HttpChunkedInput. (.write ch))
                   InputStream
                   (->> (ChunkedStream.
                          ^InputStream body)
                        HttpChunkedInput. (.write ch)) cf)] (.flush ch)))))

  (hc-ws-send [_ conn msg]
    (let [^Channel ch (cc/cc-channel conn)]
    (some->> (cond
               (c/is? WebSocketFrame msg)
               msg
               (string? msg)
               (TextWebSocketFrame. ^String msg)
               (bytes? msg)
               (some-> (.alloc ch)
                       (.directBuffer (int 4096))
                       (.writeBytes ^bytes msg)
                       (BinaryWebSocketFrame. ))) (.writeAndFlush ch))))

  (hc-h2-conn [_ host port args]
    (hx-conn _ host port args (H2Inizor.)))

  (hc-h1-conn [_ host port args]
    (hx-conn _ host port args (H1Inizor.)))

  (hc-ws-conn [_ host port user args]
    (hx-conn _ host port args (WSInizor. user))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

