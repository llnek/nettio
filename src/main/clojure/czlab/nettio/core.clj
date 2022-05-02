;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; Copyright © 2013-2022, Kenneth Leung. All rights reserved.

(ns czlab.nettio.core

  (:refer-clojure :exclude [get-method])

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.util :as u]
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
           [java.util Map List]
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

  "Get headers from message."
  {:tag Headers
   :arglists '([msg])}
  [msg]

  (:headers msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro chcfg??

  "Get config info from channel's attr."
  {:arglists '([ctx])}
  [ctx]

  `(czlab.nettio.core/akey?? ~ctx czlab.nettio.core/chcfg-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cache??

  "Get data cache from channel's attr."
  {:arglists '([ctx])}
  [ctx]

  `(czlab.nettio.core/akey?? ~ctx czlab.nettio.core/cache-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro isH2?

  "Is protocol http2."
  {:arglists '([protocol])}
  [protocol]

  `(.equals "2" ~protocol))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ihprd?

  "Isa InterfaceHttpPostRequestDecoder?"
  {:arglists '([impl])}
  [impl]

  `(instance? io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder ~impl))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro offer!

  "InterfaceHttpPostRequestDecoder.offer."
  {:arglists '([impl part])}
  [impl part]

  `(.offer
     ~(with-meta
        impl {:tag 'io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder}) ~part))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro mp-attr?

  "Isa Attribute?"
  {:arglists '([impl])}
  [impl]

  `(instance? io.netty.handler.codec.http.multipart.Attribute ~impl))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro add->mp-attr!

  "Add content to Attribute."
  {:arglists '([impl part last?])}
  [impl part last?]

  `(.addContent ~(with-meta impl
                            {:tag 'io.netty.handler.codec.http.multipart.Attribute})
                (czlab.nettio.core/retain! ~part) ~last?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro set-akey*

  "Clear a set of attribute keys."
  {:arglists '([ctx & args])}
  [ctx & args]

  `(do ~@(map (fn [[k v]]
                `(czlab.nettio.core/akey+ ~ctx ~k ~v)) args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro del-akey*

  "Clear a set of attribute keys."
  {:arglists '([ctx & args])}
  [ctx & args]

  `(do ~@(map (fn [k]
                `(czlab.nettio.core/akey- ~ctx ~k)) args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn chopt*

  "Morph into a netty's ChannelOption enum."
  {:tag ChannelOption
   :arglists '([opt])}
  [opt]

  (ChannelOption/valueOf (name opt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro new-promise

  "Make a new promise."
  {:arglists '([ctx])}
  [ctx]

  `(.newPromise ~(with-meta ctx
                            {:tag 'io.netty.channel.ChannelHandlerContext})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- gandc

  "Group and Channel info."
  {:arglists '([e n])}
  [e n]

  `(array-map :epoll ~e :nio ~n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn group+channel

  "Event group & Channel type."
  {:arglists '([t kind])}
  [t kind]

  (when-some
    [{:keys [epoll nio]}
     ({:tcps (gandc EpollServerSocketChannel NioServerSocketChannel)
       :tcpc (gandc EpollSocketChannel NioSocketChannel)
       :udps (gandc EpollDatagramChannel NioDatagramChannel)} kind)]
    (c/info "netty bootstraped with [%s]."
            (if (Epoll/isAvailable) "EPoll" "Java/NIO"))
    (if-not (Epoll/isAvailable)
      [(NioEventLoopGroup. (int t)) nio]
      [(EpollEventLoopGroup. (int t)) epoll])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro num->status

  "Morph INT into netty's http response object."
  {:arglists '([n])}
  [n]

  `(io.netty.handler.codec.http.HttpResponseStatus/valueOf ~n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro fire-msg

  "Inline fireChannelRead."
  {:arglists '([ctx msg])}
  [ctx msg]

  `(let [m# ~msg]
     (if m#
       (.fireChannelRead
         ~(with-meta ctx
                     {:tag 'io.netty.channel.ChannelHandlerContext}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro write-msg*

  "Inline write."
  {:arglists '([c msg])}
  [c msg]

  `(let [m# ~msg]
     (if m#
       (.write
         ~(with-meta c
                     {:tag 'io.netty.channel.ChannelOutboundInvoker}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro write-msg

  "Inline writeAndFlush."
  {:arglists '([c msg])}
  [c msg]

  `(let [m# ~msg]
     (if m#
       (.writeAndFlush
         ~(with-meta c
                     {:tag 'io.netty.channel.ChannelOutboundInvoker}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hv-int

  "Get header value as int."
  {:arglists '([h k])}
  [h k]

  `(czlab.basal.core/try!
     (Integer/parseInt
       (.getFirst ~(with-meta h {:tag 'Headers}) ~k))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hv->int

  "Get header value as int or default."
  {:arglists '([h k dv])}
  [h k dv]

  `(int (or (hv-int ~h ~k) ~dv)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hreq?

  "Isa HttpRequest?"
  {:arglists '([m])}
  [m]

  `(instance? ~'HttpRequest ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hrsp?

  "Isa HttpResponse?"
  {:arglists '([m])}
  [m]

  `(instance? ~'HttpResponse ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdr*

  "Morph into a HttpHeaderName."
  {:arglists '([name])}
  [name]

  `~(symbol (str "HttpHeaderNames/"  (str name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdv*

  "Morph into a HttpHeaderValue."
  {:arglists '([name])}
  [name]

  `~(symbol (str "HttpHeaderValues/"  (str name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro scode*

  "Morph into a HttpResponseStatus then cast to INT."
  {:arglists '([status])}
  [status]

  `(.code ~(symbol (str "HttpResponseStatus/"  (str status)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<x>

  "ChannelFuture - close-error."
  {:arglists '([])}
  []

  `io.netty.channel.ChannelFutureListener/CLOSE_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<z>

  "ChannelFuture - close-success."
  {:arglists '([])}
  []

  `io.netty.channel.ChannelFutureListener/CLOSE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-del

  "Release a reference-counted object."
  {:arglists '([r])}
  [r]

  `(io.netty.util.ReferenceCountUtil/release ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-add

  "Increase reference-count."
  {:arglists '([r])}
  [r]

  `(io.netty.util.ReferenceCountUtil/retain ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<e>

  "ChannelFuture - exception."
  {:arglists '([])}
  []

  `io.netty.channel.ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro akey<>

  "New Attribute."
  {:arglists '([n])}
  [n]

  `(io.netty.util.AttributeKey/newInstance (name ~n)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro gattr

  "Get actual data inside an Attribute."
  {:arglists '([attr])}
  [attr]

  `(czlab.nettio.core/get-http-data ~attr true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- hthds

  [msg] `(.headers ~(with-meta msg {:tag 'HttpMessage})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- ghthds

  [msg h] `(.get (.headers ~(with-meta msg {:tag 'HttpMessage})) ~h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- shthds

  [msg h v] `(.set (.headers ~(with-meta msg {:tag 'HttpMessage})) ~h ~v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- ahthds

  [msg h v] `(.add (.headers ~(with-meta msg {:tag 'HttpMessage})) ~h ~v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^String body-id "--body--")
(def ^String user-cb "user-cb")
(defonce ^AttributeKey dfac-key (akey<> :data-factory))
(defonce ^AttributeKey cc-key  (akey<> :wsock-client))
(defonce ^AttributeKey routes-key (akey<> :cracker))
(defonce ^AttributeKey corscfg-key (akey<> :corscfg))
(defonce ^AttributeKey origin-key (akey<> :origin))
(defonce ^AttributeKey chcfg-key (akey<> :ch-config))
(defonce ^AttributeKey cache-key (akey<> :ch-cache))

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

  "Add a handler to the end of the pipeline."
  {:arglists '([p n h])}
  [p n h]

  (c/debug "add-last %s/%s to ch-pipeline." n (u/gczn h))
  (.addLast ^ChannelPipeline p ^String n ^ChannelHandler h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pp->next

  "Insert a handler after the current one."
  {:arglists '([p a n h])}
  [p a n h]

  (c/debug "add-after %s %s/%s to ch-pipeline." a n (u/gczn h))
  (.addAfter ^ChannelPipeline p ^String a ^String n ^ChannelHandler h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn data-attr<>

  "Create a Mixed Attribute for storage."
  {:tag Attribute
   :arglists '([size])}
  [size]

  (MixedAttribute. body-id size))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1msg?

  "If the message is a Http 1.x message?"
  {:arglists '([msg])}
  [msg]

  (c/or?? [instance? msg]
          HttpContent HttpRequest HttpResponse))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1end?

  "If this is a full-response or last-content?"
  {:arglists '([msg])}
  [msg]

  (c/or?? [instance? msg]
          LastHttpContent FullHttpResponse))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn put-post?

  "Http PUT or POST?"
  {:arglists '([x])}
  [x]

  (or (= x :post)(= x :put)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn app-handler

  "Create a handler based on the user callback."
  {:tag ChannelHandler
   :arglists '([user-cb])}
  [user-cb]
  {:pre [(fn? user-cb)]}

  (proxy [InboundHandler][]
    (onRead [_ _ msg] (user-cb msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn nobs!

  "Shutdown the bootstrap and channel."
  {:arglists '([bs ch])}
  [bs ch]
  {:pre [(c/is? Bootstrap bs)]}

  (let [ch (c/cast? Channel ch)
        bs (c/cast? Bootstrap bs)]
    (c/try! (if (some-> ch
                        .isOpen) (.close ch)))
    (if-some
      [bs' (c/cast? ServerBootstrap bs)]
      (c/try! (.. bs' config childGroup shutdownGracefully)))
    (c/try! (.. bs config group shutdownGracefully))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn client-ssl??

  "Maybe set up client-pipeline for SSL?"
  {:arglists '([pp server-cert args])}
  [pp
   server-cert
   {:keys [scheme protocol] :as args}]
  {:pre [(c/is? ChannelPipeline pp)]}

  (c/info "protocol = %s, server-cert = %s." protocol server-cert)
  (letfn
    [(ccerts [in]
       (let [[d? inp] (i/input-stream?? in)]
         (try (->> (-> (CertificateFactory/getInstance "X.509")
                       (.generateCertificates ^InputStream inp))
                   (c/vargs X509Certificate))
              (finally (if d? (i/klose inp))))))
     (bld-ctx []
       (let [ctx (SslContextBuilder/forClient)]
         (if-not (c/eq? "*" server-cert)
           (.trustManager ctx
                          #^"[Ljava.security.cert.X509Certificate;"
                          (ccerts (io/as-url server-cert)))
           (.trustManager ctx InsecureTrustManagerFactory/INSTANCE))))]
    (when (and (c/!eq? "http" scheme)
               (or (c/eq? "*" server-cert)
                   (some-> server-cert
                           (cs/starts-with? "file:"))))
      (c/let->nil [^SslContextBuilder b (bld-ctx)
                  ^SslContext ctx
                  (if-not (c/eq? "2" protocol)
                    (.build b)
                    (.build (cfg-ctx-bldr b true)))]
        (pp->last pp "ssl"
                  (.newHandler ctx
                               (.. ^ChannelPipeline pp channel alloc)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn server-ssl??

  "Maybe set up client-pipeline for SSL?"
  {:arglists '([pp keyfile passwd args])}
  [pp keyfile passwd args]
  {:pre [(c/is? ChannelPipeline pp)]}

  (c/info "server-key = %s." keyfile)
  (letfn
    [(bld-ctx []
       (cond (c/eq? "*" keyfile)
             (let [c (SelfSignedCertificate.)]
               (SslContextBuilder/forServer (.certificate c)
                                            (.privateKey c)))
             (some-> keyfile
                     (cs/starts-with? "file:"))
             (let [t (->> (TrustManagerFactory/getDefaultAlgorithm)
                          TrustManagerFactory/getInstance)
                   k (->> (KeyManagerFactory/getDefaultAlgorithm)
                          KeyManagerFactory/getInstance)
                   cpwd (i/x->chars passwd)
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
    (c/let->nil [^SslContextBuilder b (bld-ctx)]
      (pp->last pp
                "ssl"
                (-> (.build (cfg-ctx-bldr b false))
                    (.newHandler (.. ^ChannelPipeline pp channel alloc)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cfop<>

  "Create a ChannelFutureListener."
  {:arglists '([func])
   :tag ChannelFutureListener}
  [func]
  {:pre [(fn? func)]}

  (reify ChannelFutureListener
    (operationComplete [_ ff] (c/try! (func ff)))))

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
(defn akey+

  "Set a Channel Attibute."
  {:arglists '([in key val])}
  [in key val]

  (cond
    Channel
    (some-> ^Channel in
            (.attr ^AttributeKey key) (.set val))
    ChannelHandlerContext
    (akey+ (.channel ^ChannelHandlerContext in) key val)) val)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn akey-

  "Remove a Channel Attribute."
  {:arglists '([in key])}
  [in key]

  (cond
    Channel
    (akey+ in key nil)
    ChannelHandlerContext
    (akey- (.channel ^ChannelHandlerContext in) key)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn akey??

  "Get a Channel Attribute value."
  {:arglists '([in key])}
  [in key]

  (cond
    Channel
    (some-> ^Channel in
            (.attr ^AttributeKey key) .get)
    ChannelHandlerContext
    (akey?? (.channel ^ChannelHandlerContext in) key)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn host-loopback-name

  "Get loop-back host name."
  {:tag String
   :arglists '([])}
  []

  (.getHostName (InetAddress/getLoopbackAddress)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn host-loopback-addr

  "Get loop-back host address."
  {:tag String
   :arglists '([])}
  []

  (.getHostAddress (InetAddress/getLoopbackAddress)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn lhost-name

  "Get local host name."
  {:tag String
   :arglists '([])}
  []

  (.getHostName (InetAddress/getLocalHost)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn lhost-addr

  "Get local host address."
  {:tag String
   :arglists '([])}
  []

  (.getHostAddress (InetAddress/getLocalHost)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fake-req<>

  "Fake a http-11 request."
  {:arglists '([])
   :tag HttpRequest}
  []

  (DefaultHttpRequest. HttpVersion/HTTP_1_1
                       HttpMethod/POST "/" (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dfac??

  "Get the default Http data factory."
  {:arglists '([ctx])
   :tag HttpDataFactory}
  [ctx]

  (akey?? ctx dfac-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn retain!

  "Up the reference count on this object's content."
  {:tag ByteBufHolder
   :arglists '([part])}
  [part]

  (.. ^ByteBufHolder part content retain))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-http-data

  "Get content and if it's a file,
  rename to self,
  trick code to not delete the file."
  {:arglists '([d]
               [d wrap?])}

  ([d]
   (get-http-data d nil))

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

  "Decode the form-multipart in the request."
  {:arglists '([deco])}
  [^InterfaceHttpPostRequestDecoder deco]

  (c/debug (str "parse-post, decoder= %s,"
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

  "Get data from multipart attribute."
  {:arglists '([a])}
  [a]

  (c/do-with [r (get-http-data a)] (ref-del a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn config-disk-files

  "Configure temp-files repo."
  {:arglists '([delExit? fDir])}
  [delExit? fDir]

  (set! DiskFileUpload/deleteOnExitTemporaryFile false)
  (set! DiskAttribute/deleteOnExitTemporaryFile false)
  (set! DiskFileUpload/baseDirectory fDir)
  (set! DiskAttribute/baseDirectory fDir)
  (c/info "netty's temp-file-repo: %s." fDir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro last-part?

  "If this is the last part of a message?"
  {:arglists '([p])}
  [p]

  `(instance? io.netty.handler.codec.http.LastHttpContent ~p))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-result

  "Get the decoder-result from this message."
  {:arglists '([msg])
   :tag DecoderResult}
  [msg]

  (some-> (c/cast? DecoderResultProvider msg) .decoderResult))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro decoder-err?

  "If the decoder-result has error?"
  {:arglists '([m])}
  [m]

  `(not (decoder-ok? ~m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro decoder-err-cause??

  "Get the decoder-result's error, if any?"
  {:arglists '([m])}
  [m]

  `(if-some
     [~'r (czlab.nettio.core/decoder-result ~m)] (.cause ~'r)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro decoder-ok?

  "If the decoder-result indicates success?"
  {:arglists '([m])}
  [m]

  `(if-some
     [~'r (czlab.nettio.core/decoder-result ~m)] (.isSuccess ~'r) true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-ssl??

  "Get the SSL handler, if installed?"
  {:tag SslHandler
   :arglists '([arg])}
  [arg]

  (c/condp?? instance? arg
    ChannelPipeline (.get ^ChannelPipeline arg SslHandler)
    Channel (get-ssl?? (.pipeline ^Channel arg))
    ChannelHandlerContext (get-ssl?? (.pipeline ^ChannelHandlerContext arg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn match-one-route??

  "Match the path to a route."
  {:arglists '([ctx msg])}
  [ctx msg]

  (letfn
    [(remapper [{:keys [remap]} params]
       (when (c/hgl? remap)
         (c/sreduce<>
           #(if (.equals "/" %2)
              (c/sbf+ %1 %2)
              (c/sbf+ %1
                      (if-some [[_ k]
                                (re-matches cr/place-holder %2)]
                        (str (get params (keyword k)))
                        %2)))
           (c/split-str (c/strim remap) "/" true))))]
    (let [c (akey?? ctx routes-key)
          {u2 :uri2
           m :request-method} msg
          path (.getPath ^URI u2)
          _ (c/debug "%s for path: %s."
                     "route matching" path)
          rc (if-not (and c
                          (c/hgl? path)
                          (cr/has-routes? c))
               :pass-through
               (or (cr/crack-route c
                                   {:uri path
                                    :request-method m}) :no-match))]
      (if-not (map? rc)
        rc
        (let [{:keys [info params]} rc
              {:keys [remap]} info
              r (remapper info params)]
          (if (c/nichts? r) rc (assoc rc :rewrite r)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cpipe??

  "Convert input to a ChannelPipeline."
  {:arglists '([c])
   :tag ChannelPipeline}
  [c]

  (c/condp?? instance? c
    ChannelPipeline c
    Channel (.pipeline ^Channel c)
    ChannelHandlerContext (.pipeline ^ChannelHandlerContext c)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ch??

  "Convert the input to Channel."
  {:tag Channel
   :arglists '([arg])}
  [arg]

  (c/condp?? instance? arg
    Channel arg
    ChannelPipeline (.channel ^ChannelPipeline arg)
    ChannelHandlerContext (.channel ^ChannelHandlerContext arg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn close!

  "Try to close the channel."
  {:arglists '([c])}
  [c]

  (c/condp?? instance? c
    Channel (.close ^Channel c)
    ChannelHandlerContext (.close ^ChannelHandlerContext c)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn chanid

  "Get the channel-id."
  {:tag String
   :arglists '([c])}
  [c]

  (str (c/condp?? instance? c
         Channel
         (.id ^Channel c)
         ChannelHandlerContext
         (.. ^ChannelHandlerContext c channel id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn redirector

  "Send back a redirect response."
  {:arglists '([msg])}
  [{:keys [route uri2
           scheme
           local-host local-port] :as msg}]

  (let [host (cc/msg-header msg "host")
        {:keys [status location]}
        (get-in route [:info :redirect])
        target (if-not (cs/starts-with? location "/")
                 location
                 (str (name scheme)
                      "://"
                      (if (c/hgl? host)
                        host
                        (str local-host ":" local-port)) location))]
    (-> (cc/http-result msg status)
        (cc/res-header-set "Location" target)
        (cc/res-header-set "Connection" "close") cc/reply-result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn slurp-bytebuf

  "Read bytes into output."
  {:arglists '([buf out])}
  [buf out]
  {:pre [(c/is? ByteBuf buf)]}

  (let [buf (c/cast? ByteBuf buf)
        len (.readableBytes buf)
        out (c/cast? OutputStream out)]
    (if-not (pos? len)
      0
      (do (.readBytes buf
                      out
                      (int len)) (.flush out) len))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn bbuf->bytes

  "Read bytes from ByteBuf."
  {:arglists '([buf])}
  [buf]
  {:pre [(c/is? ByteBuf buf)]}

  (let [out (i/baos<>)]
    (if (pos? (slurp-bytebuf buf out)) (i/x->bytes out))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- x->bbuf

  ^ByteBuf
  [ch arg encoding]

  (let [cs (u/charset?? encoding "utf-8")
        buf (some-> (ch?? ch) .alloc .directBuffer)]
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

  "Best attempt to convert input to ByteBuf."
  {:arglists '([arg]
               [arg ch]
               [arg ch encoding])}

  ([arg ch]
   (bbuf?? arg ch nil))

  ([arg]
   (bbuf?? arg nil nil))

  ([arg ch encoding]
   (let [ct (if-some
              [c (c/cast? XData arg)] (.content c) arg)]
     (if (or (bytes? ct)
             (string? ct)) (x->bbuf ch ct encoding) ct))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-reply<>

  "Create an incomplete response"
  {:tag HttpResponse
   :arglists '([][code])}

  ([]
   (http-reply<> (scode* OK)))

  ([code]
   {:pre [(number? code)]}
   (DefaultHttpResponse. HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf code))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-reply<+>

  "Create a complete response."
  {:tag FullHttpResponse
   :arglists '([]
               [code]
               [code msg alloc])}

  ([code]
   (http-reply<+> code nil nil))

  ([]
   (http-reply<+> (scode* OK)))

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
(defn cf-cb

  "Bind a callback to the Future."
  {:arglists '([cf arg])}
  [cf arg]
  {:pre [(c/is? ChannelFuture cf)]}

  (if-some
    [ln (cond (c/is? ChannelFutureListener arg) arg
              (fn? arg) (cfop<> arg)
              (nil? arg) nil
              :else
              (u/throw-IOE "Invalid object %s." (type arg)))]
    (.addListener ^ChannelFuture cf ^ChannelFutureListener ln)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cf-close

  "Maybe close the Channel once Future returns."
  {:arglists '([cf]
               [cf keepAlive?])}

  ([cf]
   (cf-close cf false))

  ([cf keepAlive?]
   (if (not (boolean keepAlive?))
     (.addListener ^ChannelFuture cf ChannelFutureListener/CLOSE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn write-last-content

  "Write LastHttpContent to channel."
  {:arglist '([inv]
              [inv flush?])}

  ([inv]
   (write-last-content inv false))

  ([inv flush?]
   {:pre [(c/is? ChannelOutboundInvoker inv)]}
   (if-not flush?
     (write-msg* inv LastHttpContent/EMPTY_LAST_CONTENT)
     (write-msg inv LastHttpContent/EMPTY_LAST_CONTENT))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn reply-status

  "Reply back to client with status."
  {:arglists '([inv]
               [inv status]
               [inv status keepAlive?])}

  ([inv status]
   (reply-status inv status false))

  ([inv]
   (reply-status inv 200))

  ([inv status keepAlive?]
   {:pre [(c/is? ChannelOutboundInvoker inv)]}
   (let [rsp (http-reply<+> status)
         ka? (if-not (and (>= status 200)
                          (< status 300)) false keepAlive?)]
     (c/debug "returning status [%s]." status)
     (HttpUtil/setKeepAlive rsp ka?)
     (cf-close (write-msg inv rsp) ka?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn reply-redirect

  "Reply back to client with a redirect."
  {:arglists '([inv perm? location]
               [inv perm? location keepAlive?])}

  ([inv perm? location]
   (reply-redirect inv perm? location false))

  ([inv perm? location keepAlive?]
   {:pre [(c/is? ChannelOutboundInvoker inv)]}
   (let [ka? false
         rsp (http-reply<+>
               (if perm?
                 (scode* MOVED_PERMANENTLY)
                 (scode* TEMPORARY_REDIRECT)))]
     (c/debug "redirecting to -> %s." location)
     (shthds rsp (h1hdr* LOCATION) location)
     (HttpUtil/setKeepAlive rsp ka?)
     (cf-close (write-msg inv rsp) ka?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn continue-100

  "Reply back to a expect-100-continue."
  {:arglists '([inv])}
  [inv]
  {:pre [(c/is? ChannelOutboundInvoker inv)]}

  (.addListener (write-msg inv
                           (http-reply<+>
                             (scode* CONTINUE))) (cfop<e>)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ctx-name

  "Get the name of a ChannelHandler."
  {:arglists '([pipe h])}
  [pipe h]
  {:pre [(c/is? ChannelPipeline pipe)]}

  (some-> ^ChannelPipeline pipe
          (.context ^ChannelHandler h) .name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dbg-pipeline

  "Debug show the handlers in the pipeline."
  {:arglists '([pipe])}
  [pipe]
  {:pre [(c/is? ChannelPipeline pipe)]}

  (c/debug "pipeline= %s"
           (cs/join "|" (.names ^ChannelPipeline pipe))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn remove-handler

  "Remove a handler from the pipeline."
  {:arglists '([pipe h])}
  [pipe h]
  {:pre [(c/is? ChannelPipeline pipe)]}

  (let [pipe (c/cast? ChannelPipeline pipe)]
    (c/try! (c/condp?? instance? h
              String (.remove pipe ^String h)
              Class  (.remove pipe ^Class h)
              ChannelHandler (.remove pipe ^ChannelHandler h)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn remove-handler*

  "Remove these handlers from the pipeline."
  {:arglists '([pipe args])}
  [pipe args]
  {:pre [(c/is? ChannelPipeline pipe)]}

  (doseq [a args] (remove-handler pipe a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-cookie<>

  "Stick with version 0, Java's HttpCookie defaults to 1 but that
  screws up the Path attribute on the wire => it's quoted but
  browser seems to not like it and mis-interpret it.
  Netty's cookie defaults to 0, which is cool with me."
  {:tag Cookie
   :arglists '([c])}
  [^HttpCookie c]

  (c/debug "cookie->netty: %s=[%s]."
           (.getName c) (.getValue c))
  (doto (DefaultCookie. (.getName c)
                        (.getValue c))
    (.setDomain (.getDomain c))
    (.setMaxAge (.getMaxAge c))
    (.setPath (.getPath c))
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-cookie<>

  "Convert a Netty cookie to Java's http cookie."
  {:tag HttpCookie
   :arglists '([c])}
  [^Cookie c]

  (doto (HttpCookie. (.name c)
                     (.value c))
    (.setDomain (.domain c))
    (.setMaxAge (.maxAge c))
    (.setPath (.path c))
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn detect-charset

  "Get the first accepted charset from message."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (c/when-some+ [cs (ghthds ^HttpMessage msg
                            (h1hdr* ACCEPT_CHARSET))]
    (or (->> (c/split cs "[,;\\s]+")
             (some #(c/try! (u/charset?? %)))) (u/charset??))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-charset

  "Get the charset from content-type."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (HttpUtil/getCharset ^HttpMessage msg (Charset/forName "utf-8")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-header

  "Get header from message."
  {:arglists '([msg h])}
  [msg h]
  {:pre [(c/is? HttpMessage msg)]}

  (ghthds ^HttpMessage msg ^CharSequence h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn has-header?

  "If message has this header?"
  {:arglists '([msg h])}
  [msg h]
  {:pre [(c/is? HttpMessage msg)]}

  (.contains (hthds ^HttpMessage msg) ^CharSequence h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-header

  "Add a header to the message."
  {:arglists '([msg h v])}
  [msg h v]
  {:pre [(c/is? HttpMessage msg)]}

  (ahthds ^HttpMessage msg ^CharSequence h v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-headers

  "Add headers to the message."
  {:arglists '([msg hs])}
  [msg hs]
  {:pre [(c/is? HttpMessage msg)]}

  (if-some
    [hs (c/cast? HttpHeaders hs)] (.add (hthds ^HttpMessage msg) hs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-headers

  "Set headers to the message."
  {:arglists '([msg hs])}
  [msg hs]
  {:pre [(c/is? HttpMessage msg)]}

  (if-some
    [hs (c/cast? HttpHeaders hs)] (.set (hthds ^HttpMessage msg) hs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-header

  "Set a header to the message."
  {:arglists '([msg h v])}
  [msg h v]
  {:pre [(c/is? HttpMessage msg)]}

  (shthds ^HttpMessage msg ^CharSequence h v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-headers

  "Get headers from the message."
  {:tag HttpHeaders
   :arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (hthds ^HttpMessage msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-method

  "Get the request method, including any override."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (if-some [req (c/cast? HttpRequest msg)]
    (keyword (c/lcase (c/stror (ghthds req "X-HTTP-Method-Override")
                               (.. req getMethod name))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-uri-path

  "Get the decoded request URI."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (if-some [req (c/cast? HttpRequest msg)]
    (.path (QueryStringDecoder. (.uri req))) ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-uri-params

  "Get the parameters from the request."
  {:tag Map
   :arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (if-some [req (c/cast? HttpRequest msg)]
    (.parameters (QueryStringDecoder.  (.uri req)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn crack-cookies

  "Scan and decode cookies."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn no-content?

  "If the message has no content- length 0."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (let [msg (c/cast? HttpMessage msg)]
    (or (not (HttpUtil/isContentLengthSet msg))
        (not (> (HttpUtil/getContentLength msg -1) 0)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn content-type

  "Get the content-type header, if any."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (c/stror (ghthds ^HttpMessage msg (h1hdr* CONTENT_TYPE)) ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn content-length-as-int

  "Get the content-length."
  {:arglists '([msg])}
  [msg]
  {:pre [(c/is? HttpMessage msg)]}

  (HttpUtil/getContentLength ^HttpMessage msg (int 0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn content-length!

  "Set the content-length."
  {:arglists '([msg len])}
  [msg ^long len]
  {:pre [(c/is? HttpMessage msg)]}

  (HttpUtil/setContentLength msg (long len)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn akey*

  [k] (let [s (c/sname k)]
        (if-not (AttributeKey/exists s)
          (AttributeKey/newInstance s) (AttributeKey/valueOf s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dbg-ref-count

  "Show ref-count of object."
  {:arglists '([obj])}
  [obj]

  (if-some
    [r (c/cast? ReferenceCounted obj)]
    (c/debug "object %s: has refcount: %s." obj (.refCnt r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/ChannelAttrs
  io.netty.channel.Channel
  (setattr [me a v]
    (akey+ me (akey* a) v))
  (delattr [me a]
    (akey- me (akey* a)))
  (getattr [me a]
    (akey?? me (akey* a)))
  io.netty.channel.ChannelHandlerContext
  (setattr [me a v]
    (akey+ me (akey* a) v))
  (delattr [me a]
    (akey- me (akey* a)))
  (getattr [me a]
    (akey?? me (akey* a))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

