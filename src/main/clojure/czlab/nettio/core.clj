;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.core

  (:require [czlab.basal.meta :as m :refer [instBytes?]]
            [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.convoy.routes :as cr]
            [czlab.convoy.core :as cc]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

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
           [czlab.jasal XData]
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
;;
(defprotocol WholeMsgProto
  ""
  (append-msg-content [_ c last?] "")
  (add-msg-content [_ c last?] "")
  (deref-msg [_] "")
  (end-msg-content [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mg-headers??
  "" ^HttpHeaders [msg] (:headers (if (c/ist? IDeref msg) @msg msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mg-cs?? "" ^CharSequence [s] s)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ref-del "" [r] `(io.netty.util.ReferenceCountUtil/release ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ref-add "" [r] `(io.netty.util.ReferenceCountUtil/retain ~r))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-object NettyWsockMsg cc/WsockMsg cc/WsockMsgGist)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-object NettyH2Msg)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-object NettyH1Msg
  cc/HttpMsg
  cc/HttpMsgGist
  (msgHeader? [msg h]
    (.contains (mg-headers?? msg) (mg-cs?? h)))
  (msgHeader [msg h]
    (.get (mg-headers?? msg) (mg-cs?? h)))
  (msgHeaderKeys [msg]
    (set (.names (mg-headers?? msg))))
  (msgHeaderVals [msg h]
    (vec (.getAll (mg-headers?? msg) (mg-cs?? h)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn nettyMsg<> ""
  ([] (nettyMsg<> {}))
  ([s] (c/object<> NettyH1Msg s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
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
;;
(defmacro akey<>
  "New Attribute"
  [n] `(io.netty.util.AttributeKey/newInstance ~n))
(def ^String user-handler-id "netty-user-handler")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defonce ^AttributeKey dfac-key (akey<> "data-factory"))
(defonce ^AttributeKey chcfg-key (akey<> "ch-config"))
(defonce ^AttributeKey h1msg-key (akey<> "h1req"))
(defonce ^AttributeKey routes-key (akey<> "cracker"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro cfop<e>
  "" [] `io.netty.channel.ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro cfop<x>
  "" [] `io.netty.channel.ChannelFutureListener/CLOSE_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro cfop<z> "" [] `io.netty.channel.ChannelFutureListener/CLOSE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro scode "Http status code" [s] `(.code ~s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn configDiskFiles
  "Configure temp-files repo" [delExit? fDir]

  (set! DiskFileUpload/deleteOnExitTemporaryFile false)
  (set! DiskAttribute/deleteOnExitTemporaryFile false)
  (set! DiskFileUpload/baseDirectory fDir)
  (set! DiskAttribute/baseDirectory fDir)
  (log/info "netty temp-file-repo: %s" fDir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn gAndC
  "" {:no-doc true} [t kind]

  (if-some [x
            ({:tcps {:epoll EpollServerSocketChannel
                     :nio NioServerSocketChannel}
              :tcpc {:epoll EpollSocketChannel
                     :nio NioSocketChannel}
              :udps {:epoll EpollDatagramChannel
                     :nio NioDatagramChannel}} kind)]
    (if (Epoll/isAvailable)
     [(EpollEventLoopGroup. (int t)) (:epoll x)]
     [(NioEventLoopGroup. (int t)) (:nio x)])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn nettyCookie<>
  "" ^Cookie
  [^HttpCookie c] {:pre [(some? c)]}

  ;; stick with version 0, Java's HttpCookie defaults to 1 but that
  ;; screws up the Path attribute on the wire => it's quoted but
  ;; browser seems to not like it and mis-interpret it.
  ;; Netty's cookie defaults to 0, which is cool with me.
  (log/debug "http->netty cookie: %s=[%s]"
             (.getName c)
             (.getValue c))
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
;;
(defn chanid
  "Channel Id" ^String [c]

  (str (condp instance? c
         ChannelHandlerContext
         (.. ^ChannelHandlerContext c channel id)
         Channel
         (.id ^Channel c)
         nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderResult
  "" ^DecoderResult [msg]
  (some-> (c/cast? DecoderResultProvider msg) .decoderResult))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderSuccess?
  "" [msg] (if-some [r (decoderResult msg)] (.isSuccess r) true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderError
  "" ^Throwable [msg]
  (if-some
    [r (some-> (c/cast? DecoderResultProvider msg)
               .decoderResult)]
    (if-not (.isSuccess r) (.cause r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setDecoderError!
  "" [msg err] {:pre [(c/ist? Throwable err)]}

  (if-some
    [p (c/cast? DecoderResultProvider msg)]
    (->> (DecoderResult/failure ^Throwable err)
         (.setDecoderResult p ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn cpipe "" ^ChannelPipeline [c]

  (condp instance? c
    Channel
    (.pipeline ^Channel c)
    ChannelHandlerContext
    (.pipeline ^ChannelHandlerContext c)
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn ch?? "" ^Channel [arg]

  (condp instance? arg
    ChannelHandlerContext
    (.channel ^ChannelHandlerContext arg)
    Channel arg
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setAKey
  "" [arg ^AttributeKey akey aval]
  (some-> (ch?? arg) (.attr akey) (.set aval)) aval)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setAKey*
  "" [arg & kvs]
  (doseq [[k v] (partition 2 kvs)] (setAKey arg k v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn delAKey
  "" [arg ^AttributeKey akey] (setAKey arg akey nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn delAKey*
  "" [arg & kvs] (doseq [k kvs] (delAKey arg k)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getAKey
  "" [arg ^AttributeKey akey]
  (some-> (ch?? arg) (.attr akey) .get))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn ctxName
  "" ^String
  [^ChannelPipeline pp ^ChannelHandler h] (some-> pp (.context h) .name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn closeCH "" [c]
  (condp instance? c
    Channel
    (.close ^Channel c)
    ChannelHandlerContext
    (.close ^ChannelHandlerContext c)
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- toHHS "" ^HttpHeaders [obj]
  (cond
    (c/ist? HttpMessage obj) (.headers ^HttpMessage obj)
    (c/ist? HttpHeaders obj) obj
    :else (c/throwBadArg "expecting http-msg or http-headers")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn addHeader
  "" [obj ^CharSequence nm ^String value] (-> (toHHS obj) (.add nm value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setHeader
  ""
  [obj ^CharSequence nm ^String value] (-> (toHHS obj) (.set nm value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getHeaderVals
  ""
  [obj ^CharSequence nm] (-> (toHHS obj) (.getAll nm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getHeader
  ""
  ^String [obj ^CharSequence nm] (-> (toHHS obj) (.get nm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn hasHeader?
  ""
  [obj ^CharSequence nm] (-> (toHHS obj) (.contains nm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn slurpByteBuf
  "" ^long [^ByteBuf buf ^OutputStream out]

  (if-some [len (some-> buf .readableBytes)]
    (when (> len 0)
      (.readBytes buf out (int len))
      (.flush out)
      len)
    0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn toByteArray
  "" ^bytes [^ByteBuf buf]

  (let [out (i/baos<>)]
    (if (> (slurpByteBuf buf out) 0)
      (i/bytes?? out))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn coerce2bb
  "" ^ByteBuf [ch arg encoding]

  (let [buf (some-> ^Channel
                    ch
                    .alloc .directBuffer)
        cs (c/toCharset encoding)]
    (cond
      (m/instBytes? arg)
      (if buf
        (.writeBytes buf ^bytes arg)
        (Unpooled/wrappedBuffer ^bytes arg))
      (string? arg)
      (if buf
        (doto buf (.writeCharSequence  ^CharSequence arg cs))
        (Unpooled/copiedBuffer ^CharSequence arg cs))
      :else (c/throwIOE "bad type"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn byteBuf?? ""

  ([arg ch] (byteBuf?? arg ch nil))
  ([arg] (byteBuf?? arg nil nil))

  ([arg ch encoding]
   (let [ct (if-some
              [c (c/cast? XData arg)]
              (.content c) arg)]
     (cond
       (m/instBytes? ct) (coerce2bb ch ct encoding)
       (string? ct) (coerce2bb ch ct encoding)
       :else ct))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn writeLastContent
  "" {:tag ChannelFuture}

  ([^ChannelOutboundInvoker inv flush?]
   (if flush?
     (.writeAndFlush inv LastHttpContent/EMPTY_LAST_CONTENT)
     (.write inv LastHttpContent/EMPTY_LAST_CONTENT)))

  ([inv] (writeLastContent inv false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mockFullRequest<>
  "" ^FullHttpRequest [req]

  (let [{:keys [headers uri2
                version method]}
        req
        rc (DefaultFullHttpRequest.
              (HttpVersion/valueOf version)
              (HttpMethod/valueOf method)
              uri2)]
    (assert (c/ist? HttpHeaders headers))
    (-> (.headers rc)
        (.set ^HttpHeaders headers))
    rc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn fakeARequest<> "" ^HttpRequest []
  (DefaultHttpRequest.
    HttpVersion/HTTP_1_1
    HttpMethod/POST
    "/"
    (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn encodeNettyCookies
  "" ^APersistentVector [cookies]

  (c/preduce<vec>
    #(conj! %1
            (.encode
              ServerCookieEncoder/STRICT ^Cookie %2)) cookies))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn encodeJavaCookies
  "" ^APersistentVector [cookies]
  (encodeNettyCookies (map #(nettyCookie<> %) cookies)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn detectAcceptableCharset
  "" ^Charset [^HttpRequest req]
  (let [cs (getHeader req HttpHeaderNames/ACCEPT_CHARSET)
        c (->> (.split (str cs) "[,;\\s]+")
               (some #(c/try! (Charset/forName ^String %))))]
    (or c (Charset/forName "utf-8"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getMsgCharset
  "" ^Charset [^HttpMessage msg]
  (HttpUtil/getCharset msg (Charset/forName "utf-8")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn closeCF
  "Close the channel unless keep-alive is set"

  ([cf] (closeCF cf false))
  ([^ChannelFuture cf keepAlive?]
    (if (and cf
             (not (boolean keepAlive?)))
      (.addListener cf ChannelFutureListener/CLOSE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpReq<+>
  "" {:tag FullHttpRequest}

  ([mt uri] (httpReq<+> mt uri nil))
  ([^HttpMethod mt ^String uri ^ByteBuf body]
   (if (nil? body)
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri)
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpReq<>
  "" ^HttpRequest [^HttpMethod mt ^String uri]
  (DefaultHttpRequest. HttpVersion/HTTP_1_1 mt uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpPost<+>
  "" ^FullHttpRequest
  [^String uri ^ByteBuf body] (httpReq<+> HttpMethod/POST uri body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpPost<>
  "" ^HttpRequest [uri] (httpReq<> HttpMethod/POST uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpGet<>
  "" ^FullHttpRequest [uri] (httpReq<+> HttpMethod/GET uri nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpReply<>
  "Create an incomplete response" {:tag HttpResponse}

  ([] (httpReply<> (scode HttpResponseStatus/OK)))
  ([code]
   {:pre [(number? code)]}
   (DefaultHttpResponse. HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf code))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpFullReply<>
  "Create a complete response" {:tag FullHttpResponse}

  ([status msg ^ByteBufAllocator alloc]
   (let [code (HttpResponseStatus/valueOf status)
         ver HttpVersion/HTTP_1_1]
     (cond
       (c/ist? ByteBuf msg)
       (DefaultFullHttpResponse. ver code ^ByteBuf msg)
       (nil? msg)
       (DefaultFullHttpResponse. ver code)
       :else
       (let [bb (some-> alloc .directBuffer)
             _
             (cond
               (nil? bb)
               (c/throwIOE "No bytebuf")
               (m/instBytes? msg)
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
               (c/throwIOE "Rouge content %s" (type msg)))]
         (DefaultFullHttpResponse. ver code ^ByteBuf bb)))))

  ([code] (httpFullReply<> code nil nil))

  ([] (httpFullReply<> (scode HttpResponseStatus/OK))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn replyStatus
  "Reply back to client with a status, no body"

  ([inv status] (replyStatus inv status false))
  ([inv] (replyStatus inv 200))
  ([^ChannelOutboundInvoker inv status keepAlive?]
   {:pre [(number? status)]}
   (let [rsp (httpFullReply<> status)
         code (.. rsp status code)
         ka? (if-not (and (>= code 200)
                          (< code 300))
               false
               keepAlive?)]
     (log/debug "returning status [%s]" status)
     (HttpUtil/setKeepAlive rsp ka?)
     (closeCF (.writeAndFlush inv rsp) ka?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn sendRedirect
  "Reply back to client with a redirect"

  ([^ChannelOutboundInvoker inv perm? location keepAlive?]
   (let
     [rsp (->>
            (if perm?
              (scode HttpResponseStatus/MOVED_PERMANENTLY)
              (scode HttpResponseStatus/TEMPORARY_REDIRECT))
            httpFullReply<> )
      ka? false]
     (log/debug "redirecting to -> %s" location)
     (setHeader rsp
                HttpHeaderNames/LOCATION location)
     (HttpUtil/setKeepAlive rsp ka?)
     (closeCF (.writeAndFlush inv rsp) ka?)))

  ([inv perm? location]
   (sendRedirect inv perm? location false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn continue100
  "Reply back to client with a 100 continue"
  [^ChannelOutboundInvoker inv]
  (->
    (->> (scode HttpResponseStatus/CONTINUE)
         httpFullReply<>
         (.writeAndFlush inv ))
    (.addListener (cfop<e>))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbgRefCount
  "Show ref-count of object" [obj]
  (if-some
    [rc (c/cast? ReferenceCounted obj)]
    (log/debug
      "object %s: has refcount: %s" obj (.refCnt rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbgPipeline
  "List all handlers" [pipe]
  (log/debug "pipeline= %s"
             (cs/join "|" (.names ^ChannelPipeline pipe))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn safeRemoveHandler
  "" [^ChannelPipeline cp ^Class cz] (c/trye!! nil (.remove cp cz)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn fireNextAndQuit
  "Fire msg and remove the handler"

  ([^ChannelHandlerContext ctx handler msg retain?]
   (let [pp (.pipeline ctx)]
    (if (c/ist? ChannelHandler handler)
      (.remove pp ^ChannelHandler handler)
      (.remove pp (str handler)))
    (dbgPipeline pp)
    (if retain?
      (ref-add msg))
    (.fireChannelRead ctx msg)))

  ([ctx handler msg]
   (fireNextAndQuit ctx handler msg false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn cfop<>
  "Create a ChannelFutureListener"
  ^ChannelFutureListener [func] {:pre [(fn? func)]}
  (reify ChannelFutureListener (operationComplete
                                 [_ ff] (c/try! (func ff)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn futureCB
  "Reg. callback" ^ChannelFuture [cf arg]

  (if-some
    [ln (cond
          (c/ist? ChannelFutureListener arg) arg
          (fn? arg) (cfop<> arg)
          (nil? arg) nil
          :else
          (c/throwIOE "Rogue object %s" (type arg)))]
    (-> ^ChannelFuture
        cf
        (.addListener ^ChannelFutureListener ln))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn maybeHandle100?
  "Handle a *expect* header"

  ([^ChannelOutboundInvoker inv msg maxSize]
   (if (and (c/ist? HttpRequest msg)
            (HttpUtil/is100ContinueExpected msg))
     (let [error (and (HttpUtil/isContentLengthSet msg)
                      (c/spos? maxSize)
                      (> (HttpUtil/getContentLength msg) maxSize))
           rsp (->> (if error
                      (scode HttpResponseStatus/EXPECTATION_FAILED)
                      (scode HttpResponseStatus/CONTINUE))
                    httpFullReply<> )]
       (-> (.writeAndFlush inv rsp)
           (futureCB (if error (cfop<z>))))
       (not error))
     true))

  ([inv msg]
   (maybeHandle100? inv msg -1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getMethod
  "Get the req method" ^String [msg]

  (if-some
    [req (c/cast? HttpRequest msg)]
    (s/ucase (s/stror (getHeader req
                                 "X-HTTP-Method-Override")
                      (.. req getMethod name)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn chkFormPost
  "Detects if a form post" ^String [req]

  (let
    [ct (->> HttpHeaderNames/CONTENT_TYPE
             (getHeader req)
             str
             s/lcase)
     method (getMethod req)]
    (cond
      (s/embeds? ct "application/x-www-form-urlencoded")
      (if (s/eqAny? method ["POST" "PUT"])
        "post"
        "url")
      (and (s/embeds? ct "multipart/form-data")
           (s/eqAny? method ["POST" "PUT"]))
      "multipart")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn isWEBSock?
  "Detects if a websock req" [req]

  (let [cn (->> HttpHeaderNames/CONNECTION
                (cc/msgHeader req)
                str
                s/lcase)
        ws (->> HttpHeaderNames/UPGRADE
                (cc/msgHeader req)
                str
                s/lcase)]
    (log/debug "checking if it's a websock request......")
    (and (s/embeds? ws "websocket")
         (s/embeds? cn "upgrade")
         (= "GET" (:method req)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn maybeSSL? "" [c] (some-> (cpipe c) (.get SslHandler) (some?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getUriPath "" ^String [msg]
  (if-some [req (c/cast? HttpRequest msg)]
    (. (QueryStringDecoder. (.uri req)) path) ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getUriParams
  "" ^Map [^HttpMessage msg]

  (if-some
    [req (c/cast? HttpRequest msg)]
    (-> (.uri req)
        QueryStringDecoder.  .parameters)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpCookie<>
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
;;
(defn crackCookies "" [msg]

  (if (c/ist? HttpRequest msg)
    (c/if-some+
      [v (getHeader msg
                    HttpHeaderNames/COOKIE)]
      (c/preduce<map>
        #(assoc! %1
                 (.name ^Cookie %2)
                 (httpCookie<> %2))
        (.decode ServerCookieDecoder/STRICT v)))
    (c/preduce<map>
      #(let [v (.decode ClientCookieDecoder/STRICT %2)]
         (assoc! %1
                 (.name v)
                 (httpCookie<> v)))
      (getHeaderVals msg HttpHeaderNames/SET_COOKIE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn matchOneRoute "" [ctx msg]

  (let [dft {:status? true}
        rc
        (if (c/ist? HttpRequest msg)
          (let [c (getAKey ctx routes-key)]
            (if (and c
                     (cr/has-routes? c))
              (cr/crack-route c {:method (getMethod msg)
                                 :uri (getUriPath msg)}))))]
    (or rc dft)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dftReqMsgObj "" []
  (doto
    {:chunked? false
     :isKeepAlive? false
     :version ""
     :method ""
     :socket nil
     :ssl? false
     :parameters (HashMap.)
     :headers (DefaultHttpHeaders.)
     :uri2 ""
     :uri ""
     :charset nil
     :cookies []} ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dftRspMsgObj "" []
  (doto
    {:chunked? false
     :isKeepAlive? false
     :version ""
     :socket nil
     :ssl? false
     :headers (DefaultHttpHeaders.)
     :charset nil
     :cookies []
     :status {} }))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn gistH1Request
  "" [ctx ^HttpRequest msg]

  (let
    [laddr (c/cast? InetSocketAddress
                    (.localAddress (ch?? ctx)))
     {:keys [body]}
     (deref-msg msg)
     ssl? (maybeSSL? ctx)
     hs (.headers msg)
     {:keys [routeInfo matcher
             status? redirect] :as ro}
     (matchOneRoute ctx msg)
     ri (if (and status? routeInfo matcher)
          (cr/collect-info routeInfo matcher))]
    (merge
      (dftReqMsgObj)
      {:chunked? (HttpUtil/isTransferEncodingChunked msg)
       :isKeepAlive? (HttpUtil/isKeepAlive msg)
      :version (.. msg protocolVersion text)
      :route (merge (dissoc ro :routeInfo :matcher)
                    {:info routeInfo} ri)
      :localAddr (some-> laddr .getAddress .getHostAddress)
      :localHost (some-> laddr .getHostName)
      :localPort (some-> laddr .getPort)
      :remotePort (c/convLong (.get hs "remote_port") 0)
      :remoteAddr (str (.get hs "remote_addr"))
      :remoteHost (str (.get hs "remote_host"))
      :serverPort (c/convLong (.get hs "server_port") 0)
      :serverName (str (.get hs "server_name"))
      :scheme (if ssl? "https" "http")
      :method (getMethod msg)
      :body body
      :socket (ch?? ctx)
      :ssl? ssl?
      :parameters (getUriParams msg)
      :headers (.headers msg)
      :uri2 (str (some-> msg .uri))
      :uri (getUriPath msg)
      :charset (getMsgCharset msg)
      :cookies (crackCookies msg)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn gistH1Response
  "" [ctx ^HttpResponse msg]

  (let [{:keys [body]} (deref-msg msg)]
    (merge
      (dftRspMsgObj)
      {:chunked? (HttpUtil/isTransferEncodingChunked msg)
       :isKeepAlive? (HttpUtil/isKeepAlive msg)
       :version (.. msg protocolVersion text)
       :socket (ch?? ctx)
       :body body
       :ssl? (maybeSSL? ctx)
       :headers (.headers msg)
       :charset (getMsgCharset msg)
       :cookies (crackCookies msg)}
      (let [s (.status msg)]
        {:status {:code (.code s)
                  :reason (.reasonPhrase s)}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn gistH1Msg "" [ctx msg]
  (if (satisfies? WholeMsgProto msg)
    (cond
      (c/ist? HttpRequest msg)
      (gistH1Request ctx msg)
      (c/ist? HttpResponse msg)
      (gistH1Response ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn contentLengthAsInt
  "" [^HttpMessage m] (HttpUtil/getContentLength m (int 0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn contentLength!
  "" [^HttpMessage m len] (HttpUtil/setContentLength m (long len)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn contentType
  "" ^String [^HttpMessage m]
  (-> (.headers m) (.get HttpHeaderNames/CONTENT_TYPE "")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn noContent? "" [^HttpMessage m]
  (or (not (HttpUtil/isContentLengthSet m))
      (not (> (HttpUtil/getContentLength m -1) 0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defonce
  ^:private
  error-filter
  (proxy [InboundHandler][]
    (onRead [_ _])
    (exceptionCaught [_ t] (log/error t ""))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn sharedErrorSink<> "" ^ChannelHandler [] error-filter)
(def ^String sharedErrorSinkName "sharedErrorSink")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn convCerts
  "Convert Certs" ^APersistentVector [arg]

  (let [[del? inp] (i/inputStream?? arg)]
    (try
      (-> (CertificateFactory/getInstance "X.509")
          (.generateCertificates ^InputStream inp) vec)
      (finally
       (if del? (i/closeQ inp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(extend-protocol cc/WsockMsgReplyer
  io.netty.channel.Channel
  (send-ws-string [me s]
    (->> (TextWebSocketFrame. ^String s)
         (.writeAndFlush ^Channel me )))
  (send-ws-bytes [me b]
    (->> (coerce2bb me b)
         (BinaryWebSocketFrame. )
         (.writeAndFlush ^Channel me ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeAKey "" [k]
  (let [s (s/sname k)]
    (if-not (AttributeKey/exists s)
      (AttributeKey/newInstance s)
      (AttributeKey/valueOf s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(extend-protocol cc/SocketAttrProvider
  io.netty.channel.Channel
  (set-socket-attr [me k a]
    (setAKey me (maybeAKey k) a))
  (get-socket-attr [me k]
    (getAKey me (maybeAKey k)))
  (del-socket-attr [me k]
    (delAKey me (maybeAKey k))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


