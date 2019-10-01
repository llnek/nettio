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
            [czlab.nettio
             [msgs :as mg]
             [core :as nc]]
            [czlab.niou
             [util :as ct]
             [core :as cc]]
            [czlab.basal
             [log :as l]
             [io :as i]
             [core :as c]
             [util :as u]])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketClientCompressionHandler]
           [io.netty.handler.ssl.util InsecureTrustManagerFactory]
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
           [io.netty.channel.epoll Epoll]
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
            H2ConnBuilder
            InboundHandler]
           [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^AttributeKey h2s-key  (nc/akey<> :h2settings-promise))
(c/def- ^AttributeKey rsp-key  (nc/akey<> :rsp-result))
(c/def- ^AttributeKey cf-key  (nc/akey<> :wsock-future))
(c/def- ^AttributeKey cc-key  (nc/akey<> :wsock-client))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;Aggregates all chunks into a full message.
(c/def- ^{:tag ChannelHandler}
  msg-agg
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg] (mg/agg-h1-read ctx msg false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- nobs!
  [^Bootstrap bs ^Channel ch]
  (c/try! (if (and ch (.isOpen ch)) (.close ch)))
  (c/try! (.. bs config group shutdownGracefully)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cconn<>
  ([module bs ^Channel ch host port]
   (cconn<> module bs ch host port nil))
  ([module bs ^Channel ch host port hint]
   (reify cc/ClientConnect
     (cc-ws-write [_ msg] (cc/hc-ws-send module _ msg))
     (cc-module [_] module)
     (cc-channel [_] ch)
     (cc-remote-port [_] port)
     (cc-remote-host [_] host)
     (cc-finz [_]
       (if (:wsock? hint)
         (c/try! (if (.isOpen ch)
                   (.writeAndFlush ch (CloseWebSocketFrame.)))))
       (nobs! bs ch)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- conv-certs
  "Convert Certs" [arg]
  (let [[d? inp] (i/input-stream?? arg)]
    (try (-> (CertificateFactory/getInstance "X.509")
             (.generateCertificates ^InputStream inp) vec)
         (finally (if d? (i/klose inp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-ctx
  ^SslContextBuilder [scert]
  (let [ctx (SslContextBuilder/forClient)]
    (if (= "*" scert)
      (.trustManager ctx InsecureTrustManagerFactory/INSTANCE)
      (let [#^"[Ljava.security.cert.X509Certificate;"
            cs (->> (conv-certs (io/as-url scert))
                    (c/vargs X509Certificate))] (.trustManager ctx cs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-ssl??
  ^SslContext [scert scheme h2?]
  (when (and (c/hgl? scert)
             (not= "http" scheme))
    (let [ctx (build-ctx scert)]
      (if-not h2?
        (.build ctx)
        (let [cfg
              (ApplicationProtocolConfig.
                ApplicationProtocolConfig$Protocol/ALPN
                ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
                ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
                (doto (java.util.ArrayList.)
                  (.add ApplicationProtocolNames/HTTP_2)))
                  ;;(.add ApplicationProtocolNames/HTTP_1_1)))
              ^SslProvider
              p  (if (and true (OpenSsl/isAlpnSupported))
                   SslProvider/OPENSSL SslProvider/JDK)
              ctx
              (-> (.ciphers ctx
                            Http2SecurityUtil/CIPHERS
                            SupportedCipherSuiteFilter/INSTANCE)
                  (.applicationProtocolConfig cfg))]
          (.build (.sslProvider ctx p)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- connect
  ^Channel [^Bootstrap bs host port ctx]
  (let
    [port (if (neg? port) (if ctx 443 80) port)
     _ (l/debug "connecting to: %s@%s." host port)
     sock (InetSocketAddress. (str host)
                              (int port))
     cf (some-> (.connect bs sock) .sync)]
    (c/do-with [ch (some-> cf .channel)]
      (if (or (nil? ch)
              (nil? cf)
              (not (.isSuccess cf)))
        (u/throw-IOE (.cause cf)))
      (l/debug "connected: %s@%s." host port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^{:tag ChannelHandler}
  user-hdlr
  (proxy [InboundHandler][true]
    (readMsg [ctx msg]
      (when-some
        [p (nc/get-akey rsp-key ctx)]
        (deliver p msg)
        (nc/del-akey rsp-key ctx)))
    (exceptionCaught [ctx err]
      (try (when-some
             [p (nc/get-akey rsp-key ctx)]
             (deliver p err)
             (nc/del-akey rsp-key ctx))
           (finally (nc/close-ch ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsock-hdlr
  ^ChannelHandler [user]
  (proxy [InboundHandler][true]
    (exceptionCaught [ctx err] (nc/close-ch ctx))
    (readMsg [ctx msg] (user (nc/get-akey cc-key ctx) msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsh<>
  ^ChannelHandler
  [^WebSocketClientHandshaker handshaker cb args]
  (proxy [InboundHandler][true]
    (handlerAdded [ctx]
      (let [p (.newPromise ^ChannelHandlerContext ctx)]
        (nc/set-akey cf-key ctx p)
        (.addListener p (nc/cfop<> cb))
        (l/debug "wsc handler-added.")))
    (channelActive [ctx]
      (l/debug "wsc handshaker start hand-shake.")
      (.handshake handshaker (nc/ch?? ctx)))
    (exceptionCaught [ctx err]
      (if-some [^ChannelPromise
                f (nc/get-akey cf-key ctx)]
        (if-not (.isDone f)
          (.setFailure f ^Throwable err)))
      (nc/close-ch ctx)
      (l/warn "%s." (.getMessage ^Throwable err)))
    (readMsg [ctx msg]
      (let [ch (nc/ch?? ctx)
            ^ChannelPromise f (nc/get-akey cf-key ctx)]
        (cond
          (not (.isHandshakeComplete handshaker))
          (do (l/debug "attempt to finz the hand-shake...")
              (.finishHandshake handshaker
                                ch ^FullHttpResponse msg)
              (.setSuccess f)
              (l/debug "finz'ed the hand-shake... success!"))
          (c/is? FullHttpResponse msg)
          (do (u/throw-ISE
                "Unexpected Response (rc=%s)."
                (.status ^FullHttpResponse msg)))
          (c/is? CloseWebSocketFrame msg)
          (do (nc/close-ch ctx)
              (l/debug "received close frame."))
          :else
          (->> (nc/ref-add msg)
               (.fireChannelRead ^ChannelHandlerContext ctx)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h2pipe
  ^ChannelHandler [ctx rcp args]
  (l/debug "client:h2pipe: ssl ctx = %s." ctx)
  (proxy [ChannelInizer][]
    (onHandlerAdded [ctx]
      (deliver rcp (nc/ch?? ctx)))
    (onError [_ e]
      (deliver rcp e))
    (initChannel [c]
      (let [hh (HttpToHttp2ConnectionHandlerBuilder.)
            ssl (c/cast? SslContext ctx)
            ^Channel ch (nc/ch?? c)
            ^ChannelPipeline pp (nc/cpipe ch)
            pm (.newPromise ch)]
        (nc/set-akey h2s-key c pm)
        (doto hh
          (.server false)
          (.frameListener (mg/h20-aggregator<> pm)))
        (if ssl
          (.addLast pp
                    "ssl"
                    (.newHandler ssl (.alloc ch))))
        (.addLast pp
                  "apn"
                  (proxy [ApplicationProtocolNegotiationHandler][""]
                    (configurePipeline [cx pn]
                      (if (.equals ApplicationProtocolNames/HTTP_2 ^String pn)
                        (doto ^ChannelPipeline
                          (nc/cpipe cx)
                          (.addLast "codec" (.build hh))
                          (.addLast "cw" (ChunkedWriteHandler.))
                          (.addLast "user-cb" user-hdlr))
                        (do (nc/close-ch cx)
                            (u/throw-ISE "Unknown protocol: %s." pn))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h1pipe
  ^ChannelHandler [ctx rcp args]
  (l/debug "client:h1pipe: ssl ctx = %s." ctx)
  (proxy [ChannelInizer][]
    (onHandlerAdded [ctx]
      (deliver rcp (nc/ch?? ctx)))
    (onError [ctx exp]
      (deliver rcp exp))
    (initChannel [ch]
      (let [^ChannelPipeline pp (nc/cpipe ch)]
        (if-some
          [ssl (c/cast? SslContext ctx)]
          (.addLast pp
                    "ssl"
                    (.newHandler ssl
                                 (.alloc ^Channel ch))))
        (doto pp
          (.addLast "codec" (HttpClientCodec.))
          (.addLast "msg-agg" msg-agg)
          (.addLast "cw" (ChunkedWriteHandler.))
          (.addLast "user-cb" user-hdlr))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wspipe
  ^ChannelHandler [ctx cb user args]
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [^ChannelPipeline pp (nc/cpipe ch)]
        (l/debug "client: wspipe is ssl? = %s." (some? ctx))
        (if-some
          [ssl (c/cast? SslContext ctx)]
          (.addLast pp
                    "ssl"
                    (.newHandler ssl
                                 (.alloc ^Channel ch))))
        (doto pp
          (.addLast "codec" (HttpClientCodec.))
          (.addLast "agg" (HttpObjectAggregator. 96000))
          (.addLast "wcc" WebSocketClientCompressionHandler/INSTANCE)
          (.addLast "wsh"
                    (wsh<>
                      (WebSocketClientHandshakerFactory/newHandshaker
                        (:uri args)
                        WebSocketVersion/V13
                        nil true (DefaultHttpHeaders.)) cb args))
          (.addLast "ws-agg" mg/wsock-aggregator<>)
          (.addLast "ws-user" (wsock-hdlr user)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- bootstrap!
  [args]
  (l/info "client bootstrap ctor().")
  (let [{:as ARGS
         :keys [max-msg-size max-mem-size
                version temp-dir server-cert
                scheme threads rcv-buf options]}
        (merge {:max-mem-size i/*membuf-limit*
                :rcv-buf (* 2 c/MegaBytes)
                :threads 0
                :max-msg-size Integer/MAX_VALUE} args)
        ctx (maybe-ssl?? server-cert scheme (= version "2"))
        temp-dir (u/fpath (or temp-dir
                              i/*tempfile-repo*))
        [g z] (nc/g-and-c threads :tcpc)
        bs (Bootstrap.)
        options (or options
                    [[ChannelOption/SO_RCVBUF (int rcv-buf)]
                     [ChannelOption/SO_KEEPALIVE true]
                     [ChannelOption/TCP_NODELAY true]])]
    (nc/config-disk-files true temp-dir)
    (l/info "setting client options.")
    (doseq [[k v] options] (.option bs k v))
    ;;assign generic attributes for all channels
    (.attr bs nc/chcfg-key ARGS)
    (.attr bs
           nc/dfac-key
           (H1DataFactory. (int max-mem-size)))
    (l/info "netty client bootstraped with [%s]."
            (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
    [(doto bs
       (.channel z)
       (.group ^EventLoopGroup g)) ctx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h1c-finz [bs ch]
  (let [f (nc/get-akey nc/dfac-key ch)]
    (nc/future-cb (.closeFuture ^Channel ch)
                  (c/fn_1 (.cleanAllHttpData ^H1DataFactory f)
                          (nobs! bs nil)
                          (l/debug "shutdown: netty h1-client.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ret-conn [module bs host port rcp hint]
  (reify
    cc/ClientConnectPromise
    (cc-sync-get-connect [_]
      (cc/cc-sync-get-connect _ 5000))
    (cc-sync-get-connect [_ ms]
      (let [r (deref rcp ms nil)]
        (cond
          (not (c/is? Channel r))
          r
          (:v2? hint)
          (try (let [^ChannelPromise pm (nc/get-akey h2s-key r)]
                 (l/debug "client waits %s[ms] for h2-settings." ms)
                 (if-not (.awaitUninterruptibly pm
                                                ms TimeUnit/MILLISECONDS)
                   (u/throw-ISE "Timed out waiting for h2-settings."))
                 (if-not (.isSuccess pm)
                   (.cause pm)
                   (do (l/debug "client waited %s[ms] -success." ms)
                       (cconn<> module bs r host port))))
               (catch Throwable e e))
          (:user hint)
          (nc/set-akey cc-key
                       r
                       (cconn<> module bs r host port hint))
          :else
          (cconn<> module bs r host port))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- hx-conn [module host port args fpipe hint]
  (let [args (assoc args
                    :version
                    (if (:v2? hint) "2" "1.1"))
        rcp (promise)
        [bs ctx] (bootstrap! args)
        pp (if-not (:user hint)
             (fpipe ctx rcp args)
             (fpipe ctx
                    (fn [^ChannelFuture ff]
                      (deliver rcp
                               (if (.isSuccess ff)
                                 (.channel ff)
                                 (or (.cause ff)
                                     (Exception. "Conn error!"))))) (:user hint) args))]
    (.handler ^Bootstrap bs pp)
    (h1c-finz bs (connect bs host port ctx))
    (ret-conn module bs host port rcp hint)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-req<>
  "" ^HttpRequest [^HttpMethod mt ^String uri]
  (DefaultHttpRequest. HttpVersion/HTTP_1_1 mt uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-req<+>
  "" {:tag FullHttpRequest}
  ([mt uri] (http-req<+> mt uri nil))
  ([^HttpMethod mt ^String uri ^ByteBuf body]
   (if (nil? body)
     (let [x (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri)]
       (HttpUtil/setContentLength x 0) x)
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-post<+>
  "" ^FullHttpRequest
  [^String uri ^ByteBuf body] (http-req<+> HttpMethod/POST uri body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-post<>
  "" ^HttpRequest [uri] (http-req<> HttpMethod/POST uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-get<>
  "" ^FullHttpRequest [uri] (http-req<+> HttpMethod/GET uri nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-module<>
  "" []
  (reify
    cc/HttpClientModule

    (hc-send-http [_ conn op uri data args]
      (let [mt (HttpMethod/valueOf (c/ucase (name op)))
            {:keys [encoding headers
                    is-keep-alive?
                    version override]} args
            ^Channel ch (cc/cc-channel conn)
            body (nc/bbuf?? data ch encoding)
            ^URI uri uri
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
              (nc/add-header req kw vv))
            (nc/set-header req kw v)))
        (nc/set-header req (nc/h1hdr* HOST) (:host args))
        (if (= version "2")
          (nc/set-header req
                         (.text HttpConversionUtil$ExtensionHeaderNames/SCHEME)
                         (.getScheme uri))
          (nc/set-header req
                         (nc/h1hdr* CONNECTION)
                         (if-not is-keep-alive?
                           (nc/h1hdv* CLOSE)
                           (nc/h1hdv* KEEP_ALIVE))))
        (c/if-some+ [mo (c/stror override "")]
          (nc/set-header req "X-HTTP-Method-Override" mo))
        (if (zero? clen)
          (HttpUtil/setContentLength req 0)
          (do (if-not (c/is? FullHttpRequest req)
                (HttpUtil/setTransferEncodingChunked req true))
              (if-not (nc/has-header? req "content-type")
                (nc/set-header req
                               (nc/h1hdr* CONTENT_TYPE)
                               "application/octet-stream"))
              (if (c/spos? clen)
                (HttpUtil/setContentLength req clen))))
        (l/debug (str "about to flush out req (headers), "
                      "isKeepAlive= %s, content-length= %s") is-keep-alive? clen)
        (c/do-with [out (nc/set-akey rsp-key ch (promise))]
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
                 (-> (.alloc ch)
                     (.directBuffer (int 4096))
                     (.writeBytes ^bytes msg)
                     (BinaryWebSocketFrame. ))) (.writeAndFlush ch))))

    (hc-h2-conn [module host port args]
      (hx-conn module host port args h2pipe {:v2? true}))

    (hc-h1-conn [module host port args]
      (hx-conn module host port args h1pipe nil))

    (hc-ws-conn [module host port user args]
      (hx-conn module host port args wspipe {:user user}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

