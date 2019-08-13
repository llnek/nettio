;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Http client using netty."
      :author "Kenneth Leung"}

  czlab.nettio.client

  (:require [czlab.nettio.msgs :as mg]
            [czlab.nettio.core :as nc]
            [czlab.convoy.util :as ct]
            [czlab.convoy.core :as cc]
            [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u])

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
           [java.security.cert X509Certificate]
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
            DuplexHandler
            H1DataFactory
            H2ConnBuilder
            InboundHandler]
           [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private ^AttributeKey h2s-key  (nc/akey<> "h2settings-promise"))
(def ^:private ^AttributeKey rsp-key  (nc/akey<> "rsp-result"))
(def ^:private ^AttributeKey cf-key  (nc/akey<> "wsock-future"))
(def ^:private ^AttributeKey cc-key  (nc/akey<> "wsock-client"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^{:private true
       :tag ChannelHandler
       :doc "A handler which aggregates chunks into a full response"}
  msg-agg
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg] (mg/agg-h1-read ctx msg false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-ctx
  "" ^SslContextBuilder [scert]
  (let [ctx (SslContextBuilder/forClient)]
    (if (= "*" scert)
      (.trustManager ctx InsecureTrustManagerFactory/INSTANCE)
      (let
        [#^"[Ljava.security.cert.X509Certificate;"
         cs (->> (nc/conv-certs (io/as-url scert))
                 (c/vargs X509Certificate))]
        (.trustManager ctx cs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-ssl
  "" ^SslContext
  [scert scheme h2?]

  (when (and (not= "http" scheme)
             (s/hgl? scert))
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
          (-> (.sslProvider ctx p) .build))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- connect
  "" ^Channel [^Bootstrap bs host port ssl?]

  (l/debug "netty client about to connect to host: %s." host)
  (let
    [port  (if (< port 0) (if ssl? 443 80) port)
     _ (l/debug "netty client about to connect to port: %s." port)
     sock (InetSocketAddress. (str host)
                              (int port))
     cf (some-> (.connect bs sock) .sync)]
    (c/do-with [ch (some-> cf .channel)]
      (if (or (nil? cf)
              (not (.isSuccess cf)) (nil? ch))
        (u/throw-IOE "Connect error: %s" (.cause cf)))
      (l/debug (str "netty client "
                    "connected to host: %s, port: %s.") host port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^{:private true
       :tag ChannelHandler}
  user-hdlr
  (proxy [InboundHandler][true]
    (exceptionCaught [ctx err]
      (when-some [p (nc/get-akey ctx rsp-key)]
        (deliver p err)
        (nc/del-akey ctx rsp-key))
      (nc/close-ch ctx))
    (readMsg [ctx msg]
      (when-some [p (nc/get-akey ctx rsp-key)]
        (deliver p msg)
        (nc/del-akey ctx rsp-key)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsock-hdlr
  "" ^ChannelHandler [user]
  (proxy [InboundHandler][true]
    (exceptionCaught [ctx err] (nc/close-ch ctx))
    (readMsg [ctx msg] (user (nc/get-akey ctx cc-key) msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsh<>
  "" ^ChannelHandler [^WebSocketClientHandshaker
                      handshaker
                      cb args]

  (proxy [InboundHandler][true]
    (handlerAdded [ctx]
      (let [p (.newPromise ^ChannelHandlerContext ctx)]
        (nc/set-akey ctx cf-key p)
        (.addListener p (nc/cfop<> cb))
        (l/debug "wsc handler-added")))
    (channelActive [ctx]
      (l/debug "wsc handshaker start hand-shake")
      (.handshake handshaker (nc/ch?? ctx)))
    (exceptionCaught [ctx err]
      (if-some [^ChannelPromise f (nc/get-akey ctx cf-key)]
        (if-not (.isDone f)
          (.setFailure f ^Throwable err)))
      (l/warn "%s" (.getMessage ^Throwable err))
      (nc/close-ch ctx))
    (readMsg [ctx msg]
      (let [^ChannelPromise f (nc/get-akey ctx cf-key)
            ch (nc/ch?? ctx)]
        (cond
          (not (.isHandshakeComplete handshaker))
          (do
            (l/debug "attempt to finz the hand-shake...")
            (.finishHandshake handshaker ch ^FullHttpResponse msg)
            (l/debug "finz'ed the hand-shake... success!")
            (.setSuccess f))
          (c/is? FullHttpResponse msg)
          (do
            (u/throw-ISE
              "Unexpected FullHttpResponse (status=%s"
              (.status ^FullHttpResponse msg)))
          (c/is? CloseWebSocketFrame msg)
          (do
            (l/debug "received close frame")
            (nc/close-ch ctx))
          :else
          (->> (nc/ref-add msg)
               (.fireChannelRead ^ChannelHandlerContext ctx )))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h2pipe
  "" ^ChannelHandler [ctx args]

  (l/debug "client:h2pipe: ssl ctx = %s" ctx)
  (proxy [ChannelInitializer][]
    (initChannel [c]
      (let [hh (HttpToHttp2ConnectionHandlerBuilder.)
            _ (.server hh false)
            ch (nc/ch?? c)
            pp (nc/cpipe ch)
            pm (.newPromise ch)
            _ (.frameListener hh
                              (mg/h20-aggregator<> pm))
            ssl (c/cast? SslContext ctx)]
        (nc/set-akey c h2s-key pm)
        (some->> (some-> ssl (.newHandler (.alloc ch)))
                 (.addLast pp "ssl"))
        (->>
          (proxy [ApplicationProtocolNegotiationHandler][""]
            (configurePipeline [cx pn]
              (if (.equals ApplicationProtocolNames/HTTP_2 ^String pn)
                (doto (nc/cpipe cx)
                  (.addLast "codec" (.build hh))
                  (.addLast "cw" (ChunkedWriteHandler.))
                  (.addLast "user-cb" user-hdlr))
                (do
                  (nc/close-ch cx)
                  (u/throw-ISE "unknown protocol: %s" pn)))))
          (.addLast pp "apn"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h1pipe
  "" ^ChannelHandler [ctx args]

  (l/debug "client:h1pipe: ssl ctx = %s." ctx)
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (if-some
        [ssl (c/cast? SslContext ctx)]
        (->> (.newHandler ssl
                          (.alloc ^Channel ch))
             (.addLast (nc/cpipe ch) "ssl")))
      (doto (nc/cpipe ch)
        (.addLast "codec" (HttpClientCodec.))
        (.addLast "msg-agg" msg-agg)
        (.addLast "cw" (ChunkedWriteHandler.))
        (.addLast "user-cb" user-hdlr)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wspipe
  "" ^ChannelHandler [ctx cb user args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (l/debug "client: wspipe is ssl? = %s" (some? ctx))
      (if-some
        [ssl (c/cast? SslContext ctx)]
        (->> (.newHandler ssl
                          (.alloc ^Channel ch))
             (.addLast (nc/cpipe ch) "ssl")))
      (doto (nc/cpipe ch)
        (.addLast "codec" (HttpClientCodec.))
        (.addLast "agg" (HttpObjectAggregator. 96000))
        (.addLast "wcc" WebSocketClientCompressionHandler/INSTANCE)
        (.addLast "wsh"
                  (wsh<>
                    (WebSocketClientHandshakerFactory/newHandshaker
                      (:uri args)
                      WebSocketVersion/V13
                      nil true (DefaultHttpHeaders.))
                    cb
                    args))
        (.addLast "ws-agg" mg/wsock-aggregator<>)
        (.addLast "ws-user" (wsock-hdlr user))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- boot!
  "" [{:keys [max-content-size max-in-memory
              version temp-dir
              server-cert scheme
              threads rcv-buf options]
       :or {max-content-size Integer/MAX_VALUE
            max-in-memory i/*membuf-limit*
            rcv-buf (* 2 c/MegaBytes)
            threads 0}
       :as args}]

  (let [ctx (maybe-ssl server-cert scheme (= version "2"))
        temp-dir (u/fpath (or temp-dir
                              i/*tempfile-repo*))
        [g z] (nc/g-and-c threads :tcpc)
        bs (Bootstrap.)
        options (or options
                    [[ChannelOption/SO_RCVBUF (int rcv-buf)]
                     [ChannelOption/SO_KEEPALIVE true]
                     [ChannelOption/TCP_NODELAY true]])]
    (nc/config-disk-files true temp-dir)
    (doseq [[k v] options] (.option bs k v))
    ;;assign generic attributes for all channels
    (.attr bs nc/chcfg-key args)
    (.attr bs
           nc/dfac-key
           (H1DataFactory. (int max-in-memory)))
    (l/info "netty client bootstraped with [%s]."
            (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
    (l/info "netty client dfiles repo: %s." temp-dir)
    (doto bs
      (.channel z)
      (.group ^EventLoopGroup g))
    [bs ctx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- mk-ws-client
  "" [module ^Bootstrap bs host port ^Channel ch]

  (reify cc/WSMsgWriter
    (ws-write-msg [_ msg]
      (some->>
        (cond
          (c/is? WebSocketFrame msg)
          msg
          (string? msg)
          (TextWebSocketFrame. ^String msg)
          (bytes? msg)
          (-> (.alloc ch)
              (.directBuffer (int 4096))
              (.writeBytes  ^bytes msg)
              (BinaryWebSocketFrame. )))
        (.writeAndFlush ch)))
    cc/ClientConnect
    (cc-finz [_]
      (c/try!
        (if (.isOpen ch)
          (doto ch
            (.writeAndFlush (CloseWebSocketFrame.))
            .close)
          (.. (.config ^Bootstrap bs)
              group shutdownGracefully))))
    (cc-await-connect [_ ms] )
    (cc-channel [_] ch)
    (cc-module [_] module)
    (cc-remote-port [_] port)
    (cc-remote-host [_] host)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsconn-cb
  "" [module rcp bs host port]

  (fn [^ChannelFuture ff]
    (if (.isSuccess ff)
      (let [ch (.channel ff)
            cc (mk-ws-client module bs host port ch)]
        (nc/set-akey ch cc-key cc)
        (deliver rcp cc))
      (let [err (or (.cause ff)
                    (Exception. "conn error"))]
        ;;(l/warn err "")
        (deliver rcp err)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyClientModule []
  cc/HttpClientModule
  (hc-send-http [_ conn op uri data args]
    (let [mt (HttpMethod/valueOf (s/ucase (name op)))
          ^Channel ch (cc/cc-channel conn)
          ^URI uri uri
          {:keys [encoding
                  headers
                  version
                  override is-keep-alive?]} args
          body (nc/bytebuf?? data ch encoding)
          mo (s/stror override "")
          path (.getPath uri)
          qy (.getQuery uri)
          uriStr (if (s/hgl? qy)
                   (str path "?" qy) path)
          clen (cond (c/is? ByteBuf body) (.readableBytes ^ByteBuf body)
                     (c/is? File body) (.length ^File body)
                     (c/is? InputStream body) -1
                     (nil? body) 0
                     :else (u/throw-IOE "bad type %s." (class body)))
          req (if (or (nil? body)
                      (c/is? ByteBuf body))
                (nc/http-req<+> mt uriStr body)
                (nc/http-req<> mt uriStr))]
      (doseq [[k v] (seq headers)
              :let [kw (name k)]]
        (if (seq? v)
          (doseq [vv (seq v)]
            (nc/add-header req kw vv))
          (nc/set-header req kw v)))
      (nc/set-header req HttpHeaderNames/HOST (:host args))
      (if (= version "2")
        (nc/set-header req
                       (.text HttpConversionUtil$ExtensionHeaderNames/SCHEME)
                       (.getScheme uri))
        (nc/set-header req
                       HttpHeaderNames/CONNECTION
                       (if is-keep-alive?
                         HttpHeaderValues/KEEP_ALIVE
                         HttpHeaderValues/CLOSE)))
      (if (s/hgl? mo)
        (nc/set-header req "X-HTTP-Method-Override" mo))
      (if (zero? clen)
        (HttpUtil/setContentLength req 0)
        (do (if-not (c/is? FullHttpRequest req)
              (HttpUtil/setTransferEncodingChunked req true))
            (if-not (nc/has-header? req "content-type")
              (nc/set-header req
                             HttpHeaderNames/CONTENT_TYPE
                             "application/octet-stream"))
            (if (c/spos? clen)
              (HttpUtil/setContentLength req clen))))
      (l/debug "Netty client: about to flush out request (headers).")
      (l/debug "Netty client: isKeepAlive= %s." is-keep-alive?)
      (l/debug "Netty client: content has length %s." clen)
      (c/do-with [out (nc/set-akey ch rsp-key (promise))]
        (let [cf (.write ch req)
              cf (condp instance? body
                   File (->> (ChunkedFile. ^File body)
                             HttpChunkedInput. (.write ch))
                   InputStream (->> (ChunkedStream.
                                      ^InputStream body)
                                    HttpChunkedInput. (.write ch)) cf)]
          (.flush ch)))))
  (hc-ws-conn [module rcp host port user args]
    (let [[^Bootstrap bs ctx] (boot! args)
          cb (wsconn-cb module rcp bs host port)
          _ (.handler bs (wspipe ctx cb user args))
          c (connect bs host port (some? ctx))
          ^H1DataFactory f (nc/get-akey c nc/dfac-key)]
      (nc/future-cb (.closeFuture c)
                    (c/fn_1 (l/debug "shutdown: netty ws-client.")
                            (some-> f .cleanAllHttpData)
                            (c/try! (.. bs config group shutdownGracefully))))))
  (hc-h2-conn [module host port args]
    (let [[^Bootstrap bs ctx] (boot! args)
          _ (.handler bs (h2pipe ctx args))
          ch (connect bs host port (some? ctx))
          ^ChannelPromise pm (nc/get-akey ch h2s-key)
          ^H1DataFactory f (nc/get-akey ch nc/dfac-key)]
      (nc/future-cb (.closeFuture ch)
                    (c/fn_1 (l/debug "shutdown: netty h2-client.")
                            (some-> f .cleanAllHttpData)
                            (c/try! (.. bs config group shutdownGracefully))))
      (reify cc/ClientConnect
        (cc-await-connect [_ ms]
          (l/debug "client waits %s[ms] for h2 settings....." ms)
          (if-not (.awaitUninterruptibly pm ms TimeUnit/MILLISECONDS)
            (u/throw-ISE "Timed out waiting for settings."))
          (if-not (.isSuccess pm)
            (c/trap! RuntimeException (.cause pm)))
          (l/debug "client waited %s[ms] ok!" ms))
        (cc-channel [_] ch)
        (cc-module [_] module)
        (cc-remote-port [_] port)
        (cc-remote-host [_] host)
        (cc-finz [_]
          (c/try! (if (.isOpen ch)
                    (.close ch)
                    (.. bs config group shutdownGracefully)))))))
  (hc-h1-conn [module host port args]
    (let [[^Bootstrap bs ctx] (boot! args)
          _ (.handler bs (h1pipe ctx args))
          ch (connect bs host port (some? ctx))
          ^H1DataFactory f (nc/get-akey ch nc/dfac-key)]
      (nc/future-cb (.closeFuture ch)
                    (c/fn_1 (l/debug "shutdown: netty h1-client.")
                            (some-> f .cleanAllHttpData)
                            (c/try! (.. bs config group shutdownGracefully))))
      (reify cc/ClientConnect
        (cc-await-connect [_ ms] )
        (cc-module [_] module)
        (cc-channel [_] ch)
        (cc-remote-port [_] port)
        (cc-remote-host [_] host)
        (cc-finz [_]
          (c/try! (if (.isOpen ch)
                    (.close ch)
                    (.. bs config group shutdownGracefully))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2send
  "Gives back a promise"
  ([target method data]
   (h2send target method data nil))
  ([target method data args]
   (cc/hxsend (NettyClientModule.)
              target method data (merge args {:version "2"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1send
  "Gives back a promise"
  ([target method data]
   (h1send target method data nil))
  ([target method data args]
   (cc/hxsend (NettyClientModule.) target method data args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1post
  "Gives back a promise"
  ([target data] (h1post target data nil))
  ([target data args]
   (cc/hxsend (NettyClientModule.) target :post data args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1get
  "Gives back a promise"
  ([target] (h1get target nil))
  ([target args]
   (cc/hxsend (NettyClientModule.) target :get nil args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2post
  "Gives back a promise"
  ([target data]
   (h2post target data nil))
  ([target data args]
   (cc/hxsend (NettyClientModule.)
              target :post data (merge args {:version "2"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2get
  "Gives back a promise"
  ([target] (h2get target nil))
  ([target args]
   (cc/hxsend (NettyClientModule.)
              target :get nil (merge args {:version "2"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

