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

  (:use [czlab.convoy.net.routes]
        [czlab.convoy.net.core]
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
           [czlab.convoy.nettio
            InboundAdapter
            WholeRequest
            WholeResponse
            InboundHandler]
           [io.netty.channel.nio NioEventLoopGroup]
           [java.net InetAddress URL HttpCookie]
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
(defmacro akey<> "New Attribute" [n] `(AttributeKey/newInstance ~n))
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
         (.. ^Channel c id)
         nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderResult
  "" ^DecoderResult [msg]
  (some-> (cast? DecoderResultProvider msg) .decoderResult))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderSuccess?
  "" [msg] (if-some [r (decoderResult msg)] (.isSuccess r) true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn decoderError
  "" ^Throwable [msg]
  (if-some
    [r (some-> (cast? DecoderResultProvider msg)
               .decoderResult)]
    (if-not (.isSuccess r) (.cause r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn setDecoderError!
  "" [msg err] {:pre [(ist? Throwable err)]}

  (if-some
    [p (cast? DecoderResultProvider msg)]
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
(defn getHeaderVals
  ""
  [^HttpMessage msg
   ^CharSequence nm]
  (-> (.headers msg) (.getAll nm)))

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

  (let [out (baos<>)]
    (if (> (slurpByteBuf buf out) 0)
      (.toByteArray out))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- coerce2bb
  "" ^ByteBuf [ch arg encoding]

  (let [buf (some-> ^Channel
                    ch
                    .alloc .directBuffer)
        cs (Charset/forName encoding)]
    (cond
      (instBytes? arg)
      (if buf
        (.writeBytes buf ^bytes arg)
        (Unpooled/wrappedBuffer ^bytes arg))
      (string? arg)
      (if buf
        (doto buf (.writeCharSequence  ^CharSequence arg cs))
        (Unpooled/copiedBuffer ^CharSequence arg cs))
      :else (throwIOE "bad type"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn byteBuf?? ""

  ([arg ch] (byteBuf?? arg ch nil))
  ([arg] (byteBuf?? arg nil nil))

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
        @req
        rc (DefaultFullHttpRequest.
              (HttpVersion/valueOf version)
              (HttpMethod/valueOf method)
              uri2)]
    (assert (ist? HttpHeaders headers))
    (-> (.headers rc)
        (.set ^HttpHeaders headers))
    rc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn encodeNettyCookies
  "" ^APersistentVector [cookies]

  (preduce<vec>
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
               (some #(try! (Charset/forName ^String %))))]
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

  ([] (httpReply<> (.code HttpResponseStatus/OK)))
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
       (ist? ByteBuf msg)
       (DefaultFullHttpResponse. ver code ^ByteBuf msg)
       (nil? msg)
       (DefaultFullHttpResponse. ver code)
       :else
       (let [bb (some-> alloc
                        .directBuffer)]
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
              (.code HttpResponseStatus/MOVED_PERMANENTLY)
              (.code HttpResponseStatus/TEMPORARY_REDIRECT))
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
    (->> HttpResponseStatus/CONTINUE
         .code
         httpFullReply<>
         (.writeAndFlush inv ))
    (.addListener (cfop<e>))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbgRefCount
  "Show ref-count of object" [obj]
  (if-some
    [rc (cast? ReferenceCounted obj)]
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
  "" [^ChannelPipeline cp ^Class cz] (trye!! nil (.remove cp cz)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn fireNextAndQuit
  "Fire msg and remove the handler"

  ([^ChannelHandlerContext ctx handler msg retain?]
   (let [pp (.pipeline ctx)]
    (if (ist? ChannelHandler handler)
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
  ^ChannelFutureListener [func] {:pre [(fn? func)]}
  (reify ChannelFutureListener (operationComplete
                                 [_ ff] (try! (func ff)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn futureCB
  "Reg. callback" ^ChannelFuture [cf arg]

  (if-some
    [ln (cond
          (ist? ChannelFutureListener arg) arg
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
   (if (and (ist? HttpRequest msg)
            (HttpUtil/is100ContinueExpected msg))
     (let [error (and (HttpUtil/isContentLengthSet msg)
                      (spos? maxSize)
                      (> (HttpUtil/getContentLength msg) maxSize))
           rsp (->> (if error
                      (.code HttpResponseStatus/EXPECTATION_FAILED)
                      (.code HttpResponseStatus/CONTINUE))
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
    [req (cast? HttpRequest msg)]
    (ucase (stror (getHeader req
                             "X-HTTP-Method-Override")
                  (.. req getMethod name)))
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn chkFormPost
  "Detects if a form post" ^String [req]

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
  "Detects if a websock req" [req]

  (let [cn (->> HttpHeaderNames/CONNECTION
                (msgHeader req)
                str
                lcase)
        ws (->> HttpHeaderNames/UPGRADE
                (msgHeader req)
                str
                lcase)]
    (log/debug "checking if it's a websock request......")
    (and (embeds? ws "websocket")
         (embeds? cn "upgrade")
         (= "GET" (:method @req)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn maybeSSL? "" [c] (some-> (cpipe c) (.get SslHandler) (some?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getUriPath "" ^String [msg]
  (if-some [req (cast? HttpRequest msg)]
    (. (QueryStringDecoder. (.uri req)) path) ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getUriParams
  "" ^Map [^HttpMessage msg]

  (if-some
    [req (cast? HttpRequest msg)]
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

  (if (ist? HttpRequest msg)
    (if-some+
      [v (getHeader msg
                    HttpHeaderNames/COOKIE)]
      (preduce<map>
        #(assoc! %1
                 (.name ^Cookie %2)
                 (httpCookie<> %2))
        (.decode ServerCookieDecoder/STRICT v)))
    (preduce<map>
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
        (if (ist? HttpRequest msg)
          (let [c (getAKey ctx routes-key)]
            (if (and c
                     (hasRoutes? c))
              (crackRoute c {:method (getMethod msg)
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
  "" [ctx ^WholeRequest req]

  (let
    [^HttpRequest msg (.intern req)
     {:keys [routeInfo matcher
             status? redirect] :as ro}
     (matchOneRoute ctx msg)
     ri (if (and status? routeInfo matcher)
          (collectInfo routeInfo matcher))]
    (merge
      (dftReqMsgObj)
      {:chunked? (HttpUtil/isTransferEncodingChunked msg)
       :isKeepAlive? (HttpUtil/isKeepAlive msg)
      :version (.. msg protocolVersion text)
      :method (getMethod msg)
      :body (.content req)
      :socket (ch?? ctx)
      :ssl? (maybeSSL? ctx)
      :parameters (getUriParams msg)
      :headers (.headers msg)
      :uri2 (str (some-> msg .uri))
      :uri (getUriPath msg)
      :charset (getMsgCharset msg)
      :cookies (crackCookies msg)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn gistH1Response
  "" [ctx ^WholeResponse rsp]

  (let [^HttpResponse msg (.intern rsp)]
    (merge
      (dftRspMsgObj)
      {:chunked? (HttpUtil/isTransferEncodingChunked msg)
       :isKeepAlive? (HttpUtil/isKeepAlive msg)
       :version (.. msg protocolVersion text)
       :socket (ch?? ctx)
       :body (.content rsp)
       :ssl? (maybeSSL? ctx)
       :headers (.headers msg)
       :charset (getMsgCharset msg)
       :cookies (crackCookies msg)}
      (let [s (.status msg)]
        {:status {:code (.code s)
                  :reason (.reasonPhrase s)}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro gistH1Message "" [ctx m]
  `(let [c# ~ctx m# ~m]
     (if (ist? czlab.convoy.nettio.WholeRequest m#)
       (gistH1Request c# m#)
       (if (ist? czlab.convoy.nettio.WholeResponse m#)
         (gistH1Response c# m#)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro contentLengthAsInt
  "" [m] `(HttpUtil/getContentLength
            ~(with-meta m {:tag 'HttpMessage}) (int 0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro contentLength!
  "" [m len] `(HttpUtil/setContentLength
                ~(with-meta m {:tag 'HttpMessage}) (long len)))

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
    (channelRead0 [_ _])
    (exceptionCaught [_ t] (log/error t ""))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn sharedErrorSink<> "" ^ChannelHandler [] error-filter)
(def ^String sharedErrorSinkName "sharedErrorSink")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn convCerts
  "Convert Certs" ^APersistentVector [arg]

  (let [[del? inp] (inputStream?? arg)]
    (try
      (-> (CertificateFactory/getInstance "X.509")
          (.generateCertificates  ^InputStream inp) vec)
      (finally
       (if del? (closeQ inp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


