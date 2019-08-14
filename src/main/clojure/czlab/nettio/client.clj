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
            [czlab.convoy.core :as cc :refer [ws-write-msg]]
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
(def ^:private ^AttributeKey h2s-key  (nc/akey<> "h2settings-promise"))
(def ^:private ^AttributeKey rsp-key  (nc/akey<> "rsp-result"))
(def ^:private ^AttributeKey cf-key  (nc/akey<> "wsock-future"))
(def ^:private ^AttributeKey cc-key  (nc/akey<> "wsock-client"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;Aggregates all chunks into a full message.
(def ^{:private true :tag ChannelHandler}
  msg-agg
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg] (mg/agg-h1-read ctx msg false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- nobs! [^Channel ch ^Bootstrap bs]
  (c/try! (if (and ch (.isOpen ch)) (.close ch)))
  (c/try! (.. bs config group shutdownGracefully)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cconn<>
  ([module bs ch host port]
   (cconn<> module bs ch host port nil))
  ([module bs ch host port {:keys [awaitc finz]}]
   (reify cc/ClientConnect
     (cc-await-connect [_ ms]
       (if awaitc (awaitc ms)))
     (cc-module [_] module)
     (cc-channel [_] ch)
     (cc-remote-port [_] port)
     (cc-remote-host [_] host)
     (cc-finz [_]
       (if finz (finz) (nobs! ch bs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-ctx
  ^SslContextBuilder [scert]
  (let [ctx (SslContextBuilder/forClient)]
    (if (= "*" scert)
      (.trustManager ctx InsecureTrustManagerFactory/INSTANCE)
      (let [#^"[Ljava.security.cert.X509Certificate;"
            cs (->> (nc/conv-certs (io/as-url scert))
                    (c/vargs X509Certificate))] (.trustManager ctx cs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-ssl
  ^SslContext [scert scheme h2?]
  (when (and (s/hgl? scert)
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
  ^Channel [^Bootstrap bs host port ssl?]
  (let
    [port (if (neg? port) (if ssl? 443 80) port)
     _ (l/debug "connect to: %s@%s." host port)
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
(def ^{:private true
       :tag ChannelHandler}
  user-hdlr
  (proxy [InboundHandler][true]
    (readMsg [ctx msg]
      (when-some
        [p (nc/get-akey ctx rsp-key)]
        (deliver p msg)
        (nc/del-akey ctx rsp-key)))
    (exceptionCaught [ctx err]
      (try (when-some
             [p (nc/get-akey ctx rsp-key)]
             (deliver p err)
             (nc/del-akey ctx rsp-key))
           (finally (nc/close-ch ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsock-hdlr
  ^ChannelHandler [user]
  (proxy [InboundHandler][true]
    (exceptionCaught [ctx err] (nc/close-ch ctx))
    (readMsg [ctx msg] (user (nc/get-akey ctx cc-key) msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsh<>
  ^ChannelHandler
  [^WebSocketClientHandshaker handshaker cb args]
  (proxy [InboundHandler][true]
    (handlerAdded [ctx]
      (let [p (.newPromise ^ChannelHandlerContext ctx)]
        (nc/set-akey ctx cf-key p)
        (.addListener p (nc/cfop<> cb))
        (l/debug "wsc handler-added.")))
    (channelActive [ctx]
      (l/debug "wsc handshaker start hand-shake.")
      (.handshake handshaker (nc/ch?? ctx)))
    (exceptionCaught [ctx err]
      (if-some [^ChannelPromise
                f (nc/get-akey ctx cf-key)]
        (if-not (.isDone f)
          (.setFailure f ^Throwable err)))
      (nc/close-ch ctx)
      (l/warn "%s." (.getMessage ^Throwable err)))
    (readMsg [ctx msg]
      (let [ch (nc/ch?? ctx)
            ^ChannelPromise f (nc/get-akey ctx cf-key)]
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
  ^ChannelHandler [ctx args]
  (l/debug "client:h2pipe: ssl ctx = %s." ctx)
  (proxy [ChannelInitializer][]
    (initChannel [c]
      (let [hh (HttpToHttp2ConnectionHandlerBuilder.)
            ssl (c/cast? SslContext ctx)
            ch (nc/ch?? c)
            pp (nc/cpipe ch)
            pm (.newPromise ch)]
        (nc/set-akey c h2s-key pm)
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
                        (doto (nc/cpipe cx)
                          (.addLast "codec" (.build hh))
                          (.addLast "cw" (ChunkedWriteHandler.))
                          (.addLast "user-cb" user-hdlr))
                        (do (nc/close-ch cx)
                            (u/throw-ISE "Unknown protocol: %s." pn))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h1pipe
  ^ChannelHandler [ctx args]
  (l/debug "client:h1pipe: ssl ctx = %s." ctx)
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (if-some
        [ssl (c/cast? SslContext ctx)]
        (.addLast (nc/cpipe ch)
                  "ssl"
                  (.newHandler ssl
                               (.alloc ^Channel ch))))
      (doto (nc/cpipe ch)
        (.addLast "codec" (HttpClientCodec.))
        (.addLast "msg-agg" msg-agg)
        (.addLast "cw" (ChunkedWriteHandler.))
        (.addLast "user-cb" user-hdlr)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wspipe
  ^ChannelHandler [ctx cb user args]
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (l/debug "client: wspipe is ssl? = %s." (some? ctx))
      (if-some
        [ssl (c/cast? SslContext ctx)]
        (.addLast (nc/cpipe ch)
                  "ssl"
                  (.newHandler ssl
                               (.alloc ^Channel ch))))
      (doto (nc/cpipe ch)
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
        (.addLast "ws-user" (wsock-hdlr user))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- boot!
  [{:keys [max-content-size max-in-memory
           version temp-dir
           server-cert scheme
           threads rcv-buf options]
    :as args
    :or {max-in-memory i/*membuf-limit*
         rcv-buf (* 2 c/MegaBytes)
         threads 0
         max-content-size Integer/MAX_VALUE}}]

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
(defn- mk-ws-cli
  [module bs ^Channel ch host port]
  (cconn<> module bs ch host port
           {:finz (c/fn_0
                    (c/try!
                      (if (.isOpen ch)
                        (.writeAndFlush ch (CloseWebSocketFrame.))))
                    (nobs! ch bs))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsconn-cb
  [module rcp bs host port]

  (fn [^ChannelFuture ff]
    (if (.isSuccess ff)
      (let [ch (.channel ff)
            cc (mk-ws-cli module bs ch host port)]
        (deliver rcp cc)
        (nc/set-akey ch cc-key cc))
      (deliver rcp (or (.cause ff)
                       (Exception. "Conn error!"))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyClientModule []
  cc/HttpClientModule
  (hc-send-http [_ conn op uri data args]
    (let [mt (HttpMethod/valueOf (s/ucase (name op)))
          ^Channel ch (cc/cc-channel conn)
          ^URI uri uri
          {:keys [encoding headers version
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
                     :else (u/throw-IOE "Bad type %s." (class body)))
          req (if-not (or (nil? body)
                          (c/is? ByteBuf body))
                (nc/http-req<> mt uriStr)
                (nc/http-req<+> mt uriStr body))]
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
                       (if-not is-keep-alive?
                         HttpHeaderValues/CLOSE
                         HttpHeaderValues/KEEP_ALIVE)))
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
      (cconn<> module bs ch host port
               {:awaitc
                (fn [ms]
                 (l/debug "client waits %s[ms] for h2 settings....." ms)
                 (if-not (.awaitUninterruptibly pm
                                                ms
                                                TimeUnit/MILLISECONDS)
                   (u/throw-ISE "Timed out waiting for settings."))
                 (if-not (.isSuccess pm)
                   (c/trap! RuntimeException (.cause pm)))
                 (l/debug "client waited %s[ms] ok!" ms))})))
  (hc-h1-conn [module host port args]
    (let [[^Bootstrap bs ctx] (boot! args)
          _ (.handler bs (h1pipe ctx args))
          ch (connect bs host port (some? ctx))
          ^H1DataFactory f (nc/get-akey ch nc/dfac-key)]
      (nc/future-cb (.closeFuture ch)
                    (c/fn_1 (l/debug "shutdown: netty h1-client.")
                            (some-> f .cleanAllHttpData)
                            (c/try! (.. bs config group shutdownGracefully))))
      (cconn<> module bs ch host port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod ws-write-msg NettyClientModule [conn msg]
  (let [^Channel ch (cc/cc-channel conn)]
    (some->> (cond
               (c/is? WebSocketFrame msg)
               msg
               (string? msg)
               (TextWebSocketFrame. ^String msg)
               (bytes? msg)
               (-> (.alloc ch)
                   (.directBuffer (int 4096))
                   (.writeBytes  ^bytes msg)
                   (BinaryWebSocketFrame. )))
             (.writeAndFlush ch))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2send
  "Gives back a promise"
  ([target method data]
   (h2send target method data nil))
  ([target method data args]
   (cc/hxsend (NettyClientModule.)
              target method data (assoc args :version "2"))))

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
              target :post data (assoc args :version "2"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2get
  "Gives back a promise"
  ([target] (h2get target nil))
  ([target args]
   (cc/hxsend (NettyClientModule.)
              target :get nil (assoc args :version "2"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

