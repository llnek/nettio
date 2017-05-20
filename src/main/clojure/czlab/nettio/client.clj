;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
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
            [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.meta :as m]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i])

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
            H1DataFactory
            H2ConnBuilder
            InboundHandler]
           [czlab.jasal Disposable XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private ^AttributeKey h2s-key  (nc/akey<> "h2settings-promise"))
(def ^:private ^AttributeKey rsp-key  (nc/akey<> "rsp-result"))
(def ^:private ^AttributeKey cf-key  (nc/akey<> "wsock-future"))
(def ^:private ^AttributeKey cc-key  (nc/akey<> "wsock-client"))
(def ^:private ^ChannelHandler msg-agg (mg/h1resAggregator<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defprotocol ClientConnect
  ""
  (c-channel [_] "")
  (remote-host [_] "")
  (remote-port [_] "")
  (await-connect [_ millis] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defprotocol WSMsgWriter
  ""
  (write-ws-msg [_ msg] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- buildCtx
  "" ^SslContextBuilder [scert]
  (let [ctx (SslContextBuilder/forClient)]
    (if (= "*" scert)
      (.trustManager ctx InsecureTrustManagerFactory/INSTANCE)
      (let
        [#^"[Ljava.security.cert.X509Certificate;"
         cs (->> (nc/convCerts (io/as-url scert))
                 (c/vargs X509Certificate))]
        (.trustManager ctx cs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeSSL
  "" ^SslContext
  [scert scheme h2?]

  (when (and (not= "http" scheme)
             (s/hgl? scert))
    (let [ctx (buildCtx scert)]
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
;;
(defn- sendHttp
  "" [^Channel ch op ^URI uri data args]

  (let [mt (HttpMethod/valueOf (s/ucase (name op)))
        {:keys [encoding
                headers
                version
                override isKeepAlive?]}
        args
        cs (s/stror encoding "utf-8")
        body (nc/byteBuf?? data ch cs)
        mo (s/stror override "")
        path (.getPath uri)
        qy (.getQuery uri)
        uriStr (if (s/hgl? qy)
                 (str path "?" qy) path)
        clen
        (cond
          (c/ist? ByteBuf body) (.readableBytes ^ByteBuf body)
          (c/ist? File body) (.length ^File body)
          (c/ist? InputStream body) -1
          (nil? body) 0
          :else (c/throwIOE "bad type %s" (class body)))
        req
        (if (or (nil? body)
                (c/ist? ByteBuf body))
          (nc/httpReq<+> mt uriStr body)
          (nc/httpReq<> mt uriStr))]
    (doseq [[k v] (seq headers)
            :let [kw (name k)]]
      (if (seq? v)
        (doseq [vv (seq v)]
          (nc/addHeader req kw vv))
        (nc/setHeader req kw v)))
    (nc/setHeader req HttpHeaderNames/HOST (:host args))
    (if (= version "2")
      (nc/setHeader req
                    (.text HttpConversionUtil$ExtensionHeaderNames/SCHEME)
                    (.getScheme uri))
      (nc/setHeader req
                    HttpHeaderNames/CONNECTION
                    (if isKeepAlive?
                      HttpHeaderValues/KEEP_ALIVE
                      HttpHeaderValues/CLOSE)))
    (if (s/hgl? mo)
      (nc/setHeader req "X-HTTP-Method-Override" mo))
    (if (== 0 clen)
      (HttpUtil/setContentLength req 0)
      (do
        (if-not (c/ist? FullHttpRequest req)
          (HttpUtil/setTransferEncodingChunked req true))
        (if-not (nc/hasHeader? req "content-type")
          (nc/setHeader req
                        HttpHeaderNames/CONTENT_TYPE
                        "application/octet-stream"))
        (if (c/spos? clen)
          (HttpUtil/setContentLength req clen))))
    (log/debug "Netty client: about to flush out request (headers)")
    (log/debug "Netty client: isKeepAlive= %s" isKeepAlive?)
    (log/debug "Netty client: content has length %s" clen)
    (c/do-with [out (nc/setAKey ch
                                rsp-key (promise))]
       (let
         [cf (.write ch req)
          cf (condp instance? body
               File
               (->> (ChunkedFile. ^File body)
                    HttpChunkedInput. (.write ch))
               InputStream
               (->> (ChunkedStream.
                      ^InputStream body)
                    HttpChunkedInput. (.write ch))
               cf)]
      (.flush ch)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- connect
  "" ^Channel [^Bootstrap bs host port ssl?]

  (log/debug "netty client about to connect to host: %s" host)
  (let
    [port  (if (< port 0) (if ssl? 443 80) port)
     _ (log/debug "netty client about to connect to port: %s" port)
     sock (InetSocketAddress. (str host)
                              (int port))
     cf (some-> (.connect bs sock) .sync)]
    (c/do-with [ch (some-> cf .channel)]
               (if (or (nil? cf)
                       (not (.isSuccess cf)) (nil? ch))
                 (c/throwIOE "Connect error: %s" (.cause cf)))
               (log/debug (str "netty client "
                               "connected to host: %s, port: %s") host port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def
  ^:private
  ^ChannelHandler
  user-hdlr
  (proxy [InboundHandler][true]
    (exceptionCaught [ctx err]
      (when-some [p (nc/getAKey ctx rsp-key)]
        (nc/delAKey ctx rsp-key)
        (deliver p err))
      (nc/closeCH ctx))
    (readMsg [ctx msg]
      (when-some [p (nc/getAKey ctx rsp-key)]
        (nc/delAKey ctx rsp-key)
        (deliver p msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wsock-hdlr
  "" ^ChannelHandler [user]

  (proxy [InboundHandler][true]
    (exceptionCaught [ctx err]
      (nc/closeCH ctx))
    (readMsg [ctx msg]
      (let [wcc (nc/getAKey ctx cc-key)]
        (user wcc msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wsh<>
  "" ^ChannelHandler [^WebSocketClientHandshaker
                      handshaker
                      cb args]

  (proxy [InboundHandler][true]
    (handlerAdded [ctx]
      (let [p (.newPromise ^ChannelHandlerContext ctx)]
        (nc/setAKey ctx cf-key p)
        (.addListener p (nc/cfop<> cb))
        (log/debug "wsc handler-added")))
    (channelActive [ctx]
      (log/debug "wsc handshaker start hand-shake")
      (.handshake handshaker (nc/ch?? ctx)))
    (exceptionCaught [ctx err]
      (if-some [^ChannelPromise f (nc/getAKey ctx cf-key)]
        (if-not (.isDone f)
          (.setFailure f ^Throwable err)))
      (log/warn "%s" (.getMessage ^Throwable err))
      (nc/closeCH ctx))
    (readMsg [ctx msg]
      (let [^ChannelPromise f (nc/getAKey ctx cf-key)
            ch (nc/ch?? ctx)]
        (cond
          (not (.isHandshakeComplete handshaker))
          (do
            (log/debug "attempt to finz the hand-shake...")
            (.finishHandshake handshaker ch ^FullHttpResponse msg)
            (log/debug "finz'ed the hand-shake... success!")
            (.setSuccess f))
          (c/ist? FullHttpResponse msg)
          (do
            (c/throwISE
              "Unexpected FullHttpResponse (status=%s"
              (.status ^FullHttpResponse msg)))
          (c/ist? CloseWebSocketFrame msg)
          (do
            (log/debug "received close frame")
            (nc/closeCH ctx))
          :else
          (->> (nc/ref-add msg)
               (.fireChannelRead ^ChannelHandlerContext ctx )))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h2pipe
  "" ^ChannelHandler [ctx args]

  (log/debug "client:h2pipe: ssl ctx = %s" ctx)
  (proxy [ChannelInitializer][]
    (initChannel [c]
      (let [hh (HttpToHttp2ConnectionHandlerBuilder.)
            _ (.server hh false)
            ch (nc/ch?? c)
            pp (nc/cpipe ch)
            pm (.newPromise ch)
            _ (.frameListener hh
                              (mg/h20Aggregator<> pm))
            ssl (c/cast? SslContext ctx)]
        (nc/setAKey c h2s-key pm)
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
                  (nc/closeCH cx)
                  (c/throwISE "unknown protocol: %s" pn)))))
          (.addLast pp "apn"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h1pipe
  "" ^ChannelHandler [ctx args]

  (log/debug "client:h1pipe: ssl ctx = %s" ctx)
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
;;
(defn- wspipe
  "" ^ChannelHandler [ctx cb user args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (log/debug "client: wspipe is ssl? = %s" (some? ctx))
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
        (.addLast "ws-agg" (mg/wsockAggregator<>))
        (.addLast "ws-user" (wsock-hdlr user))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- boot!
  "" [{:keys [maxContentSize maxInMemory
              version tempFileDir
              serverCert scheme
              threads rcvBuf options]
       :or {maxContentSize Integer/MAX_VALUE
            maxInMemory i/*membuf-limit*
            rcvBuf (* 2 c/MegaBytes)
            threads 0}
       :as args}]

  (let [tempFileDir (c/fpath (or tempFileDir
                                 i/*tempfile-repo*))
        ctx (maybeSSL serverCert scheme
                      (= version "2"))
        [g z] (nc/gAndC threads :tcpc)
        bs (Bootstrap.)
        options (or options
                    [[ChannelOption/SO_RCVBUF (int rcvBuf)]
                     [ChannelOption/SO_KEEPALIVE true]
                     [ChannelOption/TCP_NODELAY true]])]
    (nc/configDiskFiles true tempFileDir)
    (doseq [[k v] options] (.option bs k v))
    ;;assign generic attributes for all channels
    (.attr bs nc/chcfg-key args)
    (.attr bs
           nc/dfac-key
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
  "" [^Bootstrap bs host port ^Channel ch]

  (reify WSMsgWriter
    (write-ws-msg [_ msg]
      (some->>
        (cond
          (c/ist? WebSocketFrame msg)
          msg
          (string? msg)
          (TextWebSocketFrame. ^String msg)
          (m/instBytes? msg)
          (-> (.alloc ch)
              (.directBuffer (int 4096))
              (.writeBytes  ^bytes msg)
              (BinaryWebSocketFrame. )))
        (.writeAndFlush ch)))
    Disposable
    (dispose [_]
      (c/trye!
        nil
        (if (.isOpen ch)
          (doto ch
            (.writeAndFlush (CloseWebSocketFrame.))
            .close)
          (.. (.config ^Bootstrap bs)
              group shutdownGracefully))))
    ClientConnect
    (await-connect [_ ms] )
    (c-channel [_] ch)
    (remote-port [_] port)
    (remote-host [_] host)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wsconnCB
  "" [rcp bs host port]

  (fn [^ChannelFuture ff]
    (if (.isSuccess ff)
      (let [ch (.channel ff)
            cc (mkWSClient bs host port ch)]
        (nc/setAKey ch cc-key cc)
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
        ^H1DataFactory f (nc/getAKey c nc/dfac-key)]
    (nc/futureCB (.closeFuture c)
                 (fn [_]
                   (log/debug "shutdown: netty ws-client")
                   (some-> f .cleanAllHttpData)
                   (c/trye! nil (.. bs config group shutdownGracefully))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h2BootAndConn
  "" [host port args]

  (let [[^Bootstrap bs ctx] (boot! args)
        _ (.handler bs (h2pipe ctx args))
        ch (connect bs host port (some? ctx))
        ^H1DataFactory f (nc/getAKey ch nc/dfac-key)
        ^ChannelPromise pm (nc/getAKey ch h2s-key)]
    (nc/futureCB (.closeFuture ch)
                 (fn [_]
                   (log/debug "shutdown: netty h2-client")
                   (some-> f .cleanAllHttpData)
                   (c/trye! nil (.. bs config group shutdownGracefully))))
    (reify ClientConnect
      (await-connect [_ ms]
        (log/debug "client waits %s[ms] for h2 settings..... " ms)
        (if-not (.awaitUninterruptibly pm ms TimeUnit/MILLISECONDS)
          (c/throwISE "Timed out waiting for settings"))
        (if-not (.isSuccess pm)
          (throw (RuntimeException. (.cause pm))))
        (log/debug "client waited %s[ms] ok!" ms))
      (c-channel [_] ch)
      (remote-port [_] port)
      (remote-host [_] host)
      Disposable
      (dispose [_]
        (c/trye! nil
                 (if (.isOpen ch)
                   (.close ch)
                   (.. bs config group shutdownGracefully)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h1BootAndConn
  "" [host port args]

  (let [[^Bootstrap bs ctx] (boot! args)
        _ (.handler bs (h1pipe ctx args))
        ch (connect bs host port (some? ctx))
        ^H1DataFactory f (nc/getAKey ch nc/dfac-key)]
    (nc/futureCB (.closeFuture ch)
                 (fn [_]
                   (log/debug "shutdown: netty h1-client")
                   (some-> f .cleanAllHttpData)
                   (c/trye! nil (.. bs config group shutdownGracefully))))
    (reify ClientConnect
      (await-connect [_ ms] )
      (c-channel [_] ch)
      (remote-port [_] port)
      (remote-host [_] host)
      Disposable
      (dispose [_]
        (c/trye! nil
                 (if (.isOpen ch)
                   (.close ch)
                   (.. bs config group shutdownGracefully)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wsconnect<>
  ""

  ([host port uri cb]
   (wsconnect<> host port uri cb nil))

  ([host port uri cb args]
   (let [pfx (if (s/hgl? (:serverCert args)) "wss" "ws")
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
(defn h2connect<>
  ""

  ([host port] (h2connect<> host port nil))
  ([host port args]
   (h2BootAndConn host port (merge args {:version "2"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1connect<>
  ""

  ([host port] (h1connect<> host port nil))
  ([host port args]
   (h1BootAndConn host port (merge args {:version "1.1"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h2send* ""

  ([conn method uri data]
   (h2send* conn method uri data nil))

  ([conn method uri data args]
   (let [args (merge args
                     {:version "2"
                      :host (remote-host conn)})]
     (sendHttp (c-channel conn) method uri data args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1send* ""

  ([conn method uri data]
   (h1send* conn method uri data nil))

  ([conn method uri data args]
   (let [args (merge args
                     {:isKeepAlive? true
                      :host (remote-host conn)})]
     (sendHttp (c-channel conn) method uri data args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn hxsend
  "Gives back a promise" {:tag IDeref}

  ([target method data]
   (hxsend target method data nil))

  ([target method data args]
   (let [url (io/as-url target)
         h (.getHost url)
         p (.getPort url)
         {:keys [version]
          :as cargs}
         (merge args
                {:scheme (.getProtocol url)
                 :isKeepAlive? false
                 :host (.getHost url)})
         cc (if (= "2" version)
              (h2connect<> h p cargs)
              (h1connect<> h p cargs))]
     (await-connect cc 5000)
     (log/debug "about to send http request via ch: %s " (c-channel cc))
     (sendHttp (c-channel cc)
               method (.toURI url) data cargs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro h2send
  "Gives back a promise"

  ([target method data]
   `(h2send ~target ~method ~data nil))
  ([target method data args]
   `(hxsend ~target ~method ~data (merge ~args {:version "2"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro h1send
  "Gives back a promise"

  ([target method data] `(h1send ~target ~method ~data nil))
  ([target method data args] `(hxsend ~target ~method ~data ~args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro h1post
  "Gives back a promise"
  ([target data] `(h1post ~target ~data nil))
  ([target data args] `(hxsend ~target :post ~data ~args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro h1get
  "Gives back a promise"
  ([target] `(h1get ~target nil))
  ([target args] `(hxsend ~target :get nil ~args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro h2post
  "Gives back a promise"
  ([target data]
   `(h2post ~target ~data nil))
  ([target data args]
   `(hxsend ~target :post ~data (merge ~args {:version "2"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro h2get
  "Gives back a promise"
  ([target] `(h1get ~target nil))
  ([target args]
   `(hxsend ~target :get nil (merge ~args {:version "2"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

