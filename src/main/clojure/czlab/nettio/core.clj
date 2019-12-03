;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.nettio.core

  (:refer-clojure :exclude [get-method])

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.niou.core :as cc]
            [czlab.niou.upload :as cu]
            [czlab.niou.routes :as cr])

  (:import [io.netty.handler.codec DecoderResultProvider DecoderResult]
           [io.netty.handler.ssl.util
            SelfSignedCertificate
            InsecureTrustManagerFactory]
           [io.netty.channel.epoll Epoll]
           [io.netty.channel.epoll
            EpollEventLoopGroup
            EpollDatagramChannel
            EpollSocketChannel
            EpollServerSocketChannel]
           [io.netty.channel.nio
            NioEventLoopGroup]
           [io.netty.channel.socket.nio
            NioDatagramChannel
            NioSocketChannel
            NioServerSocketChannel]
           [java.security.cert X509Certificate CertificateFactory]
           [javax.net.ssl KeyManagerFactory TrustManagerFactory]
           [io.netty.handler.codec.http2 Http2SecurityUtil]
           [io.netty.bootstrap ServerBootstrap Bootstrap]
           [java.io IOException OutputStream]
           [io.netty.handler.codec.http.websocketx
            TextWebSocketFrame
            BinaryWebSocketFrame]
           [io.netty.handler.codec.http.multipart
            HttpDataFactory
            MixedAttribute
            Attribute
            HttpData
            FileUpload
            DiskAttribute
            DiskFileUpload
            InterfaceHttpPostRequestDecoder
            HttpPostRequestDecoder$EndOfDataDecoderException]
           [io.netty.handler.ssl
            OpenSsl
            SslHandler
            SslContext
            SslProvider
            SslContextBuilder
            SupportedCipherSuiteFilter
            ApplicationProtocolNames
            ApplicationProtocolConfig
            ApplicationProtocolConfig$Protocol
            ApplicationProtocolConfig$SelectorFailureBehavior
            ApplicationProtocolConfig$SelectedListenerFailureBehavior]
           [czlab.nettio InboundHandler]
           [czlab.niou Headers]
           [java.util List]
           [java.net
            InetAddress
            URL
            URI
            HttpCookie
            InetSocketAddress]
           [io.netty.handler.codec.http.cookie
            ServerCookieDecoder
            ClientCookieDecoder
            DefaultCookie
            Cookie
            ServerCookieEncoder]
           [io.netty.handler.codec.http
            HttpVersion
            HttpMethod
            HttpUtil
            FullHttpResponse
            LastHttpContent
            HttpHeaderValues
            HttpHeaderNames
            HttpMessage
            HttpContent
            HttpResponse
            DefaultFullHttpResponse
            DefaultHttpResponse
            DefaultHttpHeaders
            DefaultHttpRequest
            HttpRequest
            HttpResponseStatus
            HttpHeaders
            QueryStringDecoder]
           [java.nio.charset Charset]
           [java.security KeyStore]
           [io.netty.util
            CharsetUtil
            AttributeKey
            ReferenceCounted
            ReferenceCountUtil]
           [czlab.basal XData]
           [io.netty.buffer
            Unpooled
            ByteBuf
            ByteBufHolder
            ByteBufAllocator]
           [io.netty.channel
            ChannelPipeline
            ChannelFuture
            ChannelOption
            ChannelHandler
            Channel
            ChannelHandlerContext
            ChannelFutureListener
            ChannelOutboundInvoker]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

(defonce channel-options #{
  :ALLOCATOR
  :RCVBUF_ALLOCATOR
  :MESSAGE_SIZE_ESTIMATOR
  :CONNECT_TIMEOUT_MILLIS
  :WRITE_SPIN_COUNT
  :WRITE_BUFFER_WATER_MARK
  :ALLOW_HALF_CLOSURE
  :AUTO_READ
  :AUTO_CLOSE

  :SO_BROADCAST
  :SO_KEEPALIVE
  :SO_SNDBUF
  :SO_RCVBUF
  :SO_REUSEADDR
  :SO_LINGER
  :SO_BACKLOG
  :SO_TIMEOUT

  :IP_TOS
  :IP_MULTICAST_ADDR
  :IP_MULTICAST_IF
  :IP_MULTICAST_TTL
  :IP_MULTICAST_LOOP_DISABLED
  :TCP_NODELAY
  :SINGLE_EVENTEXECUTOR_PER_GROUP})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ghdrs

  ^Headers [msg] (:headers msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro chcfg??

  "Get config info from channel's attr."
  [ctx] `(czlab.nettio.core/akey?? ~ctx czlab.nettio.core/chcfg-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cache??

  "Get data cache from channel's attr."
  [ctx] `(czlab.nettio.core/akey?? ~ctx czlab.nettio.core/cache-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro isH2?

  "Is protocol http2."
  [protocol] `(.equals "2" ~protocol))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ihprd?

  "Isa InterfaceHttpPostRequestDecoder?"
  [impl]
  `(instance? io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder ~impl))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro offer!

  "InterfaceHttpPostRequestDecoder.offer."
  [impl part]

  `(.offer
     ~(with-meta
        impl {:tag 'io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder}) ~part))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro mp-attr?

  "Isa Attribute?"
  [impl]

  `(instance? io.netty.handler.codec.http.multipart.Attribute ~impl))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro add->mp-attr!

  "Add content to Attribute."
  [impl part last?]

  `(.addContent ~(with-meta impl
                            {:tag 'io.netty.handler.codec.http.multipart.Attribute})
                (czlab.nettio.core/retain! ~part) ~last?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro set-akey*

  "Clear a set of attribute keys."
  [ctx & args]

  `(do ~@(map (fn [[k v]]
                `(czlab.nettio.core/akey+ ~ctx ~k ~v)) args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro del-akey*

  "Clear a set of attribute keys."
  [ctx & args]

  `(do ~@(map (fn [k]
                `(czlab.nettio.core/akey- ~ctx ~k)) args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn chopt*

  "Morph into a netty's ChannelOption enum."
  ^ChannelOption [opt]

  (ChannelOption/valueOf (name opt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro new-promise

  "Make a new promise."
  [ctx]

  `(.newPromise ~(with-meta ctx
                            {:tag 'io.netty.channel.ChannelHandlerContext})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- gandc

  "Group and Channel info."
  [e n]

  `(array-map :epoll ~e :nio ~n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn group+channel

  "Event group & Channel type."
  [t kind]

  (when-some
    [{:keys [epoll nio]}
     ({:tcps (gandc EpollServerSocketChannel NioServerSocketChannel)
       :tcpc (gandc EpollSocketChannel NioSocketChannel)
       :udps (gandc EpollDatagramChannel NioDatagramChannel)} kind)]
    (l/info "netty bootstraped with [%s]."
            (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
    (if-not (Epoll/isAvailable)
      [(NioEventLoopGroup. (int t)) nio]
      [(EpollEventLoopGroup. (int t)) epoll])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol AttributeKeyAPI
  (akey- [_ k] "")
  (akey?? [_ k] "")
  (akey+ [_ k v] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ByteBufAPI
  (bbuf->bytes [_] "")
  (slurp-bytebuf [_ out] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ChannelFutureAPI
  (cf-cb [_ arg] "")
  (cf-close [_]
            [_ keepAlive?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol OutboundAPI
  (continue-100 [inv] "")
  (write-last-content [inv]
                      [inv flush?] "")
  (reply-status [inv]
                [inv status]
                [inv status keepAlive?] "")
  (reply-redirect [inv perm? location]
                  [inv perm? location keepAlive?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol PipelineAPI
  (dbg-pipeline [_] "")
  (ctx-name [_ h] "")
  (remove-handler [_ arg] "")
  (remove-handler* [_ args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpMessageAPI
  (get-uri-path [_] "")
  (get-uri-params [_] "")
  (get-charset [_] "")
  (get-method [_] "")
  (add-header [_ h v] "")
  (set-header [_ h v] "")
  (get-header [_ h] "")
  (get-headers [_] "")
  (has-header? [_ h] "")
  (set-headers [_ hs] "")
  (add-headers [_ hs] "")
  (crack-cookies [_] "")
  (no-content? [_] "")
  (content-type [_] "")
  (content-length! [_ len] "")
  (content-length-as-int [_] "")
  (detect-charset [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol BootstrapAPI (nobs! [_ ch] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro num->status

  "Morph INT into netty's http response object."
  [c] `(io.netty.handler.codec.http.HttpResponseStatus/valueOf ~c))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro fire-msg

  "Inline fireChannelRead."
  [ctx msg]

  `(let [m# ~msg]
     (if m#
       (.fireChannelRead ~(with-meta ctx {:tag 'io.netty.channel.ChannelHandlerContext}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro write-msg*

  "Inline write."
  [c msg]

  `(let [m# ~msg]
     (if m#
       (.write ~(with-meta c {:tag 'io.netty.channel.ChannelOutboundInvoker}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro write-msg

  "Inline writeAndFlush."
  [c msg]

  `(let [m# ~msg]
     (if m#
       (.writeAndFlush ~(with-meta c {:tag 'io.netty.channel.ChannelOutboundInvoker}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hv-int

  [h k] `(czlab.basal.core/try!
           (Integer/parseInt
             (.getFirst ~(with-meta h {:tag 'Headers}) ~k))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hv->int

  [h k dv] `(int (or (hv-int ~h ~k) ~dv)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hreq?

  "Isa HttpRequest?"
  [m] `(instance? ~'HttpRequest ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hrsp?

  "Isa HttpResponse?"
  [m] `(instance? ~'HttpResponse ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdr*

  "Morph into a HttpHeaderName."
  [name] `~(symbol (str "HttpHeaderNames/"  (str name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdv*

  "Morph into a HttpHeaderValue."
  [name] `~(symbol (str "HttpHeaderValues/"  (str name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro scode*

  "Morph into a HttpResponseStatus then cast to INT."
  [status]

  `(.code ~(symbol (str "HttpResponseStatus/"  (str status)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<x>

  "ChannelFuture - close-error."
  []

  `io.netty.channel.ChannelFutureListener/CLOSE_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<z>

  "ChannelFuture - close-success."
  []

  `io.netty.channel.ChannelFutureListener/CLOSE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-del

  "Release a reference-counted object."
  [r]

  `(io.netty.util.ReferenceCountUtil/release ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-add

  "Increase reference-count."
  [r]

  `(io.netty.util.ReferenceCountUtil/retain ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<e>

  "ChannelFuture - exception."
  []

  `io.netty.channel.ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro akey<>

  "New Attribute."
  [n]

  `(io.netty.util.AttributeKey/newInstance (name ~n)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro gattr

  "Get actual data inside an Attribute."
  [attr] `(czlab.nettio.core/get-http-data ~attr true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- hthds

  [msg] `(.headers ~msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- ghthds

  [msg h] `(.get (.headers ~msg) ~h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- shthds

  [msg h v] `(.set (.headers ~msg) ~h ~v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- ahthds

  [msg h v] `(.add (.headers ~msg) ~h ~v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce ^AttributeKey dfac-key (akey<> :data-factory))
(defonce ^AttributeKey cc-key  (akey<> :wsock-client))
(defonce ^AttributeKey routes-key (akey<> :cracker))
(defonce ^AttributeKey corscfg-key (akey<> :corscfg))
(defonce ^AttributeKey origin-key (akey<> :origin))
(defonce ^AttributeKey chcfg-key (akey<> :ch-config))
(defonce ^AttributeKey cache-key (akey<> :ch-cache))
(defonce ^String body-id "--body--")
(defonce ^String user-cb "user-cb")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cfg-ctx-bldr

  ^SslContextBuilder
  [^SslContextBuilder b h2Only?]

  (let [^Iterable
        ps (if h2Only?
             [ApplicationProtocolNames/HTTP_2]
             [ApplicationProtocolNames/HTTP_2
              ApplicationProtocolNames/HTTP_1_1])]
    (.sslProvider b
                  ^SslProvider
                  (if (and false (OpenSsl/isAlpnSupported))
                    SslProvider/OPENSSL SslProvider/JDK))
    (.ciphers b
              Http2SecurityUtil/CIPHERS
              SupportedCipherSuiteFilter/INSTANCE)
    (.applicationProtocolConfig
      b
      (ApplicationProtocolConfig.
        ApplicationProtocolConfig$Protocol/ALPN
        ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
        ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT ps))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pp->last

  [p n h]

  (l/debug "add-last %s/%s to ch-pipeline." n (u/gczn h))
  (.addLast ^ChannelPipeline p ^String n ^ChannelHandler h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pp->next

  [p a n h]

  (l/debug "add-after %s %s/%s to ch-pipeline." a n (u/gczn h))
  (.addAfter ^ChannelPipeline p ^String a ^String n ^ChannelHandler h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn data-attr<>

  "Create a Mixed Attribute for storage."
  ^Attribute [size] (MixedAttribute. body-id size))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1msg?

  [msg]
  (c/or?? [msg instance?]
          HttpContent HttpRequest HttpResponse))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1end?

  [msg]
  (c/or?? [msg instance?]
          LastHttpContent FullHttpResponse))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn put-post?

  "Http PUT or POST?"
  [x] (or (= x :post)(= x :put)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn app-handler

  ^ChannelHandler
  [user-cb]
  {:pre [(fn? user-cb)]}

  (proxy [InboundHandler][]
    (onRead [_ _ msg] (user-cb msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol BootstrapAPI
  Bootstrap
  (nobs! [bs ^Channel ch]
    (c/try! (if (and ch
                     (.isOpen ch))
              (.close ch)))
    (if-some [bs' (c/cast? ServerBootstrap bs)]
      (c/try! (.. bs' config childGroup shutdownGracefully)))
    (c/try! (.. bs config group shutdownGracefully))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn client-ssl??

  [^ChannelPipeline pp
   server-cert
   {:keys [scheme protocol] :as args}]

  (l/info "protocol = %s, server-cert = %s." protocol server-cert)
  (letfn
    [(ccerts [in]
       (let [[d? inp] (i/input-stream?? in)]
         (try (->> (-> (CertificateFactory/getInstance "X.509")
                       (.generateCertificates ^InputStream inp))
                   (c/vargs X509Certificate))
              (finally (if d? (i/klose inp))))))
     (bld-ctx []
       (let [ctx (SslContextBuilder/forClient)]
         (if-not (.equals "*" server-cert)
           (.trustManager ctx
                          #^"[Ljava.security.cert.X509Certificate;"
                          (ccerts (io/as-url server-cert)))
           (.trustManager ctx InsecureTrustManagerFactory/INSTANCE))))]
    (when (and (not (.equals "http" scheme))
               (or (.equals "*" server-cert)
                   (some-> server-cert
                           (cs/starts-with? "file:"))))
      (c/let#nil [^SslContextBuilder b (bld-ctx)
                  ^SslContext ctx
                  (if-not (.equals "2" protocol)
                    (.build b)
                    (.build (cfg-ctx-bldr b true)))]
        (pp->last pp "ssl" (.newHandler ctx (.. pp channel alloc)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn server-ssl??

  [^ChannelPipeline pp keyfile passwd args]

  (l/info "server-key = %s." keyfile)
  (letfn
    [(bld-ctx []
       (cond (.equals "*" keyfile)
             (let [c (SelfSignedCertificate.)]
               (SslContextBuilder/forServer (.certificate c)
                                            (.privateKey c)))
             (some-> keyfile
                     (cs/starts-with? "file:"))
             (let [t (->> (TrustManagerFactory/getDefaultAlgorithm)
                          TrustManagerFactory/getInstance)
                   k (->> (KeyManagerFactory/getDefaultAlgorithm)
                          KeyManagerFactory/getInstance)
                   cpwd (some-> passwd i/x->chars)
                   ks (KeyStore/getInstance
                        (if (cs/ends-with?
                              keyfile ".jks") "JKS" "PKCS12"))]
               (c/wo* [inp (io/input-stream (URL. ^String keyfile))]
                 (.load ks inp cpwd)
                 (.init t ks)
                 (.init k ks cpwd))
               (.trustManager (SslContextBuilder/forServer k) t))
             :else
             (u/throw-BadArg "Invalid keyfile path: %s" keyfile)))]
    (c/let#nil [^SslContextBuilder b (bld-ctx)]
      (pp->last pp
                "ssl"
                (-> (.build (cfg-ctx-bldr b false))
                    (.newHandler (.. pp channel alloc)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cfop<>

  "Create a ChannelFutureListener."
  ^ChannelFutureListener
  [func]
  {:pre [(fn? func)]}

  (reify ChannelFutureListener
    (operationComplete [_ ff] (c/try! (func ff)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mg-cs??

  "Cast to a CharSequence." ^CharSequence [s] s)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpMsgGist
  czlab.niou.core.HttpResultMsg
  (msg-header-keys [msg]
    (into #{} (.keySet (ghdrs msg))))
  (msg-header-vals [msg h]
    (into [] (.get (ghdrs msg) (str h))))
  (msg-header [msg h]
   (.getFirst (ghdrs msg) (str h)))
  (msg-header? [msg h]
    (.containsKey (ghdrs msg) (str h)))

  czlab.niou.core.Http2xMsg
  (msg-header-vals [msg h]
    (into [] (.get (ghdrs msg) h)))
  (msg-header [msg h]
    (.getFirst (ghdrs msg) (str h)))
  (msg-header? [msg h]
    (.containsKey (ghdrs msg) h))
  (msg-header-keys [msg]
    (into #{}
          (.keySet (ghdrs msg))))

  czlab.niou.core.Http1xMsg
  (msg-header-vals [msg h]
    (into [] (.get (ghdrs msg) h)))
  (msg-header [msg h]
    (.getFirst (ghdrs msg) (str h)))
  (msg-header? [msg h]
    (.containsKey (ghdrs msg) h))
  (msg-header-keys [msg]
    (into #{}
          (.keySet (ghdrs msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol AttributeKeyAPI
  ChannelHandlerContext
  (akey- [_ key]
    (akey- (.channel _) key))
  (akey?? [_ key]
    (akey?? (.channel _) key))
  (akey+ [_ key val]
    (akey+ (.channel _) key val))
  Channel
  (akey- [_ key]
    (akey+ _ key nil))
  (akey?? [_ key]
    (some-> (.attr _ ^AttributeKey key) .get))
  (akey+ [_ key val]
    (some-> (.attr _ ^AttributeKey key) (.set val)) val))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn host-loopback-name

  ^String [] (.getHostName
               (InetAddress/getLoopbackAddress)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn host-loopback-addr

  ^String [] (.getHostAddress
               (InetAddress/getLoopbackAddress)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn lhost-name

  ^String [] (.getHostName
               (InetAddress/getLocalHost)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn lhost-addr

  ^String [] (.getHostAddress
               (InetAddress/getLocalHost)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fake-req<>

  "Fake a http-11 request."
  ^HttpRequest []

  (DefaultHttpRequest. HttpVersion/HTTP_1_1
                       HttpMethod/POST "/" (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dfac??

  ^HttpDataFactory [ctx] (akey?? ctx dfac-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn retain!

  ^ByteBufHolder [^ByteBufHolder part] (.. part content retain))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-http-data

  "Get content and if it's a file,
  rename to self,
  trick code to not delete the file."

  ([d] (get-http-data d nil))

  ([^HttpData d wrap?]
   (let [f? (c/is? FileUpload d)
         x (when d
             (if (.isInMemory d)
               (.get d)
               (c/doto->> (.getFile d)
                          (.renameTo d))))
         r (if-not (and f?
                        (bytes? x)
                        (pos? (alength ^bytes x)))
             x
             (i/spit-file (i/temp-file) x true))
         r (if (and (bytes? r)
                    (zero? (alength ^bytes r))) nil r)]
     (if-not wrap? r (XData. r)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- next-body?

  [deco]
  `(try (.hasNext ~deco)
        (catch HttpPostRequestDecoder$EndOfDataDecoderException ~'_ false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-form-multipart

  [^InterfaceHttpPostRequestDecoder deco]

  (l/debug (str "parse-post, decoder= %s,"
                " multipart= %s.") deco (.isMultipart deco))
  (loop [out (cu/form-items<>)]
    (if-not (next-body? deco)
      (try out
           (finally (.destroy deco)))
      (let [n (.next deco)
            nm (.getName n)
            b (get-http-data n true)]
        (recur
          (cu/add-item out
                       (or (if-some [u (c/cast? FileUpload n)]
                             (cu/file-item<>
                               false (.getContentType u)
                               nil nm (.getFilename u) b))
                           (if-some [a (c/cast? Attribute n)]
                             (cu/file-item<> true "" nil nm "" b))
                           (u/throw-IOE "Bad http data."))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-mp-attr
  [a]
  (c/do-with [r (get-http-data a)] (ref-del a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn config-disk-files

  "Configure temp-files repo."
  [delExit? fDir]

  (set! DiskFileUpload/deleteOnExitTemporaryFile false)
  (set! DiskAttribute/deleteOnExitTemporaryFile false)
  (set! DiskFileUpload/baseDirectory fDir)
  (set! DiskAttribute/baseDirectory fDir)
  (l/info "netty temp-file-repo: %s." fDir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro last-part?

  [p]
  `(instance? io.netty.handler.codec.http.LastHttpContent ~p))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-result

  ^DecoderResult [msg]

  (some-> (c/cast? DecoderResultProvider msg) .decoderResult))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro decoder-err?

  [m] `(not (decoder-ok? ~m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro decoder-err-cause??

  [m]
  `(if-some
     [~'r (czlab.nettio.core/decoder-result ~m)] (.cause ~'r)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro decoder-ok?

  [m]
  `(if-some
     [~'r (czlab.nettio.core/decoder-result ~m)] (.isSuccess ~'r) true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-ssl??

  ^SslHandler [arg]

  (c/condp?? instance? arg
    ChannelPipeline (.get ^ChannelPipeline arg SslHandler)
    Channel (get-ssl?? (.pipeline ^Channel arg))
    ChannelHandlerContext (get-ssl?? (.pipeline ^ChannelHandlerContext arg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn match-one-route??

  [ctx msg]

  (let [c (akey?? ctx routes-key)
        {u2 :uri2
         m :request-method} msg
        path (.getPath ^URI u2)]
    (l/debug "matching route for path: %s." path)
    (or (if (and c
                 (c/hgl? path)
                 (cr/has-routes? c))
          (cr/crack-route c
                          {:uri path
                           :request-method m})) :passthru)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cpipe??

  ^ChannelPipeline [c]

  (c/condp?? instance? c
    ChannelPipeline c
    Channel (.pipeline ^Channel c)
    ChannelHandlerContext (.pipeline ^ChannelHandlerContext c)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ch??

  ^Channel [arg]

  (c/condp?? instance? arg
    Channel arg
    ChannelPipeline (.channel ^ChannelPipeline arg)
    ChannelHandlerContext (.channel ^ChannelHandlerContext arg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn close!

  [c]

  (c/condp?? instance? c
    Channel (.close ^Channel c)
    ChannelHandlerContext (.close ^ChannelHandlerContext c)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn chanid

  ^String [c]

  (str (c/condp?? instance? c
         Channel
         (.id ^Channel c)
         ChannelHandlerContext
         (.. ^ChannelHandlerContext c channel id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ByteBufAPI
  ByteBuf
  (slurp-bytebuf [buf o]
    (let [len (.readableBytes buf)
          out (c/cast? OutputStream o)]
      (if-not (pos? len)
        0
        (do (.readBytes buf
                        out
                        (int len)) (.flush out) len))))
  (bbuf->bytes [buf]
    (let [out (i/baos<>)]
      (if (pos? (slurp-bytebuf buf out)) (i/x->bytes out)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- x->bbuf

  ^ByteBuf [^Channel ch arg encoding]

  (let [cs (u/charset?? encoding "utf-8")
        buf (some-> ch .alloc .directBuffer)]
    (cond
      (bytes? arg)
      (if buf
        (.writeBytes buf ^bytes arg)
        (Unpooled/wrappedBuffer ^bytes arg))
      (string? arg)
      (if buf
        (doto buf (.writeCharSequence
                    ^CharSequence arg cs))
        (Unpooled/copiedBuffer ^CharSequence arg cs))
      :else
      (u/throw-IOE "Bad type to ByteBuf."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn bbuf??

  ([arg ch] (bbuf?? arg ch nil))

  ([arg] (bbuf?? arg nil nil))

  ([arg ch encoding]
   (let [ct (if-some
              [c (c/cast? XData arg)] (.content c) arg)]
     (if (or (bytes? ct)
             (string? ct)) (x->bbuf ch ct encoding) ct))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-reply<>

  "Create an incomplete response"
  {:tag HttpResponse}

  ([]
   (http-reply<> (scode* OK)))

  ([code]
   {:pre [(number? code)]}
   (DefaultHttpResponse. HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf code))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-reply<+>

  "Create a complete response"
  {:tag FullHttpResponse}

  ([code] (http-reply<+> code nil nil))

  ([] (http-reply<+> (scode* OK)))

  ([code msg ^ByteBufAllocator alloc]
   (let [ver HttpVersion/HTTP_1_1
         status (num->status code)]
     (cond
       (c/is? ByteBuf msg)
       (DefaultFullHttpResponse. ver
                                 status ^ByteBuf msg)
       (nil? msg)
       (doto
         (DefaultFullHttpResponse. ver status)
         (HttpUtil/setContentLength 0))
       :else
       (let [bb (some-> alloc .directBuffer)]
         (u/assert-IOE (some? bb) "No direct buffer.")
         (cond (bytes? msg)
               (.writeBytes bb ^bytes msg)
               (map? msg)
               (.writeCharSequence bb
                                   ^String (:string msg)
                                   ^Charset (:encoding msg))
               (string? msg)
               (.writeCharSequence bb
                                   ^String msg
                                   CharsetUtil/UTF_8)
               :else
               (u/throw-IOE "Rouge content %s." (type msg)))
         (DefaultFullHttpResponse. ver status ^ByteBuf bb))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ChannelFutureAPI
  ChannelFuture
  (cf-cb [cf arg]
    (if-some
      [ln (cond (c/is? ChannelFutureListener arg) arg
                (fn? arg) (cfop<> arg)
                (nil? arg) nil
                :else
                (u/throw-IOE "Invalid object %s." (type arg)))]
      (.addListener cf ^ChannelFutureListener ln)))
  (cf-close
    ([cf]
     (cf-close cf false))
    ([cf keepAlive?]
     (if (not (boolean keepAlive?))
       (.addListener cf ChannelFutureListener/CLOSE)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol OutboundAPI
  ChannelOutboundInvoker
  (write-last-content
    ([inv]
     (write-last-content inv false))
    ([inv flush?]
     (if-not flush?
       (write-msg* inv LastHttpContent/EMPTY_LAST_CONTENT)
       (write-msg inv LastHttpContent/EMPTY_LAST_CONTENT))))
  (reply-status
    ([inv status] (reply-status inv status false))
    ([inv] (reply-status inv 200))
    ([inv status keepAlive?]
     (let [rsp (http-reply<+> status)
           ka? (if-not (and (>= status 200)
                            (< status 300)) false keepAlive?)]
       (l/debug "returning status [%s]." status)
       (HttpUtil/setKeepAlive rsp ka?)
       (cf-close (write-msg inv rsp) ka?))))
  (reply-redirect
    ([inv perm? location]
     (reply-redirect inv perm? location false))
    ([inv perm? location keepAlive?]
     (let [ka? false
           rsp (http-reply<+>
                 (if perm?
                   (scode* MOVED_PERMANENTLY)
                   (scode* TEMPORARY_REDIRECT)))]
       (l/debug "redirecting to -> %s." location)
       (shthds rsp (h1hdr* LOCATION) location)
       (HttpUtil/setKeepAlive rsp ka?)
       (cf-close (write-msg inv rsp) ka?))))
  (continue-100 [inv]
    (.addListener (write-msg inv
                             (http-reply<+>
                               (scode* CONTINUE))) (cfop<e>))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol PipelineAPI
  ChannelPipeline
  (ctx-name [pipe h]
    (some-> (.context pipe ^ChannelHandler h) .name))
  (dbg-pipeline [pipe]
    (l/debug "pipeline= %s"
             (cs/join "|" (.names pipe))))
  (remove-handler* [pipe args]
    (doseq [a args]
      (remove-handler pipe a)))
  (remove-handler [pipe h]
    (c/try! (c/condp?? instance? h
              String (.remove pipe ^String h)
              Class  (.remove pipe ^Class h)
              ChannelHandler (.remove pipe ^ChannelHandler h)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-cookie<>
  ^Cookie [^HttpCookie c]
  ;; stick with version 0, Java's HttpCookie defaults to 1 but that
  ;; screws up the Path attribute on the wire => it's quoted but
  ;; browser seems to not like it and mis-interpret it.
  ;; Netty's cookie defaults to 0, which is cool with me.
  (l/debug "cookie->netty: %s=[%s]."
           (.getName c) (.getValue c))
  (doto (DefaultCookie. (.getName c)
                        (.getValue c))
    (.setDomain (.getDomain c))
    (.setMaxAge (.getMaxAge c))
    (.setPath (.getPath c))
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-cookie<>
  ^HttpCookie [^Cookie c]
  (doto (HttpCookie. (.name c)
                     (.value c))
    (.setDomain (.domain c))
    (.setMaxAge (.maxAge c))
    (.setPath (.path c))
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol HttpMessageAPI
  HttpMessage
  (detect-charset [msg]
    (c/when-some+ [cs (ghthds msg (h1hdr* ACCEPT_CHARSET))]
      (or (->> (c/split cs "[,;\\s]+")
               (some #(c/try! (u/charset?? %)))) (u/charset??))))
  (get-charset [msg]
    (HttpUtil/getCharset msg (Charset/forName "utf-8")))
  (get-header [msg h] (ghthds msg ^CharSequence h))
  (has-header? [msg h] (.contains (hthds msg) ^CharSequence h))
  (add-header [msg h v] (ahthds msg ^CharSequence h v))
  (add-headers [msg ^HttpHeaders hs] (.add (hthds msg) hs))
  (set-headers [msg ^HttpHeaders hs] (.set (hthds msg) hs))
  (set-header [msg h v] (shthds msg ^CharSequence h v))
  (get-headers [msg] (hthds msg))
  (get-method [msg]
    (if-some [req (c/cast? HttpRequest msg)]
      (keyword (c/lcase (c/stror (ghthds req "X-HTTP-Method-Override")
                                 (.. req getMethod name))))))
  (get-uri-path [msg]
    (if-some [req (c/cast? HttpRequest msg)]
      (.path (QueryStringDecoder. (.uri req))) ""))
  (get-uri-params [msg]
    (if-some [req (c/cast? HttpRequest msg)]
      (.parameters (QueryStringDecoder.  (.uri req)))))
  (crack-cookies [msg]
    (c/condp?? instance? msg
      HttpRequest
      (c/preduce<map>
        #(assoc! %1
                 (.name ^Cookie %2)
                 (http-cookie<> %2))
        (->> (str (ghthds msg (h1hdr* COOKIE)))
             (.decode ServerCookieDecoder/STRICT)))
      HttpResponse
      (c/preduce<map>
        #(if-some [v (.decode ClientCookieDecoder/STRICT %2)]
           (assoc! %1 (.name v) (http-cookie<> v)) %1)
        (.getAll (hthds msg) (h1hdr* SET_COOKIE)))))
  (no-content? [msg]
    (or (not (HttpUtil/isContentLengthSet msg))
        (not (> (HttpUtil/getContentLength msg -1) 0))))
  (content-type [msg]
    (c/stror (ghthds msg (h1hdr* CONTENT_TYPE)) ""))
  (content-length-as-int [msg]
    (HttpUtil/getContentLength msg (int 0)))
  (content-length! [msg ^long len]
    (HttpUtil/setContentLength msg (long len))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- akey*

  [k] (let [s (c/sname k)]
        (if-not (AttributeKey/exists s)
          (AttributeKey/newInstance s) (AttributeKey/valueOf s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dbg-ref-count

  "Show ref-count of object."
  [obj] (if-some
          [r (c/cast? ReferenceCounted obj)]
          (l/debug "object %s: has refcount: %s." obj (.refCnt r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

