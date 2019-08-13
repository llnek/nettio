;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.core

  (:refer-clojure :exclude [get-method])

  (:require [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.convoy.core :as cc]
            [czlab.convoy.routes :as cr])

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol WholeMsgAPI
  (end-msg-content [_] "")
  (deref-msg [_] "")
  (add-msg-content [_ c last?] "")
  (append-msg-content [_ c last?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mg-headers??
  "" ^HttpHeaders [msg] (:headers (if (c/is? IDeref msg) @msg msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mg-cs?? "" ^CharSequence [s] s)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-del "" [r] `(io.netty.util.ReferenceCountUtil/release ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ref-add "" [r] `(io.netty.util.ReferenceCountUtil/retain ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyWsockMsg [] cc/WsockMsg cc/WsockMsgGist)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyH2Msg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyH1Msg []
  cc/HttpMsg
  cc/HttpMsgGist
  (msg-header? [msg h]
    (.contains (mg-headers?? msg) (mg-cs?? h)))
  (msg-header [msg h]
    (.get (mg-headers?? msg) (mg-cs?? h)))
  (msg-header-keys [msg]
    (set (.names (mg-headers?? msg))))
  (msg-header-vals [msg h]
    (vec (.getAll (mg-headers?? msg) (mg-cs?? h)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-msg<> ""
  ([] (netty-msg<> nil))
  ([s] (merge (NettyH1Msg.) s)))

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
(def ^String user-handler-id "netty-user-handler")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce ^AttributeKey dfac-key (akey<> :data-factory))
(defonce ^AttributeKey h1msg-key (akey<> :h1req))
(defonce ^AttributeKey routes-key (akey<> :cracker))
(defonce ^AttributeKey chcfg-key (akey<> :ch-config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<e>
  "" [] `io.netty.channel.ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<x>
  "" [] `io.netty.channel.ChannelFutureListener/CLOSE_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro cfop<z> "" [] `io.netty.channel.ChannelFutureListener/CLOSE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro scode "Http status code." [s] `(.code ~s))

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
  (if-some [x
            ({:tcps {:epoll EpollServerSocketChannel
                     :nio NioServerSocketChannel}
              :tcpc {:epoll EpollSocketChannel
                     :nio NioSocketChannel}
              :udps {:epoll EpollDatagramChannel
                     :nio NioDatagramChannel}} kind)]
    (if-not (Epoll/isAvailable)
     [(NioEventLoopGroup. (int t)) (:nio x)]
     [(EpollEventLoopGroup. (int t)) (:epoll x)])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn netty-cookie<>
  "" ^Cookie
  [^HttpCookie c] {:pre [(some? c)]}
  ;; stick with version 0, Java's HttpCookie defaults to 1 but that
  ;; screws up the Path attribute on the wire => it's quoted but
  ;; browser seems to not like it and mis-interpret it.
  ;; Netty's cookie defaults to 0, which is cool with me.
  (l/debug "http->netty cookie: %s=[%s]." (.getName c) (.getValue c))
  (doto (DefaultCookie. (.getName c) (.getValue c))
    ;;(.setComment (.getComment c))
    (.setDomain (.getDomain c))
    (.setMaxAge (.getMaxAge c))
    (.setPath (.getPath c))
    ;;(.setDiscard (.getDiscard c))
    ;;(.setVersion 0)
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn chanid
  "Channel Id." ^String [c]
  (str (condp instance? c
         Channel
         (.id ^Channel c)
         ChannelHandlerContext
         (.. ^ChannelHandlerContext c channel id) nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-result
  "" ^DecoderResult [msg]
  (some-> (c/cast? DecoderResultProvider msg) .decoderResult))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-success?
  "" [msg] (if-some [r (decoder-result msg)] (.isSuccess r) true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoder-error
  "" ^Throwable [msg]
  (if-some
    [r (some-> (c/cast? DecoderResultProvider msg)
               .decoderResult)]
    (if-not (.isSuccess r) (.cause r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-decoder-error!
  "" [msg err] {:pre [(c/is? Throwable err)]}
  (if-some
    [p (c/cast? DecoderResultProvider msg)]
    (.setDecoderResult p
                       (DecoderResult/failure ^Throwable err))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cpipe "" ^ChannelPipeline [c]
  (condp instance? c
    Channel
    (.pipeline ^Channel c)
    ChannelHandlerContext
    (.pipeline ^ChannelHandlerContext c) nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ch?? "" ^Channel [arg]
  (condp instance? arg
    Channel
    arg
    ChannelHandlerContext
    (.channel ^ChannelHandlerContext arg) nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-akey
  "" [arg ^AttributeKey akey aval]
  (some-> (ch?? arg) (.attr akey) (.set aval)) aval)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-akey*
  "" [arg & kvs]
  (doseq [[k v] (partition 2 kvs)] (set-akey arg k v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn del-akey
  "" [arg ^AttributeKey akey] (set-akey arg akey nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn del-akey*
  "" [arg & kvs] (doseq [k kvs] (del-akey arg k)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-akey
  "" [arg ^AttributeKey akey] (some-> (ch?? arg) (.attr akey) .get))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ctx-name
  "" ^String
  [^ChannelPipeline pp ^ChannelHandler h] (some-> pp (.context h) .name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn close-ch "" [c]
  (condp instance? c
    Channel
    (.close ^Channel c)
    ChannelHandlerContext
    (.close ^ChannelHandlerContext c) nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- to-hhs "" ^HttpHeaders [obj]
  (condp instance? obj
    HttpHeaders
    obj
    HttpMessage
    (.headers ^HttpMessage obj)
    (u/throw-BadArg "expecting http-msg or http-headers.")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-header
  "" [obj nm value]
  (.add (to-hhs obj) ^CharSequence nm ^String value))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-header
  "" [obj nm value]
  (.set (to-hhs obj) ^CharSequence nm ^String value))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-header-vals
  "" [obj nm] (.getAll (to-hhs obj) ^CharSequence nm))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-header
  "" ^String [obj nm] (.get (to-hhs obj) ^CharSequence nm))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn has-header?
  "" [obj nm] (.contains (to-hhs obj) ^CharSequence nm))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn slurp-bytebuf
  "" ^long [^ByteBuf buf ^OutputStream out]
  (if-some [len (some-> buf .readableBytes)]
    (when (pos? len)
      (.readBytes buf out (int len)) (.flush out) len) 0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn to-byte-array
  "" ^bytes [^ByteBuf buf]
  (let [out (i/baos<>)]
    (if (pos? (slurp-bytebuf buf out)) (i/x->bytes out))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- x->bbuf
  ^ByteBuf [ch arg encoding]
  (let [buf (some-> ^Channel
                    ch
                    .alloc .directBuffer)
        cs (u/charset?? encoding)]
    (cond
      (bytes? arg)
      (if buf
        (.writeBytes buf ^bytes arg)
        (Unpooled/wrappedBuffer ^bytes arg))
      (string? arg)
      (if buf
        (doto buf (.writeCharSequence  ^CharSequence arg cs))
        (Unpooled/copiedBuffer ^CharSequence arg cs))
      :else (u/throw-IOE "bad type to ByteBuf."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn bytebuf?? ""

  ([arg ch] (bytebuf?? arg ch nil))
  ([arg] (bytebuf?? arg nil nil))

  ([arg ch encoding]
   (let [ct (if-some
              [c (c/cast? XData arg)]
              (.content c) arg)]
     (cond
       (bytes? ct) (x->bbuf ch ct encoding)
       (string? ct) (x->bbuf ch ct encoding) :else ct))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn write-last-content
  "" {:tag ChannelFuture}

  ([^ChannelOutboundInvoker inv flush?]
   (if flush?
     (.writeAndFlush inv LastHttpContent/EMPTY_LAST_CONTENT)
     (.write inv LastHttpContent/EMPTY_LAST_CONTENT)))

  ([inv] (write-last-content inv false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mock-request<+>
  "" ^FullHttpRequest [req]
  (let [{:keys [headers uri2
                version method]} req
        rc (DefaultFullHttpRequest.
              (HttpVersion/valueOf version)
              (HttpMethod/valueOf method) uri2)]
    (assert (c/is? HttpHeaders headers))
    (.set (.headers rc)
          ^HttpHeaders headers)
    rc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fake-request<> "" ^HttpRequest []
  (DefaultHttpRequest.
    HttpVersion/HTTP_1_1 HttpMethod/POST "/" (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn encode-netty-cookies
  "" ^APersistentVector [cookies]
  (c/preduce<vec>
    #(conj! %1
            (.encode
              ServerCookieEncoder/STRICT ^Cookie %2)) cookies))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn encode-java-cookies
  "" ^APersistentVector [cookies]
  (encode-netty-cookies (map #(netty-cookie<> %) cookies)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn detect-acceptable-charset
  "" ^Charset [^HttpRequest req]
  (let [cs (get-header req HttpHeaderNames/ACCEPT_CHARSET)
        c (->> (.split (str cs) "[,;\\s]+")
               (some #(c/try! (Charset/forName ^String %))))]
    (or c (Charset/forName "utf-8"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-msg-charset
  "" ^Charset [^HttpMessage msg]
  (HttpUtil/getCharset msg (Charset/forName "utf-8")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn close-cf
  "Close the channel unless keep-alive is set"
  ([cf] (close-cf cf false))
  ([^ChannelFuture cf keepAlive?]
    (if (and cf
             (not (boolean keepAlive?)))
      (.addListener cf ChannelFutureListener/CLOSE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-req<+>
  "" {:tag FullHttpRequest}
  ([mt uri] (http-req<+> mt uri nil))
  ([^HttpMethod mt ^String uri ^ByteBuf body]
   (if (nil? body)
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri)
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-req<>
  "" ^HttpRequest [^HttpMethod mt ^String uri]
  (DefaultHttpRequest. HttpVersion/HTTP_1_1 mt uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-post<+>
  "" ^FullHttpRequest
  [^String uri ^ByteBuf body] (http-req<+> HttpMethod/POST uri body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-post<>
  "" ^HttpRequest [uri] (http-req<> HttpMethod/POST uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-get<>
  "" ^FullHttpRequest [uri] (http-req<+> HttpMethod/GET uri nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-reply<>
  "Create an incomplete response" {:tag HttpResponse}

  ([] (http-reply<> (scode HttpResponseStatus/OK)))
  ([code]
   {:pre [(number? code)]}
   (DefaultHttpResponse. HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf code))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-reply<+>
  "Create a complete response" {:tag FullHttpResponse}
  ([status msg ^ByteBufAllocator alloc]
   (let [code (HttpResponseStatus/valueOf status)
         ver HttpVersion/HTTP_1_1]
     (cond
       (c/is? ByteBuf msg)
       (DefaultFullHttpResponse. ver code ^ByteBuf msg)
       (nil? msg)
       (DefaultFullHttpResponse. ver code)
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

  ([] (http-reply<+> (scode HttpResponseStatus/OK))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn reply-status
  "Reply back to client with a status, no body"

  ([inv status] (reply-status inv status false))
  ([inv] (reply-status inv 200))
  ([^ChannelOutboundInvoker inv status keepAlive?]
   {:pre [(number? status)]}
   (let [rsp (http-reply<+> status)
         code (.. rsp status code)
         ka? (if-not (and (>= code 200)
                          (< code 300))
               false
               keepAlive?)]
     (l/debug "returning status [%s]." status)
     (HttpUtil/setKeepAlive rsp ka?)
     (HttpUtil/setContentLength rsp 0)
     (close-cf (.writeAndFlush inv rsp) ka?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn send-redirect
  "Reply back to client with a redirect"
  ([^ChannelOutboundInvoker inv perm? location keepAlive?]
   (let
     [rsp (http-reply<+>
            (if perm?
              (scode HttpResponseStatus/MOVED_PERMANENTLY)
              (scode HttpResponseStatus/TEMPORARY_REDIRECT)))
      ka? false]
     (l/debug "redirecting to -> %s." location)
     (set-header rsp
                 HttpHeaderNames/LOCATION location)
     (HttpUtil/setKeepAlive rsp ka?)
     (close-cf (.writeAndFlush inv rsp) ka?)))

  ([inv perm? location]
   (send-redirect inv perm? location false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn continue-100
  "Reply back to client with a 100 continue."
  [^ChannelOutboundInvoker inv]
  (-> (->> (http-reply<+>
             (scode HttpResponseStatus/CONTINUE))
           (.writeAndFlush inv ))
      (.addListener (cfop<e>))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dbg-ref-count
  "Show ref-count of object" [obj]
  (if-some
    [rc (c/cast? ReferenceCounted obj)]
    (l/debug
      "object %s: has refcount: %s." obj (.refCnt rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn dbg-pipeline
  "List all handlers" [pipe]
  (l/debug "pipeline= %s"
           (cs/join "|" (.names ^ChannelPipeline pipe))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn safe-remove-handler
  "" [^ChannelPipeline cp ^Class cz] (c/try! (.remove cp cz)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fire-next-and-quit
  "Fire msg and remove the handler"
  ([^ChannelHandlerContext ctx handler msg retain?]
   (let [pp (.pipeline ctx)]
    (if (c/is? ChannelHandler handler)
      (.remove pp ^ChannelHandler handler)
      (.remove pp (str handler)))
    (dbg-pipeline pp)
    (if retain?
      (ref-add msg))
    (.fireChannelRead ctx msg)))

  ([ctx handler msg]
   (fire-next-and-quit ctx handler msg false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cfop<>
  "Create a ChannelFutureListener"
  ^ChannelFutureListener [func] {:pre [(fn? func)]}
  (reify ChannelFutureListener (operationComplete
                                 [_ ff] (c/try! (func ff)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn future-cb
  "Reg. callback" ^ChannelFuture [cf arg]
  (if-some
    [ln (cond
          (c/is? ChannelFutureListener arg) arg
          (fn? arg) (cfop<> arg)
          (nil? arg) nil
          :else
          (u/throw-IOE "Rogue object %s." (type arg)))]
    (-> ^ChannelFuture
        cf
        (.addListener ^ChannelFutureListener ln))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn maybe-handle-100?
  "Handle a *expect* header."

  ([^ChannelOutboundInvoker inv msg maxSize]
   (if (and (c/is? HttpRequest msg)
            (HttpUtil/is100ContinueExpected msg))
     (let [error (and (HttpUtil/isContentLengthSet msg)
                      (c/spos? maxSize)
                      (> (HttpUtil/getContentLength msg) maxSize))
           rsp (http-reply<+>
                 (if error
                   (scode HttpResponseStatus/EXPECTATION_FAILED)
                   (scode HttpResponseStatus/CONTINUE)))]
       (-> (.writeAndFlush inv rsp)
           (future-cb (if error (cfop<z>))))
       (not error))
     true))

  ([inv msg]
   (maybe-handle-100? inv msg -1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-method
  "Get the req method" ^String [msg]
  (if-some
    [req (c/cast? HttpRequest msg)]
    (s/ucase (s/stror (get-header req
                                 "X-HTTP-Method-Override")
                      (.. req getMethod name)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-websock?
  "Detects if a websock req?" [req]
  (let [cn (->> HttpHeaderNames/CONNECTION
                (cc/msg-header req) str s/lcase)
        ws (->> HttpHeaderNames/UPGRADE
                (cc/msg-header req) str s/lcase)]
    ;(l/debug "checking if it's a websock request......")
    (and (s/embeds? ws "websocket")
         (s/embeds? cn "upgrade")
         (= "GET" (:method req)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn maybe-ssl? "" [c] (some-> (cpipe c) (.get SslHandler) (some?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-uri-path "" ^String [msg]
  (if-some [req (c/cast? HttpRequest msg)]
    (. (QueryStringDecoder. (.uri req)) path) ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-uri-params
  "" ^Map [^HttpMessage msg]
  (if-some
    [req (c/cast? HttpRequest msg)]
    (-> (.uri req)
        QueryStringDecoder.  .parameters)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-cookie<>
  "" ^HttpCookie [^Cookie c] {:pre [(some? c)]}
  (doto (HttpCookie. (.name c)
                     (.value c))
    ;;(.setComment (.comment c))
    (.setDomain (.domain c))
    (.setMaxAge (.maxAge c))
    (.setPath (.path c))
    ;;(.setVersion (.getVersion c))
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn crack-cookies "" [msg]
  (if (c/is? HttpRequest msg)
    (c/if-some+
      [v (get-header msg
                     HttpHeaderNames/COOKIE)]
      (c/preduce<map>
        #(assoc! %1
                 (.name ^Cookie %2)
                 (http-cookie<> %2))
        (.decode ServerCookieDecoder/STRICT v)))
    (c/preduce<map>
      #(let [v (.decode ClientCookieDecoder/STRICT %2)]
         (assoc! %1
                 (.name v)
                 (http-cookie<> v)))
      (get-header-vals msg HttpHeaderNames/SET_COOKIE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn match-one-route "" [ctx msg]
  (l/debug "match route for msg: %s." msg)
  (let [dft {:status? true}
        rc
        (if (c/is? HttpRequest msg)
          (let [c (get-akey ctx routes-key)
                p {:method (get-method msg)
                   :uri (get-uri-path msg)}]
            (l/debug "cracker ======= %s." c)
            (when (and c (cr/rc-has-routes? c))
              (l/debug "cracking route uri= %s." p)
              (cr/rc-crack-route c p))))]
    (or rc dft)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- dft-req-msg-obj "" []
  (hash-map :is-keep-alive? false
            :chunked? false
            :version ""
            :method ""
            :socket nil
            :ssl? false
            :uri2 ""
            :uri ""
            :charset nil
            :cookies []
            :parameters (HashMap.)
            :headers (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- dft-rsp-msg-obj "" []
  (hash-map :is-keep-alive? false
            :chunked? false
            :version ""
            :socket nil
            :ssl? false
            :charset nil
            :cookies []
            :status {}
            :headers (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- gist-h1-request
  "" [ctx ^HttpRequest msg]
  (let
    [laddr (c/cast? InetSocketAddress
                    (.localAddress (ch?? ctx)))
     {:keys [body]} (deref-msg msg)
     ssl? (maybe-ssl? ctx)
     hs (.headers msg)
     {:keys [route-info matcher
             status? redirect] :as ro}
     (match-one-route ctx msg)
     ri (if (and status? route-info matcher)
          (cr/ri-collect-info route-info matcher))
     msg (assoc (dft-req-msg-obj)
                :chunked? (HttpUtil/isTransferEncodingChunked msg)
                :is-keep-alive? (HttpUtil/isKeepAlive msg)
                :version (.. msg protocolVersion text)
                :route (merge (dissoc ro
                                      :matcher
                                      :route-info) {:info route-info} ri)
                :local-addr (some-> laddr .getAddress .getHostAddress)
                :local-host (some-> laddr .getHostName)
                :local-port (some-> laddr .getPort)
                :remote-port (c/s->long (.get hs "remote_port") 0)
                :remote-addr (str (.get hs "remote_addr"))
                :remote-host (str (.get hs "remote_host"))
                :server-port (c/s->long (.get hs "server_port") 0)
                :server-name (str (.get hs "server_name"))
                :scheme (if ssl? "https" "http")
                :method (get-method msg)
                :body body
                :socket (ch?? ctx)
                :ssl? ssl?
                :parameters (get-uri-params msg)
                :headers (.headers msg)
                :uri2 (str (some-> msg .uri))
                :uri (get-uri-path msg)
                :charset (get-msg-charset msg)
                :cookies (crack-cookies msg))]
    msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- gist-h1-response
  "" [ctx ^HttpResponse msg]
  (let [{:keys [body]} (deref-msg msg)]
    (merge
      (dft-rsp-msg-obj)
      {:chunked? (HttpUtil/isTransferEncodingChunked msg)
       :is-keep-alive? (HttpUtil/isKeepAlive msg)
       :version (.. msg protocolVersion text)
       :socket (ch?? ctx)
       :body body
       :ssl? (maybe-ssl? ctx)
       :headers (.headers msg)
       :charset (get-msg-charset msg)
       :cookies (crack-cookies msg)}
      (let [s (.status msg)]
        {:status {:code (.code s)
                  :reason (.reasonPhrase s)}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-h1-msg "" [ctx msg]
  (if (satisfies? WholeMsgAPI msg)
    (c/do-with [m
                (cond
                  (c/is? HttpRequest msg)
                  (gist-h1-request ctx msg)
                  (c/is? HttpResponse msg)
                  (gist-h1-response ctx msg))]
               (l/debug "gisted h1-msg: %s." (i/fmt->edn m)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn content-length-as-int
  "" [^HttpMessage m] (HttpUtil/getContentLength m (int 0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn content-length!
  "" [^HttpMessage m len] (HttpUtil/setContentLength m (long len)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn content-type
  "" ^String [^HttpMessage m]
  (-> (.headers m) (.get HttpHeaderNames/CONTENT_TYPE "")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn no-content? "" [^HttpMessage m]
  (or (not (HttpUtil/isContentLengthSet m))
      (not (> (HttpUtil/getContentLength m -1) 0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce
  ^:private
  error-filter
  (proxy [InboundHandler][]
    (onRead [_ _])
    (exceptionCaught [_ t] (l/exception t))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn shared-error-sink<> "" ^ChannelHandler [] error-filter)
(def ^String shared-error-sink-name "sharedErrorSink")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn conv-certs
  "Convert Certs" ^APersistentVector [arg]

  (let [[del? inp] (i/input-stream?? arg)]
    (try
      (-> (CertificateFactory/getInstance "X.509")
          (.generateCertificates ^InputStream inp) vec)
      (finally
        (if del? (i/klose inp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/WsockMsgReplyer
  io.netty.channel.Channel
  (send-ws-string [me s]
    (->> (TextWebSocketFrame. ^String s)
         (.writeAndFlush ^Channel me )))
  (send-ws-bytes [me b]
    (->> (x->bbuf me b)
         (BinaryWebSocketFrame. )
         (.writeAndFlush ^Channel me ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-akey "" [k]
  (let [s (s/sname k)]
    (if-not (AttributeKey/exists s)
      (AttributeKey/newInstance s) (AttributeKey/valueOf s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/SocketAttrProvider
  io.netty.channel.Channel
  (socket-attr-get [me k] (get-akey me (maybe-akey k)))
  (socket-attr-del [me k] (del-akey me (maybe-akey k)))
  (socket-attr-set [me k a] (set-akey me (maybe-akey k) a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


