;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
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
            [czlab.niou.routes :as cr]
            [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
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
(def ^:private ^ChannelHandler obj-agg (mg/h1req-aggregator<>))
(def ^:private ^ChannelHandler req-hdr (h1/h1req-handler<>))
(def ^:private ^String user-handler-id "netty-user-handler")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private tmfda
  "" [] `(TrustManagerFactory/getDefaultAlgorithm))
(defmacro ^:private kmfda
  "" [] `(KeyManagerFactory/getDefaultAlgorithm))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-ctx
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
          cpwd (some-> pwd i/x->chars)
          ks (KeyStore/getInstance
               ^String
               (if (cs/ends-with? skey ".jks") "JKS" "PKCS12"))
          _ (c/wo*
              [inp (io/input-stream (URL. skey))]
              (.load ks inp cpwd)
              (.init t ks)
              (.init k ks cpwd))]
      (-> (SslContextBuilder/forServer k) (.trustManager t)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-cfg-ssl1
  "" ^SslContext [skey pwd] (some-> (build-ctx skey pwd) .build))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-cfg-ssl2
  "" ^SslContext [skey pwd]
  (if-some [ctx (build-ctx skey pwd)]
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
(defn- build-h2
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
(defn- cfgh2
  "" [^ChannelPipeline pp h2 args]
  (let
    [hh (HttpToHttp2ConnectionHandlerBuilder.)
     _ (.server hh true)
     ^Http2FrameListener
     c
     (cond
       (c/is? Http2FrameListener h2) h2
       (fn? h2) (mg/h20-aggregator<>)
       :else
       (u/throw-BadData "Bad handler type"))
     _ (.frameListener hh c)
     p (proxy [InboundHandler][true]
         (readMsg [ctx msg] (h2 ctx msg)))]
    (doto pp
      ;;(.addLast "in-codec" (buildH2 c args))
      (.addLast "out-codec" (.build hh))
      (.addLast "cw" (ChunkedWriteHandler.))
      (.addLast user-handler-id p))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cfgh1
  "" [^ChannelPipeline pp h1 args]
  (let
    [^ChannelHandler
     u
     (cond
       (c/is? ChannelHandler h1) h1
       (fn? h1)
       (proxy [InboundHandler][true]
         (readMsg [ctx msg] (h1 ctx msg)))
       :else
       (u/throw-BadData "Bad handler type"))]
    (doto pp
      (.addLast "SC" (HttpServerCodec.))
      (.addLast "OA" obj-agg)
      (.addLast "RH" req-hdr)
      (.addLast "CW" (ChunkedWriteHandler.))
      (.addLast user-handler-id u))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- onssl
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
              (cfgh1 pp h1 args)
              (AsciiString/contentEquals
                ApplicationProtocolNames/HTTP_2 pn)
              (cfgh2 pp h2 args)
              :else
              (u/throw-ISE "unknown protocol: %s." pn)))))
      (.addLast cpp "SSLNegotiator"))
    (some? h2)
    (cfgh2 cpp h2 args)
    (some? h1)
    (cfgh1 cpp h1 args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-initor<>
  "" ^ChannelHandler [hu args]
  {:pre [(c/is? ChannelHandler hu)]}
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (c/cast? Channel ch)
            pp (.pipeline ch)]
        (.addLast pp
                  user-handler-id
                  ^ChannelHandler hu)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn tcp-initor<>
  "" ^ChannelHandler [^SslContext ctx hh1 hh2 args]
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (let [ch (c/cast? Channel ch)
            pp (.pipeline ch)]
        (if (some? ctx)
          (do (->> (.newHandler ctx
                                (.alloc ch))
                   (.addLast pp "ssl"))
              (onssl pp hh1 hh2 args))
          (cfgh1 pp hh1 args))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- start-svr
  "" ^Channel [bs host port]
  (let [bs (c/cast? ServerBootstrap bs)
        ^H1DataFactory
        dfac (some-> bs
                     .config
                     .childAttrs
                     (.get nc/dfac-key))
        ip (if (s/hgl? host)
             (InetAddress/getByName host)
             (InetAddress/getLocalHost))]
    (c/do-with [ch (-> (.bind bs ip (int port))
                       .sync .channel)]
      (l/debug "netty-svr running on host %s:%s." ip port)
      (nc/future-cb
        (.closeFuture ch)
        (fn [_]
          (l/debug "shutdown: server bootstrap@ip %s." ip)
          (c/try! (some-> dfac .cleanAllHttpData))
          (c/try! (some-> bs .config .childGroup .shutdownGracefully))
          (c/try! (.. bs config group shutdownGracefully)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- finz-ch
  [^Channel ch]
  (c/try! (if (and ch (.isOpen ch)) (.close ch))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- init-web-server
  "" [carg]
  (let [{:keys [threads routes rcv-buf backlog
                shared-group? temp-dir
                ciz hh1 hh2
                server-key passwd
                max-content-size max-in-memory]
         :or {max-content-size Integer/MAX_VALUE
              max-in-memory i/*membuf-limit*
              rcv-buf (* 2 c/MegaBytes)
              backlog c/KiloBytes
              shared-group? true
              threads 0 routes nil}
         {:keys [server child]} :options} carg
        ctx (if (some? hh2)
             (maybe-cfg-ssl2 server-key passwd)
             (maybe-cfg-ssl1 server-key passwd))
        ci
        (cond
         (c/is? ChannelInitializer ciz) ciz
         (or hh1 hh2) (tcp-initor<> ctx hh1 hh2 carg)
         :else (c/trap! ClassCastException "wrong input"))
       temp-dir (u/fpath (or temp-dir
                             i/*tempfile-repo*))
       args (dissoc carg :routes :options)
       [g z] (nc/g-and-c threads :tcps)]
    (c/do-with [_R (atom nil)]
      (let [bs (ServerBootstrap.)]
        (reset! _R {:bootstrap bs})
        (doseq [[k v]
                (or child
                    [[ChannelOption/SO_RCVBUF (int rcv-buf)]
                     [ChannelOption/TCP_NODELAY true]])]
          (.childOption bs k v))
        (doseq [[k v]
                (or server
                    [[ChannelOption/SO_BACKLOG (int backlog)]
                     [ChannelOption/SO_REUSEADDR true]])]
          (.option bs k v))
        ;;threads=zero tells netty to use default, which is
        ;;2*num_of_processors
        (doto bs
          (.handler (LoggingHandler. LogLevel/INFO))
          (.childHandler ^ChannelHandler ci)
          (.channel z))
        (if-not shared-group?
          (.group bs
                  ^EventLoopGroup g
                  ^EventLoopGroup (first (nc/g-and-c threads :tcps)))
          (.group bs ^EventLoopGroup g))
        ;;assign generic attributes for all channels
        (.childAttr bs
                    nc/dfac-key
                    (H1DataFactory. (int max-in-memory)))
        (.childAttr bs nc/chcfg-key args)
        ;; routes to check?
        (when (not-empty routes)
          (l/info "routes provided - creating routes cracker.")
          (->> (cr/route-cracker<> routes)
               (.childAttr bs nc/routes-key)))
        (l/info "netty server bootstraped with [%s]."
                (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
        (nc/config-disk-files true temp-dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-web-server!
  ""
  ([ws] (start-web-server! ws nil))
  ([ws arg]
   (let [bs (:bootstrap @ws)
         {:keys [host port]
           :or {port 80}} arg]
     (assert (number? port))
     (c/assoc!! ws
                :channel (start-svr bs host port)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- init-udp-server
  "" [carg]
  (let [{:keys [max-msgs-per-read
                threads
                ciz hu rcv-buf options]
         :or {max-msgs-per-read Integer/MAX_VALUE
              rcv-buf (* 2 c/MegaBytes)
              threads 0}} carg
        ci (cond
             (c/is? ChannelInitializer ciz) ciz
             (some? hu) (udp-initor<> hu carg)
             :else (c/trap! ClassCastException "wrong input"))
        [g z] (nc/g-and-c threads :udps)]
    (let [bs (Bootstrap.)]
      (doseq [[k v]
              (or options
                  [[ChannelOption/MAX_MESSAGES_PER_READ max-msgs-per-read]
                   [ChannelOption/SO_RCVBUF (int rcv-buf)]
                   [ChannelOption/SO_BROADCAST true]
                   [ChannelOption/TCP_NODELAY true]])]
        (.option bs k v))
      (doto bs
        (.channel z)
        (.group g)
        (.handler ^ChannelHandler ci))
      (atom {:bootstrap bs :channel nil}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-udp-server!
  ""
  ([svr] (start-udp-server! svr nil))
  ([svr arg]
   (let [bs (:bootstrap @svr)
         {:keys [host port]
          :or {port 4444}} arg]
     (assert (number? port))
     (c/assoc!! svr
                :channel (start-svr bs host port)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn stop-server!
  "Stop the server." [s]
  (finz-ch (:channel @s))
  (c/assoc!! s :channel nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-udp-server<>
  "A UDP server using netty." [& args] (init-udp-server (c/kvs->map args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-web-server<>
  "A TCP server using netty." [& args] (init-web-server (c/kvs->map args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

