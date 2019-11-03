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
            [czlab.basal
             [util :as u]
             [log :as l]
             [io :as i]
             [core :as c]]
            [czlab.niou.core :as cc])

  (:import [clojure.lang APersistentMap APersistentSet APersistentVector]
           [io.netty.handler.codec DecoderResultProvider DecoderResult]
           [java.security.cert X509Certificate CertificateFactory]
           [javax.net.ssl KeyManagerFactory TrustManagerFactory]
           [io.netty.handler.codec.http2 Http2SecurityUtil]
           [java.io IOException OutputStream]
           [clojure.lang IDeref]
           [io.netty.handler.codec.http.websocketx
            TextWebSocketFrame
            BinaryWebSocketFrame]
           [io.netty.handler.codec.http.multipart
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
           [io.netty.channel.nio NioEventLoopGroup]
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
           [io.netty.channel.socket.nio
            NioDatagramChannel
            NioSocketChannel
            NioServerSocketChannel]
           [io.netty.channel.epoll
            Epoll
            EpollEventLoopGroup
            EpollDatagramChannel
            EpollSocketChannel
            EpollServerSocketChannel]
           [io.netty.handler.codec.http
            HttpVersion
            HttpMethod
            HttpUtil
            FullHttpResponse
            FullHttpRequest
            LastHttpContent
            HttpHeaderValues
            HttpHeaderNames
            HttpContent
            HttpMessage
            HttpResponse
            DefaultFullHttpResponse
            DefaultFullHttpRequest
            DefaultHttpResponse
            DefaultHttpHeaders
            DefaultHttpRequest
            HttpRequest
            HttpResponseStatus
            HttpHeaders
            QueryStringDecoder]
           [java.util HashMap Map Map$Entry]
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
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hreq?
  [m] `(instance? ~'HttpRequest ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro hrsp?
  [m] `(instance? ~'HttpResponse ~m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdr*
  [name]
  `(identity  ~(symbol (str "HttpHeaderNames/"  (str name)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro h1hdv*
  [name]
  `(identity  ~(symbol (str "HttpHeaderValues/"  (str name)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro scode*
  [status]
  `(.code ~(symbol (str "HttpResponseStatus/"  (str status)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ChannelXXXAPI
  ""
  (cpipe [_] "")
  (ch?? [_] "")
  (chanid [_] "")
  (close-ch [_] "")
  (fire-next-and-quit [_ h msg]
                      [_ h msg retain?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol AttributeKeyAPI
  ""
  (del-akey [_ arg] "")
  (get-akey [_ arg] "")
  (set-akey [_ arg aval] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HeadersAPI
  ""
  (del-header [_ nm] "")
  (clone-headers [_ headers] "")
  (set-header* [_ nvs] "")
  (add-header* [_ nvs] "")
  (add-header [_ nm value] "")
  (set-header [_ nm value] "")
  (get-header-vals [_ nm]  "")
  (get-header [_ nm]  "")
  (has-header? [_ nm]  ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ByteBufAPI
  ""
  (slurp-bytebuf [_ out] "")
  (bbuf->bytes [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ChannelFutureAPI
  ""
  (future-cb [_ arg] "")
  (close-cf [_]
            [_ keepAlive?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol OutboundAPI
  ""
  (write-last-content [inv]
                      [inv flush?] "")
  (reply-status [inv]
                [inv status]
                [inv status keepAlive?] "")
  (reply-redirect [inv perm? location]
                  [inv perm? location keepAlive?] "")
  (continue-100 [inv] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol PipelineAPI
  ""
  (maybe-ssl? [_] "")
  (dbg-pipeline [_] "")
  (ctx-name [_ h] "")
  (safe-remove-handler [_ cz] "")
  (safe-remove-handler* [_ args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpMessageAPI
  ""
  (get-uri-path [_] "")
  (get-uri-params [_] "")
  (get-msg-charset [_] "")
  (get-method [_] "")
  (get-headers [_] "")
  (crack-cookies [_] "")
  (no-content? [_] "")
  (content-type [_] "")
  (content-length! [_ len] "")
  (content-length-as-int [_] "")
  (detect-acceptable-charset [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pp->last
  [p n h]
  (l/debug "add-last %s/%s to ch-pipeline." n (u/gczn h))
  (.addLast ^ChannelPipeline p ^String n ^ChannelHandler h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pp->after
  [p a n h]
  (l/debug "add-after %s %s/%s to ch-pipeline." a n (u/gczn h))
  (.addAfter ^ChannelPipeline p ^String a ^String n ^ChannelHandler h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cfop<>
  "Create a ChannelFutureListener"
  ^ChannelFutureListener [func] {:pre [(fn? func)]}
  (reify ChannelFutureListener (operationComplete
                                 [_ ff] (c/try! (func ff)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<e>
  "ChannelFuture - exception." []
  `io.netty.channel.ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<x>
  "ChannelFuture - close-error." []
  `io.netty.channel.ChannelFutureListener/CLOSE_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<z>
  "ChannelFuture - close-success."
  [] `io.netty.channel.ChannelFutureListener/CLOSE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mg-headers??
  "Get the HttpHeaders object." ^HttpHeaders [msg] (:headers msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mg-cs??
  "Cast to a CharSequence." ^CharSequence [s] s)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-del
  "" [r] `(io.netty.util.ReferenceCountUtil/release ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-add
  "" [r] `(io.netty.util.ReferenceCountUtil/retain ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpMsgGist
  czlab.niou.core.Http1xMsg
  (msg-header? [msg h]
    (.contains (mg-headers?? msg) (mg-cs?? h)))
  (msg-header [msg h]
    (.get (mg-headers?? msg) (mg-cs?? h)))
  (msg-header-keys [msg]
    (set (.names (mg-headers?? msg))))
  (msg-header-vals [msg h]
    (vec (.getAll (mg-headers?? msg) (mg-cs?? h)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(let [a (InetAddress/getLoopbackAddress)
      h (InetAddress/getLocalHost)]
  (def ^String host-loopback-addr (.getHostAddress a))
  (def ^String host-loopback-name (.getHostName a))
  (def ^String lhost-name (.getHostName h))
  (def ^String lhost-addr (.getHostAddress h)))

(if false
  (do
    (println "lhost name= " lhost-name)
    (println "lhost addr= " lhost-addr)
    (println "loop name= " host-loopback-name)
    (println "loop addr= " host-loopback-addr)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro akey<>
  "New Attribute."
  [n] `(io.netty.util.AttributeKey/newInstance (name ~n)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce ^AttributeKey dfac-key (akey<> :data-factory))
(defonce ^AttributeKey h1msg-key (akey<> :h1req))
(defonce ^AttributeKey routes-key (akey<> :cracker))
(defonce ^AttributeKey chcfg-key (akey<> :ch-config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn config-disk-files
  "Configure temp-files repo." [delExit? fDir]
  (set! DiskFileUpload/deleteOnExitTemporaryFile false)
  (set! DiskAttribute/deleteOnExitTemporaryFile false)
  (set! DiskFileUpload/baseDirectory fDir)
  (set! DiskAttribute/baseDirectory fDir)
  (l/info "netty temp-file-repo: %s." fDir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn g-and-c
  "" {:no-doc true} [t kind]
  (if-some [x ({:tcps {:epoll EpollServerSocketChannel
                       :nio NioServerSocketChannel}
                :tcpc {:epoll EpollSocketChannel
                       :nio NioSocketChannel}
                :udps {:epoll EpollDatagramChannel
                       :nio NioDatagramChannel}} kind)]
    (if-not (Epoll/isAvailable)
     [(NioEventLoopGroup. (int t)) (:nio x)]
     [(EpollEventLoopGroup. (int t)) (:epoll x)])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-result
  "" ^DecoderResult [msg]
  (some-> (c/cast? DecoderResultProvider msg) .decoderResult))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-success?
  "" [msg]
  (if-some [r (decoder-result msg)] (.isSuccess r) true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ChannelXXXAPI
  Object
  (fire-next-and-quit
    ([_ h msg]
     (fire-next-and-quit _ h msg false))
    ([c h msg retain?]
     (if-some [ctx (c/cast? ChannelHandlerContext c)]
       (let [pp (.pipeline ctx)]
         (if-not (c/is? ChannelHandler h)
           (.remove pp (str h))
           (.remove pp ^ChannelHandler h))
         (dbg-pipeline pp)
         (if retain? (ref-add msg))
         (.fireChannelRead ctx msg)))))
  (chanid [c]
    (str (c/condp?? instance? c
           Channel (.id ^Channel c)
           ChannelHandlerContext (.. ^ChannelHandlerContext c channel id))))
  (cpipe [c]
    (c/condp?? instance? c
      Channel (.pipeline ^Channel c)
      ChannelHandlerContext (.pipeline ^ChannelHandlerContext c)))
  (ch?? [arg]
    (c/condp?? instance? arg
      Channel arg
      ChannelHandlerContext (.channel ^ChannelHandlerContext arg)))
  (close-ch [c]
    (c/condp?? instance? c
      Channel (.close ^Channel c)
      ChannelHandlerContext (.close ^ChannelHandlerContext c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol AttributeKeyAPI
  AttributeKey
  (set-akey [akey arg aval]
    (some-> ^Channel (ch?? arg) (.attr ^AttributeKey akey) (.set aval)) aval)
  (del-akey [akey arg]
    (set-akey akey arg nil))
  (get-akey [akey arg]
    (some-> ^Channel (ch?? arg) (.attr ^AttributeKey akey) .get)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- to-hhs
  ^HttpHeaders [obj]
  (if (map? obj)
    (:headers obj)
    (condp instance? obj
      HttpHeaders obj
      HttpMessage (get-headers obj)
      (u/throw-BadArg "Expecting http-msg or http-headers."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol HeadersAPI
  Object
  (del-header [obj nm]
    (.remove (to-hhs obj) ^CharSequence nm))
  (clone-headers [obj headers]
    (.set (to-hhs obj) headers))
  (set-header* [obj nvs]
    (doseq [[k v] (partition 2 nvs)]
      (set-header (to-hhs obj) k v)))
  (add-header* [obj nvs]
    (doseq [[k v] (partition 2 nvs)]
      (add-header (to-hhs obj) k v)))
  (add-header [obj nm value]
    (.add (to-hhs obj) ^CharSequence nm ^String value))
  (set-header [obj nm value]
    (.set (to-hhs obj) ^CharSequence nm ^String value))
  (get-header-vals [obj nm]
    (.getAll (to-hhs obj) ^CharSequence nm))
  (get-header [obj nm]
    (.get (to-hhs obj) ^CharSequence nm))
  (has-header? [obj nm]
    (.contains (to-hhs obj) ^CharSequence nm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ByteBufAPI
  ByteBuf
  (slurp-bytebuf [buf out]
    (if-some [len (some-> ^ByteBuf buf .readableBytes)]
      (if (pos? len)
        (do (.readBytes ^ByteBuf buf
                        ^OutputStream out (int len))
            (.flush ^OutputStream out) len) 0) 0))
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
        (doto buf (.writeCharSequence  ^CharSequence arg cs))
        (Unpooled/copiedBuffer ^CharSequence arg cs))
      :else (u/throw-IOE "Bad type to ByteBuf."))))

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
  ([status msg ^ByteBufAllocator alloc]
   (let [code (HttpResponseStatus/valueOf status)
         ver HttpVersion/HTTP_1_1]
     (cond
       (c/is? ByteBuf msg)
       (DefaultFullHttpResponse. ver code ^ByteBuf msg)
       (nil? msg)
       (let [x (DefaultFullHttpResponse. ver code)]
         (HttpUtil/setContentLength x 0) x)
       :else
       (let [bb (some-> alloc .directBuffer)
             _
             (cond
               (nil? bb)
               (u/throw-IOE "No direct-buffer.")
               (bytes? msg)
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
               (u/throw-IOE "Rouge content %s." (type msg)))]
         (DefaultFullHttpResponse. ver code ^ByteBuf bb)))))

  ([code] (http-reply<+> code nil nil))

  ([] (http-reply<+> (scode* OK))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ChannelFutureAPI
  ChannelFuture
  (future-cb [cf arg]
    (if-some
      [ln (cond
            (c/is? ChannelFutureListener arg) arg
            (fn? arg) (cfop<> arg)
            (nil? arg) nil
            :else
            (u/throw-IOE "Rogue object %s." (type arg)))]
      (.addListener cf ^ChannelFutureListener ln)))
  (close-cf
    ([cf]
     (close-cf cf false))
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
     {:pre [(number? status)]}
     (let [rsp (http-reply<+> status)
           code (.. rsp status code)
           ka? (if-not (and (>= code 200)
                            (< code 300)) false keepAlive?)]
       (l/debug "returning status [%s]." status)
       (HttpUtil/setKeepAlive rsp ka?)
       ;(HttpUtil/setContentLength rsp 0)
       (close-cf (.writeAndFlush inv rsp) ka?))))
  (reply-redirect
    ([inv perm? location]
     (reply-redirect inv perm? location false))
    ([inv perm? location keepAlive?]
     (let [rsp (http-reply<+>
                 (if perm?
                   (scode* MOVED_PERMANENTLY)
                   (scode* TEMPORARY_REDIRECT)))
           ka? false]
       (l/debug "redirecting to -> %s." location)
       (set-header rsp
                   (h1hdr* LOCATION) location)
       (HttpUtil/setKeepAlive rsp ka?)
       (close-cf (.writeAndFlush inv rsp) ka?))))
  (continue-100 [inv]
    (-> (->> (http-reply<+>
               (scode* CONTINUE))
             (.writeAndFlush inv ))
        (.addListener (cfop<e>)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol PipelineAPI
  ChannelPipeline
  (maybe-ssl? [pipe]
    (some? (.get pipe SslHandler)))
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
              ChannelHandler (.remove pipe ^ChannelHandler h))))
  (ctx-name [pipe h]
    (some-> (.context pipe ^ChannelHandler h) .name)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-cookie<>
  "" ^Cookie [^HttpCookie c]
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
  "" ^HttpCookie [^Cookie c]
  {:pre [(some? c)]}
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
    (if-some [req (c/cast? HttpRequest msg)]
      (let [cs (get-header req (h1hdr* ACCEPT_CHARSET))
            c (->> (c/split (str cs) "[,;\\s]+")
                   (some #(c/try! (Charset/forName ^String %))))]
        (or c (Charset/forName "utf-8")))))
  (get-headers [msg] (.headers msg))
  (get-method [msg]
    (if-some [req (c/cast? HttpRequest msg)]
      (c/ucase (c/stror (get-header req
                                    "X-HTTP-Method-Override")
                        (.. req getMethod name)))))
  (get-uri-path [msg]
    (if-some [req (c/cast? HttpRequest msg)]
      (.path (QueryStringDecoder. (.uri req))) ""))
  (get-uri-params [msg]
    (if-some [req (c/cast? HttpRequest msg)]
      (-> (.uri req) QueryStringDecoder.  .parameters)))
  (get-msg-charset [msg]
    (HttpUtil/getCharset msg (Charset/forName "utf-8")))
  (crack-cookies [msg]
    (c/condp?? instance? msg
      HttpRequest
      (c/if-some+
        [v (get-header msg
                       (h1hdr* COOKIE))]
        (c/preduce<map>
          #(assoc! %1
                   (.name ^Cookie %2)
                   (http-cookie<> %2))
          (.decode ServerCookieDecoder/STRICT v)))
      HttpResponse
      (c/preduce<map>
        #(let [v (.decode ClientCookieDecoder/STRICT %2)]
           (assoc! %1
                   (.name v) (http-cookie<> v)))
        (get-header-vals msg (h1hdr* SET_COOKIE)))))
  (no-content? [msg]
    (or (not (HttpUtil/isContentLengthSet msg))
        (not (> (HttpUtil/getContentLength msg -1) 0))))
  (content-type [msg]
    (-> (.headers msg)
        (.get (h1hdr* CONTENT_TYPE) "")))
  (content-length! [msg len]
    (HttpUtil/setContentLength msg (long len)))
  (content-length-as-int [msg]
    (HttpUtil/getContentLength msg (int 0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/WsockMsgReplyer
  Channel
  (send-ws-string [ch s]
    (.writeAndFlush ch
                    (TextWebSocketFrame. ^String s)))
  (send-ws-bytes [ch b]
    (.writeAndFlush ch
                    (BinaryWebSocketFrame. (x->bbuf ch b)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- akey??
  [k] (let [s (c/sname k)]
        (if-not (AttributeKey/exists s)
          (AttributeKey/newInstance s) (AttributeKey/valueOf s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/SocketAttrProvider
  Channel
  (socket-attr-get [c k] (get-akey (akey?? k) c))
  (socket-attr-del [c k] (del-akey (akey?? k) c))
  (socket-attr-set [c k a] (set-akey (akey?? k) c a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dbg-ref-count
  "Show ref-count of object" [obj]
  (if-some
    [rc (c/cast? ReferenceCounted obj)]
    (l/debug "object %s: has refcount: %s." obj (.refCnt rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


