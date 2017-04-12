;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Http client using netty."
      :author "Kenneth Leung"}

  czlab.convoy.nettio.client

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.convoy.nettio.aggregate]
        [czlab.convoy.nettio.core]
        [czlab.convoy.net.util]
        [czlab.basal.core]
        [czlab.basal.meta]
        [czlab.basal.str]
        [czlab.basal.io])

  (:import [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketClientCompressionHandler]
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
           [io.netty.handler.codec.http2
            Http2SecurityUtil
            HttpConversionUtil]
           [io.netty.handler.ssl
            ApplicationProtocolNames
            OpenSsl
            SslContext
            SslContextBuilder
            SslProvider
            SupportedCipherSuiteFilter
            ApplicationProtocolConfig
            ApplicationProtocolConfig$Protocol
            ApplicationProtocolConfig$SelectorFailureBehavior
            ApplicationProtocolConfig$SelectedListenerFailureBehavior]
           [java.io InputStream File IOException]
           [io.netty.buffer ByteBuf Unpooled]
           [java.net InetSocketAddress URI URL]
           [io.netty.bootstrap Bootstrap]
           [io.netty.util AttributeKey]
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
           [czlab.convoy.nettio
            WholeResponse
            H1DataFactory
            ClientConnect
            InboundHandler
            WSClientConnect]
           [czlab.jasal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private ^ChannelHandler msg-agg (h1resAggregator<>))
(def ^:private ^AttributeKey rsp-key  (akey<> "rsp-result"))
(def ^:private ^AttributeKey cf-key  (akey<> "wsock-future"))
(def ^:private ^AttributeKey cc-key  (akey<> "wsock-client"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeSSL2
  "" ^SslContext
  [{:keys [serverCert scheme] :as args}]

  (when (and (not= "http" scheme)
             (hgl? serverCert))
    (let
      [p (if (OpenSsl/isAlpnSupported)
           SslProvider/OPENSSL
           SslProvider/JDK)
       #^"[Ljava.security.cert.X509Certificate;"
       cs (->> (convCerts (io/as-url serverCert))
               (vargs X509Certificate))
       pms (doto (java.util.ArrayList.)
             (.add ApplicationProtocolNames/HTTP_2)
             (.add ApplicationProtocolNames/HTTP_1_1))
       cfg
       (ApplicationProtocolConfig.
         ApplicationProtocolConfig$Protocol/ALPN
         ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
         ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
         pms)]
      (-> (SslContextBuilder/forClient)
          (.ciphers Http2SecurityUtil/CIPHERS
                    SupportedCipherSuiteFilter/INSTANCE)
          (.trustManager cs)
          (.sslProvider  ^SslProvider p)
          (.applicationProtocolConfig cfg)
          .build))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeSSL1
  "" ^SslContext
  [{:keys [serverCert scheme] :as args}]

  (when (and (not= "http" scheme)
             (hgl? serverCert))
    (let
      [p  (if (OpenSsl/isAlpnSupported)
            SslProvider/OPENSSL
            SslProvider/JDK)
       #^"[Ljava.security.cert.X509Certificate;"
       cs (->> (convCerts (io/as-url serverCert))
               (vargs X509Certificate))]
      (-> (SslContextBuilder/forClient)
          (.sslProvider ^SslProvider p)
          (.trustManager cs)
          .build))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- send2 "" ^ChannelFuture [^Channel ch ^String op ^XData xs args] nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- send1
  "" [^Channel ch op ^URI uri data args]

  (let [mt (HttpMethod/valueOf (ucase (name op)))
        cs (stror (:encoding args) "utf-8")
        headers (:headers args)
        body (byteBuf?? data ch cs)
        mo (stror (:override args) "")
        ka? (:isKeepAlive? args)
        path (.getPath uri)
        qy (.getQuery uri)
        uriStr (if (hgl? qy)
                 (str path "?" qy) path)
        clen
        (cond
          (ist? ByteBuf body) (. ^ByteBuf body readableBytes)
          (ist? File body) (. ^File body length)
          (ist? InputStream body) -1
          (nil? body) 0
          :else (throwIOE "bad type %s" (class body)))
        req
        (if (or (nil? body)
                (ist? ByteBuf body))
          (httpReq<+> mt uriStr body)
          (httpReq<> mt uriStr))]
    (doseq [[k v] (seq headers)
            :let [kw (name k)]]
      (if (seq? v)
        (doseq [vv (seq v)]
          (addHeader req kw vv))
        (setHeader req kw v)))
    (setHeader req HttpHeaderNames/HOST (:host args))
    (setHeader req
               HttpHeaderNames/CONNECTION
               (if ka?
                 HttpHeaderValues/KEEP_ALIVE
                 HttpHeaderValues/CLOSE))
    (if (hgl? mo)
      (setHeader req "X-HTTP-Method-Override" mo))
    (if (== 0 clen)
      (HttpUtil/setContentLength req 0)
      (do
        (if-not (ist? FullHttpRequest req)
          (HttpUtil/setTransferEncodingChunked req true))
        (if-not (hasHeader? req "content-type")
          (setHeader req
                     HttpHeaderNames/CONTENT_TYPE
                     "application/octet-stream"))
        (if (spos? clen)
          (HttpUtil/setContentLength req clen))))
    (log/debug "Netty client: about to flush out request (headers)")
    (log/debug "Netty client: content has length %s" clen)
    (let [out (setAKey ch rsp-key (promise))
          cf (.write ch req)
          cf (condp instance? body
               File
               (->> (ChunkedFile. ^File body)
                    HttpChunkedInput.
                    (.write ch))
               InputStream
               (->> (ChunkedStream.
                      ^InputStream body)
                    HttpChunkedInput.
                    (.write ch))
               cf)]
      (.flush ch)
      out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- connect
  "" ^Channel [^Bootstrap bs host port ssl?]

  (log/debug "netty client about to connect")
  (let
    [port  (if (< port 0) (if ssl? 443 80) port)
     sock (InetSocketAddress. (str host)
                              (int port))
     cf (some-> (.connect bs sock) .sync)
     ch (some-> cf .channel)]
    (if (or (nil? cf)
            (not (.isSuccess cf)) (nil? ch))
      (throwIOE "Connect error: %s" (.cause cf)))
    ch))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def
  ^:private
  ^ChannelHandler
  user-hdlr
  (proxy [InboundHandler][]
    (exceptionCaught [ctx err]
      (when-some [p (getAKey ctx rsp-key)]
        (delAKey ctx rsp-key)
        (deliver p err))
      (. ^ChannelHandlerContext ctx close))
    (channelRead0 [ctx msg]
      (when-some [p (getAKey ctx rsp-key)]
        (delAKey ctx rsp-key)
        (deliver p msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wsh<>
  "" ^ChannelHandler [^WebSocketClientHandshaker
                      handshaker
                      cb user args]

  (proxy [InboundHandler][]
    (handlerAdded [ctx]
      (let [p (. ^ChannelHandlerContext ctx newPromise)]
        (setAKey ctx cf-key p)
        (. p addListener (cfop<> cb))
        (log/debug "wsc handler-added")))
    (channelActive [ctx]
      (log/debug "wsc handshaker start hand-shake")
      (. handshaker handshake (ch?? ctx)))
    (exceptionCaught [ctx err]
      (if-some [^ChannelPromise f (getAKey ctx cf-key)]
        (if-not (.isDone f)
          (. f setFailure ^Throwable err)))
      ;;(log/warn err "ERRRRRRRRRRRRRRR!!!!!")
      (. ^ChannelHandlerContext ctx close))
    (channelRead0 [ctx msg]
      (let [^ChannelPromise f (getAKey ctx cf-key)
            ^WSClientConnect wcc (getAKey ctx cc-key)
            ch (ch?? ctx)]
        (cond
          (not (. handshaker isHandshakeComplete))
          (do
            (log/debug "attempt to finz the hand-shake...")
            (.finishHandshake handshaker ch ^FullHttpResponse msg)
            (.setSuccess f))
          (ist? FullHttpResponse msg)
          (throw (IllegalStateException.
                   (str "Unexpected FullHttpResponse (status="
                        (.status ^FullHttpResponse msg))))
          (ist? BinaryWebSocketFrame msg)
          (let [^BinaryWebSocketFrame f msg]
            (log/debug "got a bin frame: %s" msg)
            (user wcc {:blob (toByteArray (.content f))
                       :final? (.isFinalFragment f)}))
          (ist? TextWebSocketFrame msg)
          (let [^TextWebSocketFrame f msg]
            (log/debug "got a text frame: %s" msg)
            (user wcc {:text (.text f)
                       :final? (.isFinalFragment f)}))
          (ist? ContinuationWebSocketFrame msg)
          (do
            (log/debug "got a CONTINUE frame: %s" msg))
          (ist? PongWebSocketFrame msg)
          (do
            (log/debug "received pong frame")
            (user wcc {:pong? true}))
          (ist? CloseWebSocketFrame msg)
          (do
            (log/debug "received close frame")
            (user wcc {:close? true})
            (. ^ChannelHandlerContext ctx close)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h1pipe
  "" ^ChannelHandler [ctx args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (if-some
        [ssl (cast? SslContext ctx)]
        (->> (.newHandler ssl
                          (.alloc ^Channel ch))
             (.addLast (cpipe ch) "ssl")))
      (doto (cpipe ch)
        (.addLast "codec" (HttpClientCodec.))
        (.addLast "msg-agg" msg-agg)
        (.addLast "cw" (ChunkedWriteHandler.))
        (.addLast "user-cb" user-hdlr)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wspipe
  "" ^ChannelHandler [ctx cb user args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (if-some
        [ssl (cast? SslContext ctx)]
        (->> (.newHandler ssl
                          (.alloc ^Channel ch))
             (.addLast (cpipe ch) "ssl")))
      (doto (cpipe ch)
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
                    user
                    args))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- boot!
  "" [{:keys [maxContentSize maxInMemory
              version tempFileDir
              threads rcvBuf options]
       :or {maxContentSize Integer/MAX_VALUE
            maxInMemory *membuf-limit*
            version "1.1"
            rcvBuf (* 2 MegaBytes)
            threads 0}
       :as args}]

  (let [tempFileDir (fpath (or tempFileDir
                               *tempfile-repo*))
        ctx (if (= version "1.1")
              (maybeSSL1 args) (maybeSSL2 args))
        [g z] (gAndC threads :tcpc)
        bs (Bootstrap.)
        options (or options
                    [[ChannelOption/SO_RCVBUF (int rcvBuf)]
                     [ChannelOption/SO_KEEPALIVE true]
                     [ChannelOption/TCP_NODELAY true]])]
    (configDiskFiles true tempFileDir)
    (doseq [[k v] options] (.option bs k v))
    ;;assign generic attributes for all channels
    (.attr bs chcfg-key args)
    (.attr bs
           dfac-key
           (H1DataFactory. (int maxInMemory)))
    (log/info "netty client bootstraped with [%s]"
              (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
    (log/info "netty client dfiles repo: %s" tempFileDir)
    (doto bs
      (.channel z)
      (.group ^EventLoopGroup g))
    [bs ctx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- mkWSClient
  "" ^WSClientConnect [^Bootstrap bs host port ^Channel ch]

  (reify WSClientConnect
    (write [_ m]
      (some->>
        (cond
          (ist? WebSocketFrame m)
          m
          (string? m)
          (TextWebSocketFrame. ^String m)
          (instBytes? m)
          (-> (.alloc ch)
              (.directBuffer (int 4096))
              (.writeBytes  ^bytes m)
              (BinaryWebSocketFrame. )))
        (.writeAndFlush ch )))


    (dispose [_]
      (try!
        (if (.isOpen ch)
          (doto ch
            (.writeAndFlush (CloseWebSocketFrame.))
            .close)
          (.. (.config ^Bootstrap bs)
              group shutdownGracefully))))

    (channel [_] ch)

    (port [_] port)

    (host [_] host)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wsconnCB
  "" [rcp bs host port]

  (fn [^ChannelFuture ff]
    (if (.isSuccess ff)
      (let [ch (.channel ff)
            cc (mkWSClient bs host port ch)]
        (setAKey c cc-key cc)
        (deliver rcp cc))
      (let [err (or (.cause ff)
                    (Exception. "conn error"))]
        ;;(log/warn err "")
        (deliver rcp err)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wsBootAndConn
  "" [rcp host port user args]

  (let [[^Bootstrap bs ctx] (boot! args)
        cb (wsconnCB rcp bs host port)
        _ (.handler bs (wspipe ctx cb user args))
        c (connect bs host port (some? ctx))
        ^H1DataFactory f (getAKey c dfac-key)]
    (futureCB (.closeFuture c)
              (fn [_]
                (log/debug "shutdown: netty client")
                (some-> f .cleanAllHttpData)
                (try! (.. bs config group shutdownGracefully))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h1BootAndConn
  "" [host port args]

  (let [[^Bootstrap bs ctx] (boot! args)
        _ (.handler bs (h1pipe ctx args))
        c (connect bs host port (some? ctx))
        ^H1DataFactory f (getAKey c dfac-key)]
    (futureCB (.closeFuture c)
              (fn [_]
                (log/debug "shutdown: netty client")
                (some-> f .cleanAllHttpData)
                (try! (.. bs config group shutdownGracefully))))
    (reify ClientConnect
      (dispose [_]
        (try! (if (.isOpen c)
                (.close c)
                (.. bs config group shutdownGracefully))))
      (channel [_] c)
      (port [_] port)
      (host [_] host))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wsconnect<>
  "" {:tag ClientConnect}

  ([host port uri cb]
   (wsconnect<> host port uri cb nil))

  ([host port uri cb args]
   (let [pfx (if (:serverCert args) "wss" "ws")
         uriStr (format "%s://%s:%d%s"
                        pfx host port uri)
         rc (promise)]
     (wsBootAndConn rc
                    host
                    port
                    cb
                    (merge args
                           {:websock true
                            :uri (URI. uriStr)
                            :version "1.1"}))
     rc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1connect<>
  "" {:tag ClientConnect}

  ([host port] (h1connect<> host port nil))
  ([host port args]
   (h1BootAndConn host port (merge args {:version "1.1"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1send* ""

  ([conn method uri data]
   (h1send* conn method uri data nil))

  ([^ClientConnect conn method uri data args]
   (let [args (merge args
                     {:isKeepAlive? true
                      :host (.host conn)})]
     (send1 (.channel conn) method uri data args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1send
  "Gives back a promise" {:tag IDeref}

  ([target method data]
   (h1send target method data nil))

  ([target method data args]
   (let [url (io/as-url target)
         args (merge args
                     {:scheme (.getProtocol url)
                      :isKeepAlive? false
                      :host (.getHost url)})
         cc (h1connect<> (.getHost url)
                         (.getPort url) args)]
     (send1 (.channel cc)
            method (.toURI url) data args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1post
  "Gives back a promise" {:tag IDeref}
  ([target data] (h1post target data nil))
  ([target data args] (h1send target :post data args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1get
  "Gives back a promise" {:tag IDeref}
  ([target] (h1get target nil))
  ([target args] (h1send target :get nil args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


