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
            Http2FrameAdapter
            Http2SecurityUtil
            Http2FrameLogger
            Http2CodecUtil
            Http2HeadersEncoder
            Http2HeadersDecoder
            DefaultHttp2ConnectionEncoder
            DefaultHttp2ConnectionDecoder
            DefaultHttp2HeadersEncoder
            DefaultHttp2HeadersDecoder
            DefaultHttp2FrameWriter
            DefaultHttp2FrameReader
            Http2ServerUpgradeCodec
            Http2ConnectionDecoder
            Http2ConnectionEncoder
            Http2OutboundFrameLogger
            Http2InboundFrameLogger
            Http2ConnectionHandler
            Http2Settings
            DefaultHttp2Connection
            HttpToHttp2ConnectionHandlerBuilder
            InboundHttp2ToHttpAdapter
            InboundHttp2ToHttpAdapterBuilder
            AbstractHttp2ConnectionHandlerBuilder]
           [io.netty.channel.epoll Epoll]
           [io.netty.handler.ssl.util SelfSignedCertificate]
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
           [czlab.jasal DataError LifeCycle]
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
            InboundHandler
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- buildCtx
  "" ^SslContextBuilder [^String skey pwd]

  (if (= "selfsignedcert" skey)
    (let [ssc (SelfSignedCertificate.)]
      (SslContextBuilder/forServer (.certificate ssc)
                                   (.privateKey ssc)))
    (let [t (TrustManagerFactory/getInstance (tmfda))
          k (KeyManagerFactory/getInstance (kmfda))
          cpwd (some-> pwd charsit)
          ks (KeyStore/getInstance
               ^String
               (if (.endsWith skey ".jks") "JKS" "PKCS12"))
          _ (with-open
              [inp (.openStream (URL. skey))]
              (.load ks inp cpwd)
              (.init t ks)
              (.init k ks cpwd))]
      (-> (SslContextBuilder/forServer k) (.trustManager t)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeCfgSSL1
  "" ^SslContext [skey pwd] (-> (buildCtx skey pwd) .build))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeCfgSSL2
  "" ^SslContext [skey pwd]
  (let
    [cfg
     (ApplicationProtocolConfig.
       ApplicationProtocolConfig$Protocol/ALPN
       ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
       ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
       (doto (java.util.ArrayList.)
           (.add ApplicationProtocolNames/HTTP_2)
           (.add ApplicationProtocolNames/HTTP_1_1)))
     ctx (buildCtx skey pwd)
     ^SslProvider
     p (if (and true (OpenSsl/isAlpnSupported))
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- buildH2
  ""
  ^ChannelHandler
  [^Http2FrameListener h2 args]

  (let [conn (->> Http2CodecUtil/SMALLEST_MAX_CONCURRENT_STREAMS
                  (DefaultHttp2Connection. true))
        ss (->> Http2CodecUtil/DEFAULT_HEADER_LIST_SIZE
                (.maxHeaderListSize (Http2Settings.)))
        mhls (.maxHeaderListSize ss)
        log (Http2FrameLogger. LogLevel/INFO "h2logger")
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
    (.gracefulShutdownTimeoutMillis handler 30000)
    handler))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cfgH2
  "" [^ChannelPipeline pp h2 args]
  (let
    [u
     (cond
       (ist? Http2FrameListener h2) h2
       (fn? h2)
       (proxy [Http2FrameAdapter][]
         (onDataRead [ctx sid data padding end?]
           (h2 ctx sid data padding end?))
         (onHeadersRead [ctx sid headers padding end?]
           (h2 ctx sid headers padding end?)))
       :else
       (trap! DataError "Bad handler type"))]
    (doto pp
      (.addLast user-handler-id (buildH2 u args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cfgH1 "" [^ChannelPipeline pp h1 args]

  (let
    [^ChannelHandler
     u
     (cond
       (ist? ChannelHandler h1) h1
       (fn? h1)
       (proxy [InboundHandler][]
         (channelRead0 [ctx msg]
           (h1 ctx msg)))
       :else
       (trap! DataError "bad handler type"))]
    (doto pp
      (.addLast "SC" (HttpServerCodec.))
      (.addLast "OA" obj-agg)
      (.addLast "RH" req-hdr)
      (.addLast "CW" (ChunkedWriteHandler.))
      (.addLast user-handler-id u))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- onSSL
  "" [^ChannelPipeline cpp h1 h2 args]

  (cond
    (and (some? h1)
         (some? h2))
    (->>
      (proxy [SSLNegotiator][]
        (configurePipeline [ctx n]
          (let [pp (cpipe ctx)
                ^String pn n]
            (cond
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_1_1 pn)
              (cfgH1 pp h1 args)
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_2 pn)
              (cfgH2 pp h2 args)
              :else
              (trap! IllegalStateException
                     (str "unknown protocol: " pn))))))
      (.addLast cpp (gczn SSLNegotiator)))
    (some? h2)
    (cfgH2 cpp h2 args)
    (some? h1)
    (cfgH1 cpp h1 args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn udpInitor<>
  "" ^ChannelHandler [hu args]
  {:pre [(ist? ChannelHandler hu)]}
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (cast? Channel ch)
            pp (.pipeline ch)]
        (.addLast pp
                  user-handler-id
                  ^ChannelHandler hu)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn tcpInitor<>
  "" ^ChannelHandler [^SslContext ctx hh1 hh2 args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (cast? Channel ch)
            pp (.pipeline ch)]
        (if (some? ctx)
          (do (->> (.newHandler ctx
                                (.alloc ch))
                   (.addLast pp "ssl"))
              (onSSL pp hh1 hh2 args))
          (cfgH1 pp hh1 args))))))

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
      [{:keys [threads routes rcvBuf backlog
               sharedGroup? tempFileDir
               ciz hh1 hh2
               serverKey passwd
               maxContentSize maxInMemory]
        :or {maxContentSize Integer/MAX_VALUE
             maxInMemory *membuf-limit*
             rcvBuf (* 2 MegaBytes)
             backlog KiloBytes
             sharedGroup? true
             threads 0 routes nil}
        {:keys [server child]}
        :options }
       carg
       ctx (if (some? hh2)
             (maybeCfgSSL2 serverKey passwd)
             (maybeCfgSSL1 serverKey passwd))
       ci
       (cond
         (ist? ChannelInitializer ciz) ciz
         (or hh1 hh2) (tcpInitor<> ctx hh1 hh2 carg)
         :else (trap! ClassCastException "wrong input"))
       tempFileDir (fpath (or tempFileDir
                              *tempfile-repo*))
       args (dissoc carg :routes :options)
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
      [{:keys [maxMsgsPerRead threads
               ciz hu
               rcvBuf options]
        :or {maxMsgsPerRead Integer/MAX_VALUE
             rcvBuf (* 2 MegaBytes)
             threads 0}}
       carg
       ci
       (cond
         (ist? ChannelInitializer ciz) ciz
         (some? hu) (udpInitor<> hu carg)
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
   (do-with [w (mutable<> NettyUdpServer)]
            (if (some? carg)
              (.init w carg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn nettyWebServer<> "" {:tag LifeCycle}
  ([] (nettyWebServer<> nil))
  ([carg]
   (do-with [w (mutable<> NettyWebServer)]
            (if (some? carg)
              (.init w carg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

