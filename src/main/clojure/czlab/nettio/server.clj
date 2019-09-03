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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ServerAPI
  ""
  (stop-server! [_] "")
  (start-server! [_]
                 [_ args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol CfgChannelAPI
  ""
  (cfgh2 [_ h2 args] "")
  (cfgh1 [_ h1 args] "")
  (onssl [_ ctx h1 h2 args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyServer [])
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- pp->last
  [p n h]
  (l/debug "adding %s:%s to pipeline." n (u/gczn h))
  (.addLast ^ChannelPipeline p ^String n ^ChannelHandler h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private tmfda
  "" [] `(TrustManagerFactory/getDefaultAlgorithm))
(defmacro ^:private kmfda
  "" [] `(KeyManagerFactory/getDefaultAlgorithm))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-ctx
  ^SslContextBuilder [key pwd]
  (l/debug "build-ctx, key = %s." key)
  (cond (= "*" key)
        (let [c (SelfSignedCertificate.)]
          (SslContextBuilder/forServer (.certificate c)
                                       (.privateKey c)))
        (s/hgl? key)
        (let [k (KeyManagerFactory/getInstance (kmfda))
              t (TrustManagerFactory/getInstance (tmfda))
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
    (when h2?
      (.ciphers ctx
                Http2SecurityUtil/CIPHERS
                SupportedCipherSuiteFilter/INSTANCE)
      (.sslProvider ctx
                    ^SslProvider
                    (or (and true
                             (OpenSsl/isAlpnSupported)
                             SslProvider/OPENSSL) SslProvider/JDK))
      (->> (doto (java.util.ArrayList.)
             (.add ApplicationProtocolNames/HTTP_2)
             (.add ApplicationProtocolNames/HTTP_1_1))
           (ApplicationProtocolConfig.
             ApplicationProtocolConfig$Protocol/ALPN
             ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
             ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT)
           (.applicationProtocolConfig ctx)))
    (.build ctx)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build-h2
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
(extend-protocol CfgChannelAPI
  Channel
  (cfgh2 [ch h2 args]
    {:pre [(or (c/is? Http2FrameListener h2)(fn? h2))]}
    (let [hh (HttpToHttp2ConnectionHandlerBuilder.)]
      (.server hh true)
      (.frameListener hh
                      ^Http2FrameListener
                      (or (c/cast? Http2FrameListener h2)
                          (mg/h20-aggregator<>)))
      (doto (nc/cpipe ch)
        ;(pp->last "in-codec" (build-h2 c args))
        (pp->last "out-codec" (.build hh))
        (pp->last "cw" (ChunkedWriteHandler.))
        (pp->last "user-func"
                  (proxy [InboundHandler][true]
                    (readMsg [ctx msg] (h2 ctx msg)))))))
  (cfgh1 [ch h1 args]
    {:pre [(or (c/is? ChannelHandler h1)(fn? h1))]}
    (doto (nc/cpipe ch)
      (pp->last "SC" (HttpServerCodec.))
      (pp->last "OA" obj-agg)
      (pp->last "RH" req-hdr)
      (pp->last "CW" (ChunkedWriteHandler.))
      (pp->last "user-func"
                  (or (c/cast? ChannelHandler h1)
                      (proxy [InboundHandler][true]
                        (readMsg [ctx msg] (h1 ctx msg)))))))
  (onssl [ch ctx h1 h2 args]
    (pp->last (nc/cpipe ch) "ssl" (.newHandler ^SslContext ctx
                                               (.alloc ^Channel ch)))
    (cond (and h1 h2)
          (pp->last (nc/cpipe ch)
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
      (pp->last (nc/cpipe c) "user-func" hu))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- tcp-initor<>
  ^ChannelHandler [ctx hh1 hh2 args]
  (proxy [ChannelInitializer][]
    (initChannel [ch]
      (l/debug "tcp-initor: ctx = %s." ctx)
      (if (nil? ctx)
        (cfgh1 ch hh1 args)
        (onssl ch ctx hh1 hh2 args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ssvr
  ^Channel [bs host port]
  (let [bs (c/cast? AbstractBootstrap bs)
        bs' (c/cast? ServerBootstrap bs)
        ^H1DataFactory
        dfac (some-> bs'
                     .config
                     .childAttrs
                     (.get nc/dfac-key))
        ip (if (s/nichts? host)
             (InetAddress/getLocalHost)
             (InetAddress/getByName host))]
    (c/do-with [ch (->> (int port)
                        (.bind bs ip)
                        .sync .channel)]
      (l/info "netty-server running on %s:%s." ip port)
      (nc/future-cb (.closeFuture ch)
                    (fn [_]
                      (c/try! (some-> dfac .cleanAllHttpData))
                      (c/try! (some-> bs' .config
                                      .childGroup .shutdownGracefully))
                      (c/try! (.. bs config group shutdownGracefully))
                      (l/debug "info: server bootstrap@ip %s stopped." ip))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ServerAPI
  NettyServer
  (start-server!
    ([_] (start-server! _ nil))
    ([_ args]
     (let [{:keys [host port block?]} args
           {:keys [bootstrap channel]} _
           p (if (c/is? ServerBootstrap bootstrap) 80 4444)]
       (->> (c/num?? port p)
            (ssvr bootstrap host) (aset
                                   #^"[Ljava.lang.Object;" channel 0))
       (if block? (u/block!)))))
  (stop-server! [_]
    (let [{:keys [channel]} _
          ^Channel ch (c/_1 channel)]
      (aset #^"[Ljava.lang.Object;" channel 0 nil)
      (c/try! (if (and ch (.isOpen ch)) (.close ch))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-server<>
  "A UDP server using netty."
  ([] (udp-server<> nil))
  ([& args]
   (let [{:keys [max-msgs-per-read
                 threads
                 ciz hu rcv-buf options]
          :or {rcv-buf (* 2 c/MegaBytes)
               threads 0
               max-msgs-per-read Integer/MAX_VALUE}} args
         ci (cond (c/is? ChannelInitializer ciz) ciz
                  (some? hu) (udp-initor<> hu args)
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
       (c/object<> NettyServer
                   :bootstrap bs
                   :channel (object-array 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn tcp-server<>
  "A TCP server using netty."
  ([] (tcp-server<> nil))
  ([args]
   (let [{:as ARGS
          :keys [threads routes rcv-buf backlog
                 options shared-group?
                 temp-dir ciz hh1 hh2
                 server-key passwd
                 max-msg-size max-in-memory]}
          (merge {:max-msg-size Integer/MAX_VALUE
                  :backlog c/KiloBytes
                  :shared-group? true
                  :threads 0
                  :routes nil
                  :rcv-buf (* 2 c/MegaBytes)
                  :max-in-memory i/*membuf-limit*} args)
         ctx (maybe-cfg-ssl?? server-key passwd hh2)
         _ (l/debug "ssl ctx = %s." ctx)
         ci (cond (c/is? ChannelInitializer ciz) ciz
                  (or hh1 hh2) (tcp-initor<> ctx hh1 hh2 args)
                  :else (c/trap! ClassCastException "wrong input"))
         temp-dir (u/fpath (or temp-dir
                               i/*tempfile-repo*))
         ARGS (dissoc ARGS
                      :routes :options)
         [g z] (nc/g-and-c threads :tcps)]
     (let [bs (ServerBootstrap.)]
       (doseq [[k v]
               (or (:child options)
                   [[ChannelOption/SO_RCVBUF (int rcv-buf)]
                    [ChannelOption/TCP_NODELAY true]])]
         (.childOption bs k v))
       (doseq [[k v]
               (or (:server options)
                   [[ChannelOption/SO_BACKLOG (int backlog)]
                    [ChannelOption/SO_REUSEADDR true]])]
         (.option bs k v))
       ;;threads=zero tells netty to use default, which is
       ;;2*num_of_processors
       (doto bs
         (.handler (LoggingHandler. LogLevel/INFO))
         (.childHandler ^ChannelHandler ci)
         (.channel z))
       (if shared-group?
         (.group bs ^EventLoopGroup g)
         (.group bs
                 ^EventLoopGroup g
                 ^EventLoopGroup (first (nc/g-and-c threads :tcps))))
       ;;assign generic attributes for all channels
       (.childAttr bs
                   nc/dfac-key
                   (H1DataFactory. (int max-in-memory)))
       (.childAttr bs nc/chcfg-key ARGS)
       ;; routes to check?
       (when-not (empty? routes)
         (l/info "routes provided - creating routes cracker.")
         (->> (cr/route-cracker<> routes)
              (.childAttr bs nc/routes-key)))
       (l/info "netty server bootstraped with [%s]."
               (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
       (l/debug "args = %s" (i/fmt->edn ARGS))
       (nc/config-disk-files true temp-dir)
       (c/object<> NettyServer
                   :bootstrap bs
                   :channel (object-array 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

