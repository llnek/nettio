;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Netty servers."
      :author "Kenneth Leung"}

  czlab.convoy.nettio.server

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.convoy.nettio.aggregate]
        [czlab.convoy.nettio.http11]
        [czlab.convoy.nettio.core]
        [czlab.convoy.net.server]
        [czlab.convoy.net.routes]
        [czlab.basal.core]
        [czlab.basal.str]
        [czlab.basal.consts]
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
           [java.util ArrayList]
           [java.security KeyStore]
           [io.netty.bootstrap
            Bootstrap
            ServerBootstrap
            AbstractBootstrap]
           [czlab.convoy.nettio
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeCfgSSL
  ""
  ^SslContext
  [{:keys [serverKey passwd] :as args}]
  (when-some+
    [keyUrl (str serverKey)]
    (let
      [t (-> (TrustManagerFactory/getDefaultAlgorithm)
             TrustManagerFactory/getInstance)
       k (-> (KeyManagerFactory/getDefaultAlgorithm)
             KeyManagerFactory/getInstance)
       ^String kt (if (.endsWith
                        keyUrl ".jks") "JKS" "PKCS12")
       ks (KeyStore/getInstance kt)
       pms (doto (java.util.ArrayList.)
             (.add ApplicationProtocolNames/HTTP_2)
             (.add ApplicationProtocolNames/HTTP_1_1))
       cfg
       (ApplicationProtocolConfig.
         ApplicationProtocolConfig$Protocol/ALPN
         ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
         ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
         pms)
       pwd (some-> passwd str .toCharArray)
       ^SslProvider
       p (if (OpenSsl/isAlpnSupported)
           SslProvider/OPENSSL
           SslProvider/JDK)]
      (with-open [inp (-> (URL. keyUrl) .openStream)]
        (.load ks inp pwd)
        (.init t ks)
        (.init k ks pwd))
      (-> (SslContextBuilder/forServer k)
          (.ciphers Http2SecurityUtil/CIPHERS
                    SupportedCipherSuiteFilter/INSTANCE)
          (.trustManager t)
          (.sslProvider p)
          (.applicationProtocolConfig cfg)
          .build))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- newH2Builder<>

  ""
  ^H2Connector
  [h2handler]

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
(defn- upgradeCodecFac'

  ""
  [h2handler]

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

  "No h2 upgrade, install standard h1 pipeline"
  [h1 args]
  {:pre [(inst? ChannelHandler h1)]}

  (proxy [InboundAdapter][]
    (channelRead [ctx msg]
      (let
        [^ChannelHandlerContext ctx ctx
         pp (.pipeline ctx)
         cur (.handler ctx)]
        (when (inst? HttpMessage msg)
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
  "Maybe deal with possible http2 upgrade"
  [^Channel ch {:keys [h1 h2 h2h1] :as funcs} args]
  (let
    [dft (HttpServerCodec.)
     pp (.pipeline ch)]
    (.addLast pp (gczn HttpServerCodec) dft)
    (cond
      (inst? ChannelHandler h2)
      (do
        (log/debug "http2 handler provided, implement h2 upgrader")
        (->> (upgradeCodecFac h2)
             (HttpServerUpgradeHandler. dft)
             (.addLast pp (gczn HttpServerUpgradeHandler)))
        (->> ^ChannelHandler
             (onH1 h1 args)
             (.addLast pp "H1Only" )))
      (inst? ChannelHandler h1)
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
(defn- cfgSSLXXX

  ""
  [^ChannelPipeline pp h1 args]
  {:pre [(inst? ChannelHandler h1)]}

  (doto pp
    (.addLast (gczn HttpServerCodec) (HttpServerCodec.))
    (.addLast "HOA" obj-agg)
    (.addLast "H1RH" req-hdr)
    (.addLast "CWH" (ChunkedWriteHandler.))
    (.addLast user-handler-id ^ChannelHandler h1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- onSSL

  ""
  [^ChannelPipeline cpp {:keys [h1 h2]} args]

  (if (inst? ChannelHandler h2)
    (->>
      (proxy [SSLNegotiator][]
        (configurePipeline [ctx pn]
          (let [pp (cpipe ctx)]
            (cond
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_1_1 ^String pn)
              (cfgSSLXXX pp h1 args)
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_2 ^String pn)
                (.addLast pp
                          (gczn H2Connector) (newH2Builder<> h2))
              :else
              (trap! IllegalStateException
                     (str "unknown protocol: " pn))))))
      (.addLast cpp (gczn SSLNegotiator)))
    (cfgSSLXXX cpp h1 args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn chanInitor<>

  ""
  ^ChannelHandler
  [deco args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (cast? Channel ch)
            funcs (deco args)
            pp (.pipeline ch)]
        (if-some
          [ssl (maybeCfgSSL args)]
          (do
            (->> (.newHandler ssl (.alloc ch))
                 (.addLast pp "ssl"))
            (onSSL pp funcs args))
          (onH1Proto ch funcs args))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;{:keys [a] {:keys []} :b} - destruct nested
;;
(defmethod createServer<>

  :netty/http
  [stype & cargs]

  (let
    [{:keys [threads routes rcvBuf backlog
             sharedGroup? tempFileDir
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
     (second cargs)
     p1 (first cargs)
     ci
     (cond
       (inst? ChannelInitializer p1) p1
       (fn? p1) (chanInitor<> p1 args)
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod createServer<>

  :netty/udp
  [stype & cargs]

  (let
    [{:keys [maxMsgsPerRead threads rcvBuf options]
      :or {maxMsgsPerRead Integer/MAX_VALUE
           rcvBuf (* 2 MegaBytes)
           threads 0}
      :as args}
     (second cargs)
     p1 (first cargs)
     ci
     (cond
       (inst? ChannelInitializer p1) p1
       (fn? p1) (chanInitor<> p1 args)
       :else (trap! ClassCastException "wrong input"))
     [g z] (gAndC threads :udps)
     bs (Bootstrap.)
     options (or options
                 [[ChannelOption/MAX_MESSAGES_PER_READ maxMsgsPerRead]
                  [ChannelOption/SO_RCVBUF (int rcvBuf)]
                  [ChannelOption/SO_BROADCAST true]
                  [ChannelOption/TCP_NODELAY true]])]
    (doseq [[k v] options] (.option bs k v))
    (doto bs
      (.channel z)
      (.group g)
      (.handler ^ChannelHandler ci))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- startsvr

  ""
  ^Channel
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod startServer

  ServerBootstrap
  [bs {:keys [host
              port]
       :or {port 80}}]
  {:pre [(number? port)]} (startsvr bs host port))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod startServer

  Bootstrap
  [bs {:keys [host
              port]
       :or {port 4444}}]
  {:pre [(number? port)]} (startsvr bs host port))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod stopServer

  Channel
  [^Channel ch]
  (if (and ch (.isOpen ch)) (.close ch)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

