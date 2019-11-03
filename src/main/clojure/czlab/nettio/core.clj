;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.nettio.core

  (:refer-clojure :exclude [get-method])

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.niou.core :as cc]
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
            HttpData
            FileUpload
            DiskAttribute
            DiskFileUpload]
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
           [java.util List]
           [java.net
            InetAddress
            URL
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
(defmacro chopt*
  [opt] `(ChannelOption/valueOf (str ~opt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- gandc
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
  (del-akey [_ k] "")
  (get-akey [_ k] "")
  (set-akey [_ k v] ""))

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
  (safe-remove-handler [_ arg] "")
  (safe-remove-handler* [_ args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpMessageAPI
  (get-uri-path [_] "")
  (get-uri-params [_] "")
  (get-msg-charset [_] "")
  (get-method [_] "")
  (add-header [_ h v] "")
  (set-header [_ h v] "")
  (get-header [_ h] "")
  (get-headers [_] "")
  (has-header? [_ h] "")
  (set-headers [_ hs] "")
  (crack-cookies [_] "")
  (no-content? [_] "")
  (content-type [_] "")
  (content-length! [_ len] "")
  (content-length-as-int [_] "")
  (detect-acceptable-charset [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol BootstrapAPI (nobs! [_ ch] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro num->status
  [c] `(io.netty.handler.codec.http.HttpResponseStatus/valueOf ~c))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro fire-msg
  "Inline fireChannelRead."
  [ctx msg]
  `(let [m# ~msg]
     (if m#
       (.fireChannelRead ~(with-meta ctx {:tag 'io.netty.channel.ChannelHandlerContext}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro write-msg
  "Inline writeAndFlush."
  [c msg]
  `(let [m# ~msg]
     (if m#
       (.writeAndFlush ~(with-meta c {:tag 'io.netty.channel.ChannelOutboundInvoker}) m#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hreq?
  [m] `(instance? ~'HttpRequest ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hrsp?
  [m] `(instance? ~'HttpResponse ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdr*
  [name] `~(symbol (str "HttpHeaderNames/"  (str name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdv*
  [name] `~(symbol (str "HttpHeaderValues/"  (str name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro scode*
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
  [] `io.netty.channel.ChannelFutureListener/CLOSE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-del
  "" [r] `(io.netty.util.ReferenceCountUtil/release ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-add
  "" [r] `(io.netty.util.ReferenceCountUtil/retain ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<e>
  "ChannelFuture - exception."
  []
  `io.netty.channel.ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro akey<>
  "New Attribute."
  [n] `(io.netty.util.AttributeKey/newInstance (name ~n)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro gattr
  [attr] `(czlab.nettio.core/get-http-data ~attr true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro mg-headers??
  "Get the Headers map." [msg] `(:headers ~msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ht-headers??
  "Get the HttpHeaders object." [msg] `(.headers ~msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce ^AttributeKey dfac-key (akey<> :data-factory))
(defonce ^AttributeKey cc-key  (akey<> :wsock-client))
(defonce ^AttributeKey req-key (akey<> :request))
;;(defonce ^AttributeKey h1msg-key (akey<> :h1req))
(defonce ^AttributeKey routes-key (akey<> :cracker))
(defonce ^AttributeKey chcfg-key (akey<> :ch-config))
(defonce ^String body-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cfg-ctx-bldr
  ^SslContextBuilder
  [^SslContextBuilder b h2Only?]
  (let [^List
        ps (u/x->java (if h2Only?
                        [ApplicationProtocolNames/HTTP_2]
                        [ApplicationProtocolNames/HTTP_2
                         ApplicationProtocolNames/HTTP_1_1]))]
    (.sslProvider b
                  ^SslProvider
                  (if (OpenSsl/isAlpnSupported)
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn put-post?
  "Http PUT or POST?"
  [x] (or (= x :post)(= x :put)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn app-handler
  ^ChannelHandler
  [user-handler user-cb]
  (or (c/cast? ChannelHandler user-handler)
      (and user-cb
           (proxy [InboundHandler][]
             (readMsg [ctx msg] (user-cb ctx msg))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol BootstrapAPI
  Bootstrap
  (nobs! [bs ch]
    (c/try! (if (and ch
                     (.isOpen ^Channel ch))
              (.close ^Channel ch)))
    (if-some [bs' (c/cast? ServerBootstrap bs)]
      (c/try! (.. bs' config childGroup shutdownGracefully)))
    (c/try! (.. bs config group shutdownGracefully))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn client-ssl??
  [^ChannelPipeline pp server-cert
   {:keys [scheme protocol] :as args}]
  (l/info "server-cert = %s." server-cert)
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
               (c/hgl? server-cert)
               (cs/starts-with? server-cert "file:"))
      (c/let#nil [^SslContextBuilder b (bld-ctx)
                  ^SslContext ctx
                  (if-not (.equals "2" protocol)
                    (.build b)
                    (.build (cfg-ctx-bldr b true)))]
        (.addLast pp "ssl" (.newHandler ctx (.. pp channel alloc)))))))

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
             (and (c/hgl? keyfile)
                  (cs/starts-with? keyfile "file:"))
             (let [t (->> (TrustManagerFactory/getDefaultAlgorithm)
                          TrustManagerFactory/getInstance)
                   k (->> (KeyManagerFactory/getDefaultAlgorithm)
                          KeyManagerFactory/getInstance)
                   cpwd (some-> passwd i/x->chars)
                   ks (KeyStore/getInstance
                        (if (cs/ends-with? key ".jks") "JKS" "PKCS12"))]
               (c/wo* [inp (io/input-stream (URL. ^String keyfile))]
                 (.load ks inp cpwd)
                 (.init t ks)
                 (.init k ks cpwd))
               (.trustManager (SslContextBuilder/forServer k) t))
             :else
             (u/throw-BadArg "Invalid keyfile path: %s" keyfile)))]
    (c/let#nil [^SslContextBuilder b (bld-ctx)]
      (.addLast pp
                "ssl"
                (-> (.build (cfg-ctx-bldr b false))
                    (.newHandler (.. pp channel alloc)))))))

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
  czlab.niou.core.Http1xMsg
  (msg-header-keys [msg]
    (keys (mg-headers?? msg)))
  (msg-header-vals [msg h]
    (get (mg-headers?? msg) (str h)))
  (msg-header [msg h]
    (first (cc/msg-header-vals msg h)))
  (msg-header? [msg h]
    (contains? (mg-headers?? msg) (str h))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol AttributeKeyAPI
  ChannelHandlerContext
  (del-akey [_ key]
    (del-akey (.channel _) key))
  (get-akey [_ key]
    (get-akey (.channel _) key))
  (set-akey [_ key val]
    (set-akey (.channel _) key val))
  Channel
  (del-akey [_ key]
    (set-akey _ key nil))
  (get-akey [_ key]
    (some-> (.attr _ ^AttributeKey key) .get))
  (set-akey [_ key val]
    (some-> (.attr _ ^AttributeKey key) (.set val)) val))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(let [h (InetAddress/getLocalHost)
      a (InetAddress/getLoopbackAddress)]
  (def ^String host-loopback-name (.getHostName a))
  (def ^String lhost-name (.getHostName h))
  (def ^String lhost-addr (.getHostAddress h))
  (def ^String host-loopback-addr (.getHostAddress a)))

(if false
  (do
    (println "lhost name= " lhost-name)
    (println "lhost addr= " lhost-addr)
    (println "loop name= " host-loopback-name)
    (println "loop addr= " host-loopback-addr)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fake-req<>
  "Fake a http-11 request."
  ^HttpRequest []
  (DefaultHttpRequest. HttpVersion/HTTP_1_1
                       HttpMethod/POST "/" (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dfac??
  ^HttpDataFactory [ctx] (get-akey ctx dfac-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn retain!
  ^ByteBufHolder [^ByteBufHolder part] (.. part content retain))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-http-data
  "Get content and if it's a file, rename to self ,trick code to not delete the file."
  ([d] (get-http-data d nil))
  ([^HttpData d wrap?]
   (let [f? (c/is? FileUpload d)
         x (when d
             (if (.isInMemory d)
               (.get d)
               (c/doto->> (.getFile d)
                          (.renameTo d))))
         r (if-not (and f?
                        (bytes? x))
             x
             (i/spit-file (i/temp-file) x true))]
     (if wrap? (XData. r) r))))

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
(defn decoder-result
  ^DecoderResult [msg]
  (some-> (c/cast? DecoderResultProvider msg) .decoderResult))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-success?
  [msg] (if-some [r (decoder-result msg)] (.isSuccess r) true))

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
  (let [c (get-akey ctx routes-key)
        {u :uri u2 :uri2 m :request-method} msg]
    (l/debug "match route for path: %s." u2)
    (or (if (and c
                 (c/hgl? u2)
                 (cr/rc-has-routes? c))
          (cr/rc-crack-route c
                             {:uri u
                              :request-method m})) {:status? true})))

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
  (let [cs (u/charset?? encoding)
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
       (.write inv LastHttpContent/EMPTY_LAST_CONTENT)
       (.writeAndFlush inv LastHttpContent/EMPTY_LAST_CONTENT))))
  (reply-status
    ([inv status] (reply-status inv status false))
    ([inv] (reply-status inv 200))
    ([inv status keepAlive?]
     (let [rsp (http-reply<+> status)
           code (.. rsp status code)
           ka? (if-not (and (>= code 200)
                            (< code 300)) false keepAlive?)]
       (l/debug "returning status [%s]." status)
       (HttpUtil/setKeepAlive rsp ka?)
       ;(HttpUtil/setContentLength rsp 0)
       (cf-close (.writeAndFlush inv rsp) ka?))))
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
       (.set (.headers rsp) (h1hdr* LOCATION) location)
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
  (safe-remove-handler* [pipe args]
    (doseq [a args]
      (safe-remove-handler pipe a)))
  (safe-remove-handler [pipe h]
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
    ;;(.setComment (.getComment c))
    (.setDomain (.getDomain c))
    (.setMaxAge (.getMaxAge c))
    (.setPath (.getPath c))
    ;;(.setDiscard (.getDiscard c))
    ;;(.setVersion 0)
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-cookie<>
  ^HttpCookie [^Cookie c]
  (doto (HttpCookie. (.name c) (.value c))
    ;;(.setComment (.comment c))
    (.setDomain (.domain c))
    (.setMaxAge (.maxAge c))
    (.setPath (.path c))
    ;;(.setVersion (.getVersion c))
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol HttpMessageAPI
  HttpMessage
  (detect-acceptable-charset [msg]
    (c/when-some+ [cs (some-> (c/cast? HttpRequest msg)
                              .headers
                              (.get (h1hdr* ACCEPT_CHARSET)))]
      (or (->> (c/split cs "[,;\\s]+")
               (some #(c/try! (u/charset?? %)))) (u/charset??))))
  (get-msg-charset [msg]
    (HttpUtil/getCharset msg (Charset/forName "utf-8")))
  (get-header [msg h] (.get (.headers msg) ^String h))
  (has-header? [msg h] (.contains (.headers msg) ^String h))
  (add-header [msg h v] (.add (.headers msg) ^String h v))
  (set-headers [msg hs] (.set (.headers msg) ^HttpHeaders hs))
  (set-header [msg h v] (.set (.headers msg) ^String h v))
  (get-headers [msg] (.headers msg))
  (get-method [msg]
    (if-some [req (c/cast? HttpRequest msg)]
      (keyword (c/lcase (c/stror (.get (.headers req) "X-HTTP-Method-Override")
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
      (c/if-some+
        [v (.get (.headers ^HttpRequest msg) (h1hdr* COOKIE))]
        (c/preduce<map>
          #(assoc! %1
                   (.name ^Cookie %2)
                   (http-cookie<> %2))
          (.decode ServerCookieDecoder/STRICT v)))
      HttpResponse
      (c/preduce<map>
        #(let [v (.decode ClientCookieDecoder/STRICT %2)]
           (assoc! %1
                   (.name v)
                   (http-cookie<> v)))
        (.getAll (.headers ^HttpResponse msg) (h1hdr* SET_COOKIE)))))
  (no-content? [msg]
    (or (not (HttpUtil/isContentLengthSet msg))
        (not (> (HttpUtil/getContentLength msg -1) 0))))
  (content-type [msg]
    (-> (.headers msg)
        (.get (h1hdr* CONTENT_TYPE) "")))
  (content-length-as-int [msg]
    (HttpUtil/getContentLength msg (int 0)))
  (content-length! [msg len]
    (HttpUtil/setContentLength msg (long len))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/WsockMsgReplyer
  Channel
  (send-ws-string [inv s]
    (write-msg inv (TextWebSocketFrame. ^String s)))
  (send-ws-bytes [inv b]
    (write-msg inv (BinaryWebSocketFrame. (x->bbuf inv b)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- akey??
  [k] (let [s (c/sname k)]
        (if-not (AttributeKey/exists s)
          (AttributeKey/newInstance s) (AttributeKey/valueOf s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/SocketAttrProvider
  Channel
  (socket-attr-get [c k] (get-akey c (akey?? k)))
  (socket-attr-del [c k] (del-akey c (akey?? k)))
  (socket-attr-set [c k a] (set-akey c (akey?? k) a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dbg-ref-count
  "Show ref-count of object." [obj]
  (if-some
    [rc (c/cast? ReferenceCounted obj)]
    (l/debug "object %s: has refcount: %s." obj (.refCnt rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


