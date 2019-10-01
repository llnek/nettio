;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "Netty servers."
    :author "Kenneth Leung"}

  czlab.nettio.server

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal
             [core :as c]
             [log :as l]
             [util :as u]
             [io :as i]
             [xpis :as po]]
            [czlab.nettio
             [msgs :as mg]
             [core :as nc]
             [http11 :as h1]]
            [czlab.niou.routes :as cr])

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
           [java.util List]
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
(c/defonce- ^ChannelHandler obj-agg (mg/h1req-aggregator<>))
(c/defonce- ^ChannelHandler req-hdr (h1/h1req-handler<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol CfgChannelAPI
  "*internal*"
  (cfgh2 [_ h2 args] "")
  (cfgh1 [_ h1 args] "")
  (onssl [_ ctx h1 h2 args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- tmfda
  [] `(TrustManagerFactory/getDefaultAlgorithm))
(c/defmacro- kmfda
  [] `(KeyManagerFactory/getDefaultAlgorithm))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-ctx
  ^SslContextBuilder [key pwd]
  (l/info "server-key = %s." key)
  (cond (= "*" key)
        (let [c (SelfSignedCertificate.)]
          (SslContextBuilder/forServer (.certificate c)
                                       (.privateKey c)))
        (c/hgl? key)
        (let [t (TrustManagerFactory/getInstance (tmfda))
              k (KeyManagerFactory/getInstance (kmfda))
              cpwd (some-> pwd i/x->chars)
              ks (KeyStore/getInstance
                   (if (cs/ends-with? key ".jks") "JKS" "PKCS12"))]
          (c/wo* [inp (io/input-stream (URL. ^String key))]
            (.load ks inp cpwd)
            (.init t ks)
            (.init k ks cpwd))
          (.trustManager (SslContextBuilder/forServer k) t))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-cfg-ssl??
  ^SslContext [key pwd h2?]
  (when-some [ctx (build-ctx key pwd)]
    (when h2? ;for h2, extra stuff needed
      (.ciphers ctx
                Http2SecurityUtil/CIPHERS
                SupportedCipherSuiteFilter/INSTANCE)
      (.sslProvider ctx
                    ^SslProvider
                    (or (and true
                             (OpenSsl/isAlpnSupported)
                             SslProvider/OPENSSL) SslProvider/JDK))
      (->> ^List
           (u/x->java [ApplicationProtocolNames/HTTP_2
                       ApplicationProtocolNames/HTTP_1_1])
           (ApplicationProtocolConfig.
             ApplicationProtocolConfig$Protocol/ALPN
             ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
             ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT)
           (.applicationProtocolConfig ctx)))
    (.build ctx)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-h2
  ^ChannelHandler [^Http2FrameListener h2 args]
  (-> (proxy [H2ConnBuilder][]
        (build [dc ec ss]
          (proxy [Http2ConnectionHandler]
                 [^Http2ConnectionDecoder dc
                  ^Http2ConnectionEncoder ec
                  ^Http2Settings ss])))
      (.setListener h2)
      (.newHandler true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol CfgChannelAPI
  Channel
  (cfgh2 [ch h2 args]
    {:pre [(or (c/is? Http2FrameListener h2)(fn? h2))]}
    (let [hh (HttpToHttp2ConnectionHandlerBuilder.)]
      (.server hh true)
      (.frameListener hh
                      ^Http2FrameListener
                      (or (c/cast? Http2FrameListener h2) (mg/h20-aggregator<>)))
      (doto (nc/cpipe ch)
        ;(nc/pp->last "in-codec" (build-h2 c args))
        (nc/pp->last "out-codec" (.build hh))
        (nc/pp->last "cw" (ChunkedWriteHandler.))
        (nc/pp->last "user-func"
                     (proxy [InboundHandler][true]
                       (readMsg [ctx msg] (h2 ctx msg)))))))
  (cfgh1 [ch h1 args]
    {:pre [(or (c/is? ChannelHandler h1)(fn? h1))]}
    (doto (nc/cpipe ch)
      (nc/pp->last "SC" (HttpServerCodec.))
      (nc/pp->last "OA" obj-agg)
      (nc/pp->last "RH" req-hdr)
      (nc/pp->last "CW" (ChunkedWriteHandler.))
      (nc/pp->last "user-func"
                   (or (c/cast? ChannelHandler h1)
                       (proxy [InboundHandler][true]
                         (readMsg [ctx msg] (h1 ctx msg)))))))
  (onssl [ch ctx h1 h2 args]
    (nc/pp->last (nc/cpipe ch) "ssl" (.newHandler ^SslContext ctx
                                                  (.alloc ^Channel ch)))
    (cond (and h1 h2)
          (nc/pp->last (nc/cpipe ch)
                       "SSLNegotiator"
                       (proxy [ApplicationProtocolNegotiationHandler]
                              [ApplicationProtocolNames/HTTP_1_1]
                         (configurePipeline [ctx n]
                           (cond (AsciiString/contentEquals
                                   ApplicationProtocolNames/HTTP_1_1 ^String n)
                                 (cfgh1 ch h1 args)
                                 (AsciiString/contentEquals
                                   ApplicationProtocolNames/HTTP_2 ^String n)
                                 (cfgh2 ch h2 args)
                                 :else
                                 (u/throw-ISE "unknown protocol: %s." n)))))
          h2
          (cfgh2 ch h2 args)
          h1
          (cfgh1 ch h1 args)
          :else (u/throw-BadArg "missing handlers"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- udp-initor<>
  ^ChannelHandler [hu args]
  {:pre [(c/is? ChannelHandler hu)]}
  (proxy [ChannelInitializer][]
    (initChannel [c]
      (nc/pp->last (nc/cpipe c) "user-func" hu))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- tcp-initor<>
  ^ChannelHandler [ctx hh1 hh2 args]
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (if (nil? ctx)
        (cfgh1 ch hh1 args)
        (onssl ch ctx hh1 hh2 args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ssvr
  ^Channel [bs host port]
  (let [bs (c/cast? AbstractBootstrap bs)
        bs' (c/cast? ServerBootstrap bs)
        ^H1DataFactory
        dfac (some-> bs' .config
                     .childAttrs (.get nc/dfac-key))
        ip (if (c/nichts? host)
             (InetAddress/getLocalHost)
             (InetAddress/getByName host))]
    (c/do-with [ch (.. bs (bind ip (int port)) sync channel)]
      (l/info "netty-server running on %s:%s." ip port)
      (nc/future-cb (.closeFuture ch)
                    (c/fn_1 (c/try! (some-> dfac .cleanAllHttpData))
                            (c/try! (some-> bs' .config
                                            .childGroup .shutdownGracefully))
                            (c/try! (.. bs config group shutdownGracefully))
                            (l/debug "info: server bootstrap@ip %s stopped." ip))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyServer []
  po/Startable
  (start [_] (.start _ nil))
  (start [_ options]
     (let [{:keys [host port block?]} options
           {:keys [bootstrap channel]} _
           p (if (c/is? ServerBootstrap bootstrap) 80 4444)]
       (->> (c/num?? port p)
            (ssvr bootstrap host)
            (aset #^"[Ljava.lang.Object;" channel 0))
       (if block? (u/block!))))
  (stop [_]
    (let [{:keys [channel]} _
          ^Channel ch (c/_1 channel)]
      (l/debug "stopping channel: %s." ch)
      (aset #^"[Ljava.lang.Object;" channel 0 nil)
      (c/try! (if (and ch (.isOpen ch)) (.close ch))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-server<>
  "A UDP server using netty."
  ([] (udp-server<> nil))
  ([args]
   (l/info "udp-server ctor().")
   (let [{:as ARGS
          :keys [max-msgs-per-read
                 threads ciz hu rcv-buf options]}
          (merge {:rcv-buf (* 2 c/MegaBytes)
                  :threads 0
                  :max-msgs-per-read Integer/MAX_VALUE} args)
         ci (cond (c/is? ChannelInitializer ciz) ciz
                  hu (udp-initor<> hu ARGS)
                  :else (c/trap! ClassCastException "wrong input"))
         [g z] (nc/g-and-c threads :udps)]
     (let [bs (Bootstrap.)]
       (l/info "setting server options.")
       (doseq [[k v]
               (or options
                   [[ChannelOption/MAX_MESSAGES_PER_READ max-msgs-per-read]
                    [ChannelOption/SO_RCVBUF (int rcv-buf)]
                    [ChannelOption/SO_BROADCAST true]
                    [ChannelOption/TCP_NODELAY true]])]
         (.option bs k v))
       (if (pos? threads)
         (l/info "threads=%s." threads)
         (l/info "threads=0, netty default to 2*num_of_processors"))
       (doto bs
         (.channel z)
         (.group g)
         (.handler ^ChannelHandler ci))
       (l/info "netty-server created - ok.")
       (c/object<> NettyServer
                   :bootstrap bs
                   :channel (object-array 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn tcp-server<>
  "A TCP server using netty."
  ([] (tcp-server<> nil))
  ([args]
   (l/info "tcp-server ctor().")
   (let [{:as ARGS
          :keys [threads routes rcv-buf backlog
                 options shared-group?
                 temp-dir ciz hh1 hh2
                 server-key passwd
                 max-msg-size max-mem-size]}
          (merge {:max-msg-size Integer/MAX_VALUE
                  :backlog c/KiloBytes
                  :shared-group? true
                  :threads 0
                  :routes nil
                  :rcv-buf (* 2 c/MegaBytes)
                  :max-mem-size i/*membuf-limit*} args)
         ctx (maybe-cfg-ssl?? server-key passwd hh2)
         ci (cond (c/is? ChannelInitializer ciz) ciz
                  (or hh1 hh2) (tcp-initor<> ctx hh1 hh2 args)
                  :else (c/trap! ClassCastException "wrong input"))
         temp-dir (u/fpath (or temp-dir
                               i/*tempfile-repo*))
         ARGS (dissoc ARGS
                      :routes :options)
         [g z] (nc/g-and-c threads :tcps)]
     (let [bs (ServerBootstrap.)]
       (l/info "setting child options.")
       (doseq [[k v]
               (or (:child options)
                   [[ChannelOption/SO_RCVBUF (int rcv-buf)]
                    [ChannelOption/TCP_NODELAY true]])]
         (.childOption bs k v))
       (l/info "setting server options.")
       (doseq [[k v]
               (or (:server options)
                   [[ChannelOption/SO_BACKLOG (int backlog)]
                    [ChannelOption/SO_REUSEADDR true]])]
         (.option bs k v))
       (if (pos? threads)
         (l/info "threads=%s." threads)
         (l/info "threads=0, netty default to 2*num_of_processors"))
       (doto bs
         (.channel z)
         (.childHandler ^ChannelHandler ci)
         (.handler (LoggingHandler. LogLevel/INFO)))
       (l/info "group sharing? = %s." shared-group?)
       (if shared-group?
         (.group bs ^EventLoopGroup g)
         (.group bs
                 ^EventLoopGroup g
                 ^EventLoopGroup (c/_1 (nc/g-and-c threads :tcps))))
       (l/info "assigning generic attributes for all channels.")
       (.childAttr bs
                   nc/dfac-key
                   (H1DataFactory. (int max-mem-size)))
       (.childAttr bs nc/chcfg-key ARGS)
       ;; routes to check?
       (l/info "routes provided: %s." (not-empty routes))
       (when-not (empty? routes)
         (->> (cr/route-cracker<> routes)
              (.childAttr bs nc/routes-key))
         (l/info "creating routes cracker."))
       (l/info "netty server bootstraped with [%s]."
               (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
       ;(l/debug "args = %s" (i/fmt->edn ARGS))
       (nc/config-disk-files true temp-dir)
       (l/info "netty-server created - ok.")
       (c/object<> NettyServer
                   :bootstrap bs :channel (object-array 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

