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

  (:require [czlab.nettio.http11 :as h1]
            [czlab.convoy.routes :as cr]
            [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.nettio.msgs :as mg]
            [czlab.nettio.core :as nc])

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
            H1DataFactory
            H2ConnBuilder
            InboundHandler]
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
(def ^:private ^ChannelHandler obj-agg (mg/h1reqAggregator<>))
(def ^:private ^ChannelHandler req-hdr (h1/h1reqHandler<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private tmfda "" [] `(TrustManagerFactory/getDefaultAlgorithm))
(defmacro ^:private kmfda "" [] `(KeyManagerFactory/getDefaultAlgorithm))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- buildCtx
  "" ^SslContextBuilder [^String skey pwd]

  (cond
    (s/nichts? skey)
    nil
    (= "*" skey)
    (let [ssc (SelfSignedCertificate.)]
      (SslContextBuilder/forServer (.certificate ssc)
                                   (.privateKey ssc)))
    :else
    (let [t (TrustManagerFactory/getInstance (tmfda))
          k (KeyManagerFactory/getInstance (kmfda))
          cpwd (some-> pwd c/charsit)
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
  "" ^SslContext [skey pwd] (some-> (buildCtx skey pwd) .build))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeCfgSSL2
  "" ^SslContext [skey pwd]
  (if-some [ctx (buildCtx skey pwd)]
    (let
      [cfg
       (ApplicationProtocolConfig.
         ApplicationProtocolConfig$Protocol/ALPN
         ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
         ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
         (doto (java.util.ArrayList.)
             (.add ApplicationProtocolNames/HTTP_2)
             (.add ApplicationProtocolNames/HTTP_1_1)))
       ^SslProvider
       p (if (and true (OpenSsl/isAlpnSupported))
           SslProvider/OPENSSL SslProvider/JDK)]
      (->
        (.ciphers ctx
                  Http2SecurityUtil/CIPHERS
                  SupportedCipherSuiteFilter/INSTANCE)
        (.sslProvider p)
        (.applicationProtocolConfig cfg)
        (.build)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- buildH2
  ""
  ^ChannelHandler
  [^Http2FrameListener h2 args]

  (-> (proxy [H2ConnBuilder][]
        (build [dc ec ss]
          (proxy [Http2ConnectionHandler]
                 [^Http2ConnectionDecoder dc
                  ^Http2ConnectionEncoder ec
                  ^Http2Settings ss])))
      (.setListener h2) (.newHandler true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cfgH2
  "" [^ChannelPipeline pp h2 args]
  (let
    [hh (HttpToHttp2ConnectionHandlerBuilder.)
     _ (.server hh true)
     ^Http2FrameListener
     c
     (cond
       (c/ist? Http2FrameListener h2) h2
       (fn? h2) (mg/h20Aggregator<>)
       :else
       (c/throwBadData "Bad handler type"))
     _ (.frameListener hh c)
     p (proxy [InboundHandler][true]
         (readMsg [ctx msg] (h2 ctx msg)))]
    (doto pp
      ;;(.addLast "in-codec" (buildH2 c args))
      (.addLast "out-codec" (.build hh))
      (.addLast "cw" (ChunkedWriteHandler.))
      (.addLast nc/user-handler-id p))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cfgH1 "" [^ChannelPipeline pp h1 args]

  (let
    [^ChannelHandler
     u
     (cond
       (c/ist? ChannelHandler h1) h1
       (fn? h1)
       (proxy [InboundHandler][true]
         (readMsg [ctx msg] (h1 ctx msg)))
       :else
       (c/throwBadData "bad handler type"))]
    (doto pp
      (.addLast "SC" (HttpServerCodec.))
      (.addLast "OA" obj-agg)
      (.addLast "RH" req-hdr)
      (.addLast "CW" (ChunkedWriteHandler.))
      (.addLast nc/user-handler-id u))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- onSSL
  "" [^ChannelPipeline cpp h1 h2 args]

  (cond
    (and (some? h1)
         (some? h2))
    (->>
      (proxy [ApplicationProtocolNegotiationHandler]
             [ApplicationProtocolNames/HTTP_1_1]
        (configurePipeline [ctx n]
          (let [pp (nc/cpipe ctx)
                ^String pn n]
            (cond
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_1_1 pn)
              (cfgH1 pp h1 args)
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_2 pn)
              (cfgH2 pp h2 args)
              :else
              (c/trap! IllegalStateException
                       (str "unknown protocol: " pn))))))
      (.addLast cpp "SSLNegotiator"))
    (some? h2)
    (cfgH2 cpp h2 args)
    (some? h1)
    (cfgH1 cpp h1 args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn udpInitor<>
  "" ^ChannelHandler [hu args]
  {:pre [(c/ist? ChannelHandler hu)]}
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (c/cast? Channel ch)
            pp (.pipeline ch)]
        (.addLast pp
                  nc/user-handler-id
                  ^ChannelHandler hu)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn tcpInitor<>
  "" ^ChannelHandler [^SslContext ctx hh1 hh2 args]

  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (c/cast? Channel ch)
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

  (let [sbs (c/cast? ServerBootstrap bs)
        ^H1DataFactory
        dfac (some-> sbs
                     .config
                     .childAttrs
                     (.get nc/dfac-key))
        ip (if (s/hgl? host)
             (InetAddress/getByName host)
             (InetAddress/getLocalHost))]
    (c/do-with [ch (-> (.bind bs ip (int port))
                       .sync .channel)]
      (log/debug "netty-svr running on host %s:%s" ip port)
      (nc/futureCB
        (.closeFuture ch)
        (fn [_]
          (log/debug "shutdown: server bootstrap@ip %s" ip)
          (c/try!
            (some-> dfac .cleanAllHttpData)
            (some-> sbs
                    .config .childGroup .shutdownGracefully)
            (.. bs config group shutdownGracefully)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- finz-ch [^Channel ch]
  (c/trye!
    nil
    (if (and ch
             (.isOpen ch)) (.close ch))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-mutable NettyWebServer
  LifeCycle
  (init [me carg]
    (let
      [{:keys [threads routes rcvBuf backlog
               sharedGroup? tempFileDir
               ciz hh1 hh2
               serverKey passwd
               maxContentSize maxInMemory]
        :or {maxContentSize Integer/MAX_VALUE
             maxInMemory i/*membuf-limit*
             rcvBuf (* 2 c/MegaBytes)
             backlog c/KiloBytes
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
         (c/ist? ChannelInitializer ciz) ciz
         (or hh1 hh2) (tcpInitor<> ctx hh1 hh2 carg)
         :else (c/trap! ClassCastException "wrong input"))
       tempFileDir (c/fpath (or tempFileDir
                                i/*tempfile-repo*))
       args (dissoc carg :routes :options)
       [g z] (nc/gAndC threads :tcps)]
      (c/do-with [bs (ServerBootstrap.)]
        (doseq [[k v]
                (or child
                    [[ChannelOption/SO_RCVBUF (int rcvBuf)]
                     [ChannelOption/TCP_NODELAY true]])]
          (.childOption bs k v))
        (doseq [[k v]
                (or server
                    [[ChannelOption/SO_BACKLOG (int backlog)]
                     [ChannelOption/SO_REUSEADDR true]])]
          (.option bs k v))
        ;;threads=zero tells netty to use default, which is
        ;;2*num_of_processors
        (c/setf! me :bootstrap bs)
        (doto bs
          (.handler (LoggingHandler. LogLevel/INFO))
          (.childHandler ^ChannelHandler ci)
          (.channel z))
        (if-not sharedGroup?
          (.group bs
                  ^EventLoopGroup g
                  ^EventLoopGroup (first (nc/gAndC threads :tcps)))
          (.group bs ^EventLoopGroup g))
        ;;assign generic attributes for all channels
        (.childAttr bs
                    nc/dfac-key
                    (H1DataFactory. (int maxInMemory)))
        (.childAttr bs nc/chcfg-key args)
        ;; routes to check?
        (when-not (empty? routes)
          (log/info "routes provided - creating routes cracker")
          (->> (cr/routeCracker<> routes)
               (.childAttr bs nc/routes-key)))
        (log/info "netty server bootstraped with [%s]"
                  (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
        (nc/configDiskFiles true tempFileDir))))

  (start [me] (.start me nil))
  (start [me arg]
    (let [bs (:bootstrap @me)
          {:keys [host port]
           :or {port 80}} arg]
      (assert (number? port))
      (c/setf! me
               :channel (start-svr bs host port))))

  (stop [me]
    (finz-ch (:channel @me)))

  (dispose [me] (c/wipe! me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-mutable NettyUdpServer
  LifeCycle
  (init [me carg]
    (let
      [{:keys [maxMsgsPerRead threads
               ciz hu
               rcvBuf options]
        :or {maxMsgsPerRead Integer/MAX_VALUE
             rcvBuf (* 2 c/MegaBytes)
             threads 0}}
       carg
       ci
       (cond
         (c/ist? ChannelInitializer ciz) ciz
         (some? hu) (udpInitor<> hu carg)
         :else (c/trap! ClassCastException "wrong input"))
       [g z] (nc/gAndC threads :udps)]
      (c/do-with [bs (Bootstrap.)]
        (doseq [[k v]
                (or options
                    [[ChannelOption/MAX_MESSAGES_PER_READ maxMsgsPerRead]
                     [ChannelOption/SO_RCVBUF (int rcvBuf)]
                     [ChannelOption/SO_BROADCAST true]
                     [ChannelOption/TCP_NODELAY true]])]
          (.option bs k v))
        (c/setf! me :bootstrap bs)
        (doto bs
          (.channel z)
          (.group g)
          (.handler ^ChannelHandler ci)))))

  (start [me] (.start me nil))
  (start [me arg]
    (let [bs (:bootstrap @me)
          {:keys [host port]
           :or {port 4444}}
          arg]
      (assert (number? port))
      (c/setf! me
               :channel (start-svr bs host port))))

  (stop [me]
    (finz-ch (:channel @me)))

  (dispose [me] (c/wipe! me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn nettyUdpServer<> "" {:tag LifeCycle}
  ([] (nettyUdpServer<> nil))
  ([carg]
   (c/do-with
     [w (c/mutable<> NettyUdpServer)]
     (if (some? carg) (.init w carg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn nettyWebServer<> "" {:tag LifeCycle}
  ([] (nettyWebServer<> nil))
  ([carg]
   (c/do-with
     [w (c/mutable<> NettyWebServer)]
     (if (some? carg) (.init w carg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

