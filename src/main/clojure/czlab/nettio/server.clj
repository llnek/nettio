;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Netty servers."
      :author "Kenneth Leung"}

  czlab.nettio.server

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.nettio.aggh11]
        [czlab.nettio.http11]
        [czlab.nettio.core]
        [czlab.convoy.routes]
        [czlab.basal.core]
        [czlab.basal.str]
        [czlab.basal.io])

  (:import [javax.net.ssl KeyManagerFactory TrustManagerFactory]
           [io.netty.handler.logging LogLevel LoggingHandler]
           [io.netty.util ReferenceCountUtil AsciiString]
           [io.netty.handler.stream ChunkedWriteHandler]
           [java.net URL InetAddress InetSocketAddress]
           [io.netty.handler.codec.http
            HttpServerUpgradeHandler
            HttpServerCodec
            HttpServerUpgradeHandler$UpgradeCodec
            HttpServerUpgradeHandler$UpgradeCodecFactory]
           [io.netty.handler.codec.http2
            Http2FrameListener
            Http2SecurityUtil
            Http2CodecUtil
            Http2ServerUpgradeCodec
            Http2ConnectionDecoder
            Http2ConnectionEncoder
            Http2Settings
            DefaultHttp2Connection
            HttpToHttp2ConnectionHandlerBuilder
            InboundHttp2ToHttpAdapter
            InboundHttp2ToHttpAdapterBuilder
            AbstractHttp2ConnectionHandlerBuilder]
           [io.netty.channel.epoll Epoll]
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
           [io.netty.handler.codec.http HttpMessage]
           [czlab.jasal LifeCycle]
           [java.util ArrayList]
           [java.security KeyStore]
           [io.netty.bootstrap
            Bootstrap
            ServerBootstrap
            AbstractBootstrap]
           [czlab.nettio
            H2Connector
            H2Builder
            H1DataFactory
            InboundAdapter
            SSLNegotiator]
           [io.netty.channel
            ChannelInitializer
            ChannelPipeline
            ChannelOption
            ChannelFuture
            EventLoopGroup
            Channel
            ChannelHandler
            ChannelHandlerContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

(def ^:private ^ChannelHandler obj-agg (h1reqAggregator<>))
(def ^:private ^ChannelHandler req-hdr (h1reqHandler<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private tmfda "" [] `(TrustManagerFactory/getDefaultAlgorithm))
(defmacro ^:private kmfda "" [] `(KeyManagerFactory/getDefaultAlgorithm))
(defmacro ^:private maybeCfgSSL
  "" [s p] `(let [s# ~s] (if (hgl? s#) (cfgSSL?? s# ~p))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cfgSSL??
  "" ^SslContext [serverKey passwd]
  (let
    [ctx
     (if (= "selfsignedcert" serverKey)
       (let [ssc (SelfSignedCertificate.)]
         (SslContextBuilder/forServer (.certificate ssc)
                                      (.privateKey ssc)))
       (let [t (TrustManagerFactory/getInstance tmfda)
             k (KeyManagerFactory/getInstance kmfda)
             pwd (some-> passwd charsit)
             ks (-> ^String
                    (if (.endsWith
                          serverKey ".jks")
                      "JKS" "PKCS12")
                 KeyStore/getInstance)
             _ (with-open
                 [inp (-> (URL. serverKey)
                       .openStream)]
                 (.load ks inp pwd)
                 (.init t ks)
                 (.init k ks pwd))]
         (-> (SslContextBuilder/forServer k)
             (.trustManager t))))
     pms (doto (java.util.ArrayList.)
           (.add ApplicationProtocolNames/HTTP_2)
           (.add ApplicationProtocolNames/HTTP_1_1))
     cfg
     (ApplicationProtocolConfig.
       ApplicationProtocolConfig$Protocol/ALPN
       ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
       ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
       pms)
     ^SslProvider
     p (if (OpenSsl/isAlpnSupported)
         SslProvider/OPENSSL SslProvider/JDK)]
    (->
      (.ciphers ctx
                Http2SecurityUtil/CIPHERS
                SupportedCipherSuiteFilter/INSTANCE)
      (.sslProvider p)
      (.applicationProtocolConfig cfg)
      (.build))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- newH2Builder<>
  "" ^H2Connector [h2handler]

  (-> (proxy [H2Builder][]
        (build [dc ec ss]
          (doto->>
            (H2Connector.
              ^Http2ConnectionDecoder dc
              ^Http2ConnectionEncoder ec
              ^Http2Settings ss)
            (.frameListener ^H2Builder this))))
      .build))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- upgradeCodecFac' "" [h2handler]
  (reify
    HttpServerUpgradeHandler$UpgradeCodecFactory
    (newUpgradeCodec [_ pn]
      (if (-> Http2CodecUtil/HTTP_UPGRADE_PROTOCOL_NAME
              (AsciiString/contentEquals pn))
        (-> (newH2Builder<> h2handler)
            Http2ServerUpgradeCodec.)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private upgradeCodecFac (memoize upgradeCodecFac'))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- onH1'
  "No h2 upgrade, use standard h1 pipeline"
  [h1 args] {:pre [(ist? ChannelHandler h1)]}

  (proxy [InboundAdapter][]
    (channelRead [ctx msg]
      (let
        [^ChannelHandlerContext ctx ctx
         pp (.pipeline ctx)
         cur (.handler ctx)]
        (when (ist? HttpMessage msg)
          (.replace pp cur "HOA" obj-agg)
          (.addAfter pp "HOA" "H1RH" req-hdr)
          (.addAfter pp "H1RH" "CWH" (ChunkedWriteHandler.))
          (.addAfter pp "CWH" user-handler-id ^ChannelHandler h1)
          (log/debug "removing h2-upgrader")
          (.remove pp HttpServerUpgradeHandler))
        (.fireChannelRead ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private onH1 (memoize onH1'))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- onH1Proto
  "Deal with possible http2 upgrade?"
  [^Channel ch {:keys [h1 h2 h2h1] :as funcs} args]

  (let
    [dft (HttpServerCodec.)
     pp (.pipeline ch)]
    (.addLast pp (gczn HttpServerCodec) dft)
    (cond
      (ist? ChannelHandler h2)
      (do
        (log/debug "http2 handler provided, implement h2 upgrader")
        (->> (upgradeCodecFac h2)
             (HttpServerUpgradeHandler. dft)
             (.addLast pp (gczn HttpServerUpgradeHandler)))
        (->> ^ChannelHandler
             (onH1 h1 args)
             (.addLast pp "H1Only" )))
      (ist? ChannelHandler h1)
      (do
        (log/debug "standard http1 pipeline only")
        (.addLast pp "HOA" obj-agg)
        (.addLast pp "H1RH" req-hdr)
        (.addLast pp "CWH" (ChunkedWriteHandler.))
        (.addLast pp user-handler-id ^ChannelHandler h1))
      :else
      (trap! ClassCastException (format "wrong input %s" funcs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;not-used!
(defn- newH2H1
  "Handle http2 via http1 adapter"
  [^ChannelHandlerContext ctx
   {:keys [h2h1]}
   {:keys [maxContentSize]
    :or {maxContentSize (* 64 MegaBytes)} :as args}]

  (let
    [conn (DefaultHttp2Connection. true)
     pp (.pipeline ctx)
     ln (->
          (doto
            (InboundHttp2ToHttpAdapterBuilder. conn)
            (.maxContentLength (int maxContentSize))
            (.propagateSettings true)
            (.validateHttpHeaders false))
          .build)]
    (doto pp
      (.addLast "H1H2CH"
                (-> (HttpToHttp2ConnectionHandlerBuilder.)
                    (.frameListener ln)
                    (.connection conn)
                    .build))
      (.addLast user-handler-id ^ChannelHandler h2h1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- buildH2 "" [args]
  (let [conn (->> Http2CodecUtil/DEFAULT_MAX_RESERVED_STREAMS
                  (DefaultHttp2Connection. true))
        ss (->> Http2CodecUtil/DEFAULT_HEADER_LIST_SIZE
                (.maxHeaderListSize (Http2Settings.)))
        mhls (.maxHeaderListSize ss)
        log (Http2FrameLogger. INFO "")
        rdr (-> (DefaultHttp2FrameReader.
                  (DefaultHttp2HeadersDecoder. true mhls))
                (Http2InboundFrameLogger. log))
        wtr (-> (DefaultHttp2FrameWriter.
                  Http2HeadersEncoder/NEVER_SENSITIVE)
                (Http2OutboundFrameLogger. log))
        ec (DefaultHttp2ConnectionEncoder. conn wtr)
        dc (doto
             (DefaultHttp2ConnectionDecoder. conn ec rdr)
             (.frameListener h2))
        handler
        (proxy [Http2ConnectionHandler][dc ec ss])]
    (.gracefulShutdownTimeoutMillis handler
                                    DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS)
    handler))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cfgH2
  "" [pp h2 args]

  (doto ^ChannelPipeline pp
    (.addLast (gczn HttpServerCodec) (HttpServerCodec.))
    (.addLast "HOA" obj-agg)
    (.addLast "H1RH" req-hdr)
    (.addLast "CWH" (ChunkedWriteHandler.))
    (.addLast user-handler-id ^ChannelHandler h1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cfgH1
  "" [pp h1 args]
  {:pre [(ist? ChannelHandler h1)]}

  (doto ^ChannelPipeline pp
    (.addLast (gczn HttpServerCodec) (HttpServerCodec.))
    (.addLast "HOA" obj-agg)
    (.addLast "H1RH" req-hdr)
    (.addLast "CWH" (ChunkedWriteHandler.))
    (.addLast user-handler-id ^ChannelHandler h1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- onSSL
  "" [^ChannelPipeline cpp {:keys [h1 h2]} args]

  (cond
    (and h1 h2)
    (->>
      (proxy [SSLNegotiator][]
        (configurePipeline [ctx n]
          (let [pp (cpipe ctx)
                ^String pn n]
            (cond
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_1_1 pn)
              (cfgh1 pp h1 args)
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_2 pn)
              (cfgh2 pp h2 args)
              ;;(.addLast pp (gczn H2Connector) (newH2Builder<> h2))
              :else
              (trap! IllegalStateException
                     (str "unknown protocol: " pn))))))
      (.addLast cpp (gczn SSLNegotiator)))
    (some? h2)
    (some? h1)
    (cfgh1 cpp h1 args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn udpInitor<>
  "" ^ChannelHandler [deco args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (cast? Channel ch)
            funcs (deco args)
            pp (.pipeline ch)]
        (onH1Proto ch funcs args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn tcpInitor<>
  "" ^ChannelHandler [deco sslCtx args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (cast? Channel ch)
            funcs (deco args)
            pp (.pipeline ch)]
        (if (some? sslCtx)
          (do (->> (.newHandler sslCtxl
                                (.alloc ch))
                   (.addLast pp "ssl"))
              (onSSL pp funcs args))
          (onH1Proto ch funcs args))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- start-svr
  "" ^Channel
  [^AbstractBootstrap bs host port]

  (let [sbs (cast? ServerBootstrap bs)
        ^H1DataFactory
        dfac (some-> sbs
                     .config
                     .childAttrs
                     (.get dfac-key))
        ip (if (hgl? host)
             (InetAddress/getByName host)
             (InetAddress/getLocalHost))
        ch (-> (.bind bs ip (int port))
               .sync
               .channel)
        cf (.closeFuture ch)]
    (log/debug "netty-svr running on host %s:%s" ip port)
    (futureCB cf
              (fn [_]
                (log/debug "shutdown: server bootstrap@ip %s" ip)
                (try!
                  (some-> dfac .cleanAllHttpData)
                  (some-> sbs
                          .config
                          .childGroup
                          .shutdownGracefully)
                  (.. bs config group shutdownGracefully))))
    ch))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- finz-ch [^Channel ch]
  (try!
    (if (and ch
             (.isOpen ch)) (.close ch))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(decl-mutable NettyWebServer
  LifeCycle
  (init [me carg]
    (let
      [carg (if (fn? carg) {:ifunc carg} carg)
       {:keys [threads routes rcvBuf backlog
               sharedGroup? tempFileDir
               serverKey passwd
               maxContentSize maxInMemory]
        :or {maxContentSize Integer/MAX_VALUE
             maxInMemory *membuf-limit*
             rcvBuf (* 2 MegaBytes)
             backlog KiloBytes
             sharedGroup? true
             threads 0 routes nil}
        {:keys [server child]}
        :options
        :as args}
       (dissoc carg :ifunc)
       p1 (:ifunc carg)
       ctx (maybeCfgSSL
             serverKey passwd)
       ci
       (cond
         (ist? ChannelInitializer p1) p1
         (fn? p1) (tcpInitor<> p1 ctx args)
         :else (trap! ClassCastException "wrong input"))
       tempFileDir (fpath (or tempFileDir
                              *tempfile-repo*))
       args (dissoc args :routes :options)
       [g z] (gAndC threads :tcps)
       bs (ServerBootstrap.)
       server (or server
                  [[ChannelOption/SO_BACKLOG (int backlog)]
                   [ChannelOption/SO_REUSEADDR true]])
       child (or child
                 [[ChannelOption/SO_RCVBUF (int rcvBuf)]
                  [ChannelOption/TCP_NODELAY true]])]
      (doseq [[k v] child] (.childOption bs k v))
      (doseq [[k v] server] (.option bs k v))
      ;;threads=zero tells netty to use default, which is
      ;;2*num_of_processors
      (setf! me :bootstrap bs)
      (doto bs
        (.handler (LoggingHandler. LogLevel/INFO))
        (.childHandler ^ChannelHandler ci)
        (.channel z))
      (if-not sharedGroup?
        (.group bs
                ^EventLoopGroup g
                ^EventLoopGroup (first (gAndC threads :tcps)))
        (.group bs ^EventLoopGroup g))
      ;;assign generic attributes for all channels
      (.childAttr bs dfac-key (H1DataFactory. (int maxInMemory)))
      (.childAttr bs chcfg-key args)
      ;; routes to check?
      (when-not (empty? routes)
        (log/info "routes provided - creating routes cracker")
        (->> (routeCracker<> routes)
             (.childAttr bs routes-key)))
      (log/info "netty server bootstraped with [%s]"
                (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
      (configDiskFiles true tempFileDir)
      bs))

  (start [me] (.start me nil))
  (start [me arg]
    (let [bs (:bootstrap @me)
          {:keys [host port]
           :or {port 80}} arg]
      (assert (number? port))
      (setf! me
             :channel
             (start-svr bs host port))))

  (stop [me]
    (finz-ch (:channel @me)))

  (dispose [me] (wipe! me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(decl-mutable NettyUdpServer
  LifeCycle
  (init [me carg]
    (let
      [carg (if (fn? carg) {:ifunc carg} carg)
       {:keys [maxMsgsPerRead threads rcvBuf options]
        :or {maxMsgsPerRead Integer/MAX_VALUE
             rcvBuf (* 2 MegaBytes)
             threads 0}
        :as args}
       (dissoc carg :ifunc)
       p1 (:ifunc carg)
       ci
       (cond
         (ist? ChannelInitializer p1) p1
         (fn? p1) (udpInitor<> p1 args)
         :else (trap! ClassCastException "wrong input"))
       [g z] (gAndC threads :udps)
       bs (Bootstrap.)
       options (or options
                   [[ChannelOption/MAX_MESSAGES_PER_READ maxMsgsPerRead]
                    [ChannelOption/SO_RCVBUF (int rcvBuf)]
                   [ChannelOption/SO_BROADCAST true]
                   [ChannelOption/TCP_NODELAY true]])]
      (doseq [[k v] options] (.option bs k v))
      (setf! me :bootstrap bs)
      (doto bs
        (.channel z)
        (.group g)
        (.handler ^ChannelHandler ci))))

  (start [me] (.start me nil))
  (start [me arg]
    (let [bs (:bootstrap @me)
          {:keys [host port]
           :or {port 4444}}
          arg]
      (assert (number? port))
      (setf! me
             :channel
             (start-svr bs host port))))

  (stop [me]
    (finz-ch (:channel @me)))

  (dispose [me] (wipe! me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn nettyUdpServer<> "" {:tag LifeCycle}
  ([] (nettyUdpServer<> nil))
  ([carg]
   (let [w (mutable<> NettyUdpServer)]
     (if (some? carg)
       (.init w carg))
     w)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn nettyWebServer<> "" {:tag LifeCycle}
  ([] (nettyWebServer<> nil))
  ([carg]
   (let [w (mutable<> NettyWebServer)]
     (if (some? carg)
       (.init w carg))
     w)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

