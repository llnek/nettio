;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.convoy.nettio.core

  (:require [czlab.basal.meta :refer [instBytes?]]
            [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.convoy.net.core]
        [czlab.basal.str]
        [czlab.basal.io]
        [czlab.basal.core])

  (:import [clojure.lang APersistentMap APersistentSet APersistentVector]
           [io.netty.handler.codec DecoderResultProvider DecoderResult]
           [java.security.cert X509Certificate CertificateFactory]
           [javax.net.ssl KeyManagerFactory TrustManagerFactory]
           [io.netty.handler.codec.http2 Http2SecurityUtil]
           [java.io IOException OutputStream]
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
           [czlab.convoy.nettio InboundAdapter InboundHandler]
           [czlab.convoy.net RouteInfo RouteCracker]
           [io.netty.channel.nio NioEventLoopGroup]
           [java.net InetAddress URL HttpCookie]
           [io.netty.handler.codec.http.cookie
            ServerCookieDecoder
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
            DefaultCookie
            Cookie
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
            DefaultHttpRequest
            HttpRequest
            HttpResponseStatus
            HttpHeaders
            QueryStringDecoder]
           [java.util Map Map$Entry]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^String host-loopback-addr (.getHostAddress (InetAddress/getLoopbackAddress)))
(def ^String host-loopback-name (.getHostName (InetAddress/getLoopbackAddress)))
(def ^String lhost-name (.getHostName (InetAddress/getLocalHost)))
(def ^String lhost-addr (.getHostAddress (InetAddress/getLocalHost)))
(comment
  (println "lhost name= " lhost-name)
  (println "lhost addr= " lhost-addr)
  (println "loop name= " host-loopback-name)
  (println "loop addr= " host-loopback-addr))
(defmacro akey<> "New Attribute" [n] `(AttributeKey/newInstance ~n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defonce ^AttributeKey dfac-key (akey<> "data-factory"))
(defonce ^AttributeKey chcfg-key (akey<> "ch-config"))
(defonce ^AttributeKey h1msg-key (akey<> "h1req"))
(defonce ^AttributeKey routes-key (akey<> "cracker"))
(def ^String user-handler-id "netty-user-handler")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro cfop<e>
  "" [] `ChannelFutureListener/FIRE_EXCEPTION_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro cfop<x>
  "" [] `ChannelFutureListener/CLOSE_ON_FAILURE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro cfop<z> "" [] `ChannelFutureListener/CLOSE)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn configDiskFiles
  "Configure temporary output file repo"
  [delExit? fDir]
  (set! DiskFileUpload/deleteOnExitTemporaryFile false)
  (set! DiskAttribute/deleteOnExitTemporaryFile false)
  (set! DiskFileUpload/baseDirectory fDir)
  (set! DiskAttribute/baseDirectory fDir)
  (log/info "netty temp-file-repo: %s" fDir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn gAndC
  ""
  {:no-doc true}
  [t kind]
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
  ""
  ^Cookie
  [^HttpCookie c]
  {:pre [(some? c)]}
  ;; stick with version 0, Java's HttpCookie defaults to 1 but that
  ;; screws up the Path attribute on the wire => it's quoted but
  ;; browser seems to not like it and mis-interpret it.
  ;; Netty's cookie defaults to 0, which is cool with me.
  (doto (DefaultCookie. (.getName c)
                        (.getValue c))
    ;;(.setComment (.getComment c))
    (.setDomain (.getDomain c))
    (.setMaxAge (.getMaxAge c))
    (.setPath (.getPath c))
    ;;(.setDiscard (.getDiscard c))
    (.setVersion 0)
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn chanid
  "Channel Id"
  ^String
  [c]
  (str (condp instance? c
         ChannelHandlerContext
         (.id (.channel ^ChannelHandlerContext c))
         Channel
         (.id ^Channel c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistHeader?
  :netty
  [gist hd]
  (.contains ^HttpHeaders (:headers gist) ^CharSequence hd))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistParam?
  :netty
  [gist pm]
  (-> ^Map
      (:parameters gist) (.containsKey ^String pm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistHeader
  :netty
  [gist hd]
  (.get ^HttpHeaders (:headers gist) ^CharSequence hd))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistParam
  :netty
  [gist pm]
  (first (.get ^Map (:parameters gist) ^String pm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderResult
  ""
  ^DecoderResult
  [msg]
  (some-> (cast? DecoderResultProvider msg) (.decoderResult)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderSuccess?
  ""
  [msg]
  (if-some [r (decoderResult msg)] (.isSuccess r) true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderError
  ""
  ^Throwable
  [msg]
  (if-some
    [r (some-> (cast? DecoderResultProvider msg)
               (.decoderResult))]
    (if-not (.isSuccess r) (.cause r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setDecoderError!
  ""
  [msg err]
  {:pre [(inst? Throwable err)]}
  (if-some
    [p (cast? DecoderResultProvider msg)]
    (->> (DecoderResult/failure ^Throwable err)
         (.setDecoderResult p ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn cpipe
  ""
  ^ChannelPipeline
  [c]
  (condp instance? c
    Channel
    (.pipeline ^Channel c)
    ChannelHandlerContext
    (.pipeline ^ChannelHandlerContext c)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn ch??
  ""
  ^Channel
  [arg]
  (condp instance? arg
    ChannelHandlerContext
    (.channel ^ChannelHandlerContext arg)
    Channel arg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setAKey
  ""
  [arg ^AttributeKey akey aval]
  (some-> (ch?? arg) (.attr akey) (.set aval)) aval)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setAKey*
  ""
  [arg & kvs]
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
  ""
  [arg ^AttributeKey akey]
  (some-> (ch?? arg) (.attr akey) (.get)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn ctxName
  ""
  ^String
  [^ChannelPipeline pp ^ChannelHandler h]
  (some-> pp (.context h) (.name)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn closeCH
  ""
  [c]
  (condp instance? c
    Channel
    (.close ^Channel c)
    ChannelHandlerContext
    (.close ^ChannelHandlerContext c)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistHeaderKeys
  :netty
  [gist] (set (.names ^HttpHeaders (:headers gist))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistHeaderVals
  :netty
  [gist hd]
  (vec (.getAll ^HttpHeaders (:headers gist) ^String hd)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistParamKeys
  :netty
  [gist] (set (.keySet ^Map (:parameters gist))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod gistParamVals
  :netty
  [gist pm]
  (vec (.get ^Map (:parameters gist) ^String pm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn addHeader
  ""
  [^HttpMessage msg
   ^CharSequence nm ^String value]
  (-> (.headers msg) (.add nm value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setHeader
  ""
  [^HttpMessage msg
   ^CharSequence nm ^String value]
  (-> (.headers msg) (.set nm value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getHeader
  ""
  ^String
  [^HttpMessage msg
   ^CharSequence nm]
  (-> (.headers msg) (.get nm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn hasHeader?
  ""
  [^HttpMessage msg
   ^CharSequence nm]
  (-> (.headers msg) (.contains nm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn slurpByteBuf
  ""
  ^long
  [^ByteBuf buf
   ^OutputStream out]
  (if-some [len (some-> buf
                        (.readableBytes))]
    (when (> len 0)
      (.readBytes buf out (int len))
      (.flush out)
      len)
    0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn toByteArray
  ""
  ^bytes
  [^ByteBuf buf]
  (let [out (baos<>)]
    (if (> (slurpByteBuf buf out) 0)
      (.toByteArray out))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- coerce2bb
  ""
  ^ByteBuf
  [^Channel ch arg encoding]
  (let [buf (some-> ch
                    (.alloc) (.directBuffer))
        cs (Charset/forName encoding)]
    (cond
      (instBytes? arg)
      (if (some? buf)
        (.writeBytes buf ^bytes arg)
        (Unpooled/wrappedBuffer ^bytes arg))
      (string? arg)
      (if (some? buf)
        (doto buf (.writeCharSequence  ^CharSequence arg cs))
        (Unpooled/copiedBuffer ^CharSequence arg cs))
      :else (throwIOE "bad type"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn coerceToByteBuf
  ""
  ([arg ch] (coerceToByteBuf arg ch nil))
  ([arg] (coerceToByteBuf arg nil nil))
  ([arg ch encoding]
   (let [encoding (stror encoding "utf-8")
         ct (if-some
              [c (cast? XData arg)]
              (.content c) arg)]
     (cond
       (instBytes? ct) (coerce2bb ch ct encoding)
       (string? ct) (coerce2bb ch ct encoding)
       :else ct))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn writeLastContent
  ""
  {:tag ChannelFuture}

  ([^ChannelOutboundInvoker inv flush?]
   (if flush?
     (.writeAndFlush inv LastHttpContent/EMPTY_LAST_CONTENT)
     (.write inv LastHttpContent/EMPTY_LAST_CONTENT)))

  ([inv] (writeLastContent inv false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mockFullRequest<>
  ""
  {:tag FullHttpRequest}

  ([^HttpRequest req rel?]
   {:pre [(some? req)]}
   (let [rc (DefaultFullHttpRequest.
              (.protocolVersion req)
              (.method req)
              (.uri req))]
     (-> (.headers rc)
         (.set (.headers req)))
     (if rel?
       (ReferenceCountUtil/release req))
     rc))

  ([req]
   (mockFullRequest<> req false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn encodeNettyCookies
  ""
  ^APersistentVector
  [cookies]
  (preduce<vec>
    #(conj! %1
            (.encode
              ServerCookieEncoder/STRICT ^Cookie %2))
    cookies))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn encodeJavaCookies
  ""
  ^APersistentVector
  [cookies]
  (encodeNettyCookies (map #(nettyCookie<> %) cookies)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn detectAcceptableCharset
  ""
  ^Charset
  [^HttpRequest req]
  (let [cs (getHeader req HttpHeaderNames/ACCEPT_CHARSET)
        c (->> (.split (str cs) "[,;\\s]+")
               (some #(try! (Charset/forName ^String %))))]
    (or c (Charset/forName "utf-8"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getMsgCharset
  ""
  ^Charset
  [^HttpMessage msg]
  (HttpUtil/getCharset msg (Charset/forName "utf-8")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn closeCF
  "Close the channel unless keep-alive is set"

  ([cf] (closeCF cf false))
  ([^ChannelFuture cf keepAlive?]
    (if (and (some? cf)
             (not (boolean keepAlive?)))
      (.addListener cf ChannelFutureListener/CLOSE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpReq<+>
  ""
  {:tag FullHttpRequest}

  ([mt uri] (httpReq<+> mt uri nil))
  ([^HttpMethod mt ^String uri ^ByteBuf body]
   (if (nil? body)
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri)
     (DefaultFullHttpRequest. HttpVersion/HTTP_1_1 mt uri body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpReq<>
  ""
  ^HttpRequest
  [^HttpMethod mt ^String uri]
  (DefaultHttpRequest. HttpVersion/HTTP_1_1 mt uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpPost<+>
  ""
  ^FullHttpRequest
  [^String uri ^ByteBuf body] (httpReq<+> HttpMethod/POST uri body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpPost<>
  ""
  ^HttpRequest
  [^String uri] (httpReq<> HttpMethod/POST uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpGet<>
  ""
  ^FullHttpRequest
  [^String uri] (httpReq<+> HttpMethod/GET uri nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpReply<>
  "Create an incomplete response"
  {:tag HttpResponse}

  ([] (httpReply<> (.code HttpResponseStatus/OK)))
  ([code]
   {:pre [(number? code)]}
   (DefaultHttpResponse. HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf code))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpFullReply<>
  "Create a complete response"
  {:tag FullHttpResponse}

  ([status msg ^ByteBufAllocator alloc]
   (let [code (HttpResponseStatus/valueOf status)
         ver HttpVersion/HTTP_1_1]
     (cond
       (inst? ByteBuf msg)
       (DefaultFullHttpResponse. ver code ^ByteBuf msg)
       (nil? msg)
       (DefaultFullHttpResponse. ver code)
       :else
       (let [bb (some-> alloc
                        (.directBuffer))]
         (cond
           (nil? bb)
           (trap! IOException "No bytebuf")
           (instBytes? msg)
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
           (trap! IOException
                  (format "Rouge content %s" (type msg))))
         (DefaultFullHttpResponse. ver code bb)))))

  ([code] (httpFullReply<> code nil nil))

  ([] (httpFullReply<> (.code HttpResponseStatus/OK))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn replyStatus
  "Reply back to client with a status, no body"

  ([inv status] (replyStatus inv status false))

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
              (.code HttpResponseStatus/MOVED_PERMANENTLY)
              (.code HttpResponseStatus/TEMPORARY_REDIRECT))
            (httpFullReply<> ))
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
    (->> HttpResponseStatus/CONTINUE
         (.code )
         (httpFullReply<> )
         (.writeAndFlush inv ))
    (.addListener (cfop<e>))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbgRefCount
  "Show ref-count if a ref-counted object"
  [obj]
  (if-some
    [rc (cast? ReferenceCounted obj)]
    (log/debug
      "object %s: has refcount: %s" obj (.refCnt rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbgPipeline
  "List all handlers"
  [pipe]
  (log/debug "pipeline= %s"
             (cs/join "|" (.names ^ChannelPipeline pipe))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn safeRemoveHandler
  ""
  [^ChannelPipeline cp ^Class cz] (try! (.remove cp cz)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn fireNextAndQuit
  "Fire msg and remove the handler"

  ([^ChannelHandlerContext ctx handler msg retain?]
   (let [pp (.pipeline ctx)]
    (if (inst? ChannelHandler handler)
      (.remove pp ^ChannelHandler handler)
      (.remove pp (str handler)))
    (dbgPipeline pp)
    (if retain?
      (ReferenceCountUtil/retain msg))
    (.fireChannelRead ctx msg)))

  ([ctx handler msg]
   (fireNextAndQuit ctx handler msg false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn cfop<>
  "Create a ChannelFutureListener"
  ^ChannelFutureListener
  [func]
  {:pre [(fn? func)]}
  (reify ChannelFutureListener
    (operationComplete [_ ff] (try! (func ff)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn futureCB
  "Register a callback upon operation completion"
  ^ChannelFuture
  [cf arg]
  (if-some
    [ln (cond
          (inst? ChannelFutureListener arg) arg
          (fn? arg) (cfop<> arg)
          (nil? arg) nil
          :else
          (trap! IOException
                 (format "Rogue object %s" (type arg))))]
    (-> ^ChannelFuture
        cf
        (.addListener ^ChannelFutureListener ln))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn maybeHandle100?
  "Handle a *expect* header"

  ([^ChannelOutboundInvoker inv msg maxSize]
   (if (and (inst? HttpRequest msg)
            (HttpUtil/is100ContinueExpected msg))
     (let [error (and (HttpUtil/isContentLengthSet msg)
                      (spos? maxSize)
                      (> (HttpUtil/getContentLength msg) maxSize))
           rsp (->> (if error
                      (.code HttpResponseStatus/EXPECTATION_FAILED)
                      (.code HttpResponseStatus/CONTINUE))
                    (httpFullReply<> ))]
       (-> (.writeAndFlush inv rsp)
           (futureCB (if error (cfop<z>))))
       (not error))
     true))

  ([inv msg]
   (maybeHandle100? inv msg -1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getMethod
  "Get the request method"
  ^String
  [^HttpMessage msg]
  (if-some
    [req (cast? HttpRequest msg)]
    (ucase (stror (getHeader req
                             "X-HTTP-Method-Override")
                  (.. req getMethod name)))
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn chkFormPost
  "Detects if a http form post"
  ^String
  [^HttpRequest req]
  (let
    [ct (->> HttpHeaderNames/CONTENT_TYPE
             (getHeader req)
             str
             lcase)
     method (getMethod req)]
    (cond
      (embeds? ct "application/x-www-form-urlencoded")
      (if (eqAny? method ["POST" "PUT"])
        "post"
        "url")
      (and (embeds? ct "multipart/form-data")
           (eqAny? method ["POST" "PUT"]))
      "multipart")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn isWEBSock?
  "Detects if a websocket request"
  [^HttpRequest req]
  (let [cn (->> HttpHeaderNames/CONNECTION
                (getHeader req)
                str
                lcase)
        ws (->> HttpHeaderNames/UPGRADE
                (getHeader req)
                str
                lcase)]
    (log/debug "checking if it's a websock request......")
    (and (embeds? ws "websocket")
         (embeds? cn "upgrade")
         (= "GET" (getMethod req)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn maybeSSL? "" [c] (some-> (cpipe c) (.get SslHandler) (some?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getUriPath
  ""
  ^String
  [^HttpMessage msg]
  (if-some [req (cast? HttpRequest msg)]
    (.path (QueryStringDecoder. (.uri req))) ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getUriParams
  ""
  ^Map
  [^HttpMessage msg]
  (if-some
    [req (cast? HttpRequest msg)]
    (-> (.uri req)
        (QueryStringDecoder. ) (.parameters))
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn httpCookie<>
  ""
  ^HttpCookie
  [^Cookie c]
  {:pre [(some? c)]}

  (doto (HttpCookie. (.getName c)
                     (.getValue c))
    (.setComment (.getComment c))
    (.setDomain (.getDomain c))
    (.setMaxAge (.getMaxAge c))
    (.setPath (.getPath c))
    (.setVersion (.getVersion c))
    (.setHttpOnly (.isHttpOnly c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn crackCookies
  ""
  ^APersistentMap
  [^HttpMessage msg]

  (if-some+
    [v (getHeader msg HttpHeaderNames/COOKIE)]
    (preduce<map>
      #(assoc! %1
               (.getName ^Cookie %2)
               (httpCookie<> %2))
      (.decode ServerCookieDecoder/STRICT v))
    {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn matchRoute
  ""
  [^ChannelHandlerContext ctx ^HttpMessage msg]
  (let [dft {:status true}
        rc
        (if (inst? HttpRequest msg)
          (let [^RouteCracker c (getAKey ctx routes-key)]
            (if (and (some? c)
                     (.hasRoutes c))
              (.crack c {:method (getMethod msg)
                         :uri (getUriPath msg)}))))]
    (or rc dft)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn scanMsgGist
  ""
  ^APersistentMap
  [^ChannelHandlerContext ctx ^HttpMessage msg]
  (let
    [req (cast? HttpRequest msg)
     {:keys [routeInfo
             matcher
             status?
             redirect] :as ro}
     (matchRoute ctx msg)
     ri
     (if (and status?
              (some? routeInfo)
              (some? matcher))
       (.collect ^RouteInfo routeInfo ^Matcher matcher))
     m {:chunked? (HttpUtil/isTransferEncodingChunked msg)
        :isKeepAlive? (HttpUtil/isKeepAlive msg)
        :version (.. msg protocolVersion text)
        :framework :netty
        :parameters (getUriParams msg)
        :headers (.headers msg)
        :uri2 (str (some-> req (.uri )))
        :uri (getUriPath msg)
        :charset (getMsgCharset msg)
        :route (merge {:info routeInfo}
                      (dissoc ro :routeInfo) ri)
        :method (getMethod msg)
        :cookies (crackCookies msg)}]
    (if-some [s (some-> (cast? HttpResponse msg)
                        (.status))]
      (merge m {:status {:code (.code s)
                         :reason (.reasonPhrase s)}})
      m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro contentLengthAsInt
  ""
  [m]
  `(HttpUtil/getContentLength
     ~(with-meta m {:tag 'HttpMessage}) (int 0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro contentLength!
  ""
  [m len]
  `(HttpUtil/setContentLength
     ~(with-meta m {:tag 'HttpMessage}) (long len)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn contentType
  ""
  ^String
  [^HttpMessage m]
  (-> (.headers m)
      (.get HttpHeaderNames/CONTENT_TYPE "")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn noContent?
  ""
  [^HttpMessage m]
  (or (not (HttpUtil/isContentLengthSet m))
      (not (> (HttpUtil/getContentLength m -1) 0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defonce
  ^:private
  error-filter
  (proxy [InboundHandler][]
    (exceptionCaught [_ t]
      (log/error t ""))
    (channelRead0 [_ _])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro sharedErrorSinkName "" ^String [] `"sharedErrorSink")
(defn sharedErrorSink<> "" ^ChannelHandler [] error-filter)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn convCerts
  "Convert a set of Certificates"
  ^APersistentVector
  [arg]
  (let
    [[del ^InputStream inp] (coerceToInputStream arg)]
    (try
     (-> (CertificateFactory/getInstance "X.509")
         (.generateCertificates inp)
         (vec))
     (finally
       (if del (closeQ inp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


