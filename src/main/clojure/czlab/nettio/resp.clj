;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.resp

  (:require [czlab.convoy.mime :as mm :refer [guessContentType]]
            [czlab.convoy.core :as cc :refer :all]
            [czlab.nettio.ranges :as nr]
            [czlab.nettio.core :as nc]
            [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.convoy.wess :as ss]
            [czlab.basal.dates :as d]
            [czlab.basal.meta :as m]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

  (:import [io.netty.channel ChannelFuture Channel ChannelHandlerContext]
           [io.netty.buffer Unpooled ByteBuf ByteBufAllocator]
           [java.io IOException File InputStream]
           [io.netty.util ReferenceCountUtil]
           [clojure.lang APersistentVector]
           [java.nio.charset Charset]
           [java.net HttpCookie URL]
           [czlab.jasal DateUtil XData]
           [java.util Date]
           [io.netty.handler.codec.http
            HttpResponseStatus
            DefaultHttpHeaders
            FullHttpResponse
            HttpChunkedInput
            HttpVersion
            Cookie
            HttpUtil
            HttpHeaderValues
            HttpHeaderNames
            HttpResponse
            HttpHeaders]
           [io.netty.handler.stream
            ChunkedNioFile
            ChunkedInput
            ChunkedFile
            ChunkedStream]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

(def ^:private conds-hds
  [[:if-unmod-since HttpHeaderNames/IF_UNMODIFIED_SINCE]
   [:if-mod-since HttpHeaderNames/IF_MODIFIED_SINCE]
   [:if-none-match HttpHeaderNames/IF_NONE_MATCH]
   [:if-match HttpHeaderNames/IF_MATCH]
   [:if-range HttpHeaderNames/IF_RANGE]
   [:range HttpHeaderNames/RANGE]])

(def ^:private resp-hds
   [[:last-mod HttpHeaderNames/LAST_MODIFIED]
    [:etag HttpHeaderNames/ETAG]
    [:ctype HttpHeaderNames/CONTENT_TYPE]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-object NettyResultObj
  cc/HttpResultMsg
  cc/HttpMsgGist
  (msgHeader? [msg h]
    (.contains (nc/mg-headers?? msg) (nc/mg-cs?? h)))
  (msgHeader [msg h]
    (.get (nc/mg-headers?? msg) (nc/mg-cs?? h)))
  (msgHeaderKeys [msg]
    (set (.names (nc/mg-headers?? msg))))
  (msgHeaderVals [msg h]
    (vec (.getAll (nc/mg-headers?? msg) (nc/mg-cs?? h)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- result<> "" [theReq status]
  {:pre [(or (nil? status)
             (number? status))]}
  (c/object<> NettyResultObj
              {:status (or status (nc/scode HttpResponseStatus/OK))
               :ver (.text HttpVersion/HTTP_1_1)
               :headers (DefaultHttpHeaders.)
               :request theReq
               :cookies {}
               :framework :netty}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(declare replyer<> result<>)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(extend-type czlab.nettio.core.NettyH1Msg
  HttpResultMsgCreator
  (http-result
    ([theReq] (http-result theReq 200))
    ([theReq status] (result<> theReq status))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(extend-type czlab.nettio.resp.NettyResultObj
  HttpResultMsgReplyer
  (reply-result
    ([theRes] (reply-result theRes nil))
    ([theRes arg] (replyer<> theRes arg)))
  HttpResultMsgModifier
  (remove-res-header [res name]
    (c/do-with
      [res res]
      (.remove ^HttpHeaders (:headers res) ^CharSequence name)))
  (add-res-header [res name value]
    (c/do-with
      [res res]
      (nc/addHeader (:headers res) name value)))
  (set-res-header [res name value]
    (c/do-with
      [res res]
      (nc/setHeader (:headers res) name value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn eTagFromFile
  "ETag based on a file object" [^File f]
  (format "\"%s-%s\"" (.lastModified f) (.hashCode f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private codeOK? "" [c] `(let [c# ~c]
                                      (and (>= c# 200)(< c# 300))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private condErrCode "" [m]
  `(if
     (s/eqAny? ~m ["GET" "HEAD"])
     (nc/scode HttpResponseStatus/NOT_MODIFIED)
     (nc/scode HttpResponseStatus/PRECONDITION_FAILED)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- ifNoneMatch?
  "Condition fails if the reply eTag
  matches, or if filter is a wildcard"
  [method eTag code body conds]

  (let [ec (condErrCode method)
        {:keys [value has?]}
        (:if-none-match conds)
        value (s/strim value)
        c (cond
            (or (not has?)
                (s/nichts? value))
            code
            (s/eqAny? value ["*" "\"*\""])
            (if (s/hgl? eTag) ec code)
            (s/eqAny? eTag (map #(s/strim %)
                                (.split value ",")))
            ec
            :else code)]
    [c body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- ifMatch?
  "Condition fails if reply eTag doesn't
  match, or if filter is wildcard and no eTag"
  [method eTag code body conds]

  (let [ec (condErrCode method)
        {:keys [value has?]}
        (:if-match conds)
        value (s/strim value)
        c (cond
            (or (not has?)
                (s/nichts? value))
            code
            (s/eqAny? value ["*" "\"*\""])
            (if (s/hgl? eTag) code ec)
            (not (s/eqAny? eTag
                           (map #(s/strim %)
                                (.split value ","))))
            ec
            :else code)]
    [c body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- ifUnmodSince?
  "Condition fails if the last-modified
  timestamp is greater than the given date"
  [method lastMod code body conds]

  (let [rc (condErrCode method)
        {:keys [value has?]}
        (:if-unmod-since conds)
        value (s/strim value)
        t (DateUtil/parseHttpDate value -1)
        c (if (and (c/spos? lastMod)
                   (c/spos? t))
            (if (< lastMod t) code rc)
            code)]
    [c body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- ifModSince?
  "Condition fails if the last-modified
  time-stamp is less than the given date"
  [method lastMod code body conds]

  (let [rc (condErrCode method)
        {:keys [value has?]}
        (:if-mod-since conds)
        value (s/strim value)
        t (DateUtil/parseHttpDate value -1)
        c (if (and (c/spos? lastMod)
                   (c/spos? t))
            (if (> lastMod t) code rc)
            code)]
    [c body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- ifRange?
  "Sort out the range if any, then apply
  the if-range condition"
  [eTag lastMod code cType body conds]

  (let
    [ec (nc/scode HttpResponseStatus/REQUESTED_RANGE_NOT_SATISFIABLE)
     pc (nc/scode HttpResponseStatus/PARTIAL_CONTENT)
     ^String hd (get-in conds [:if-range :value])
     {:keys [value has?]}
     (:range conds)
     value (s/strim value)
     g (nr/evalRanges (if has? value nil) cType body)]
    (cond
      (or (not has?)
          (s/nichts? value))
      [code body]
      (nil? g)
      [ec nil]
      (s/nichts? hd)
      [pc g]
      (and (.endsWith hd "GMT")
           (> (.indexOf hd (int \,)) 0)
           (> (.indexOf hd (int \:)) 0))
      (let [t (DateUtil/parseHttpDate hd -1)]
        (if (and (c/spos? lastMod)
                 (c/spos? t)
                 (> lastMod t))
          [code body]
          [pc g]))
      (not= hd eTag)
      [code body]
      :else
      [pc g])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn converge
  "" [^Charset cs body]

  (let [body (if (c/ist? XData body)
               (.content ^XData body) body)]
    (cond
      (m/isBytes? body)
      body
      (string? body)
      (.getBytes ^String body cs)
      :else body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- zmapHeaders "" [msg headers]
  (zipmap (map #(let [[k v] %1] k) headers)
          (map #(let [[k v] %1]
                  {:has? (cc/msgHeader? msg v)
                   :value (cc/msgHeader msg v)}) headers)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- writeHeaders
  "" ^HttpHeaders
  [^HttpResponse rsp headers] (.set (.headers rsp) headers))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- replyer<> "" [res sessionObj]

  (log/debug "replyer called with res = %s" res)
  (let
    [res (ss/downstream res sessionObj)
     req (:request res)
     ^Channel
     ch (:socket req)
     method (:method req)
     {:keys [headers
             lastMod
             eTag
             cookies] :as cfg}
     res
     cs (c/toCharset (:charset res))
     code (:status res)
     body0 (->> (:body res)
                (converge cs))
     _ (log/debug "resp: body0===> %s" body0)
     conds (zmapHeaders req conds-hds)
     rhds (zmapHeaders res resp-hds)
     cType (get-in rhds [:ctype :value])
     [code body]
     (if (codeOK? code)
       (ifRange? eTag lastMod code cType body0 conds)
       [code body0])
     [code body]
     (if (codeOK? code)
       (ifMatch? method eTag code body conds)
       [code body])
     [code body]
     (if (codeOK? code)
       (ifNoneMatch? method eTag code body conds)
       [code body])
     [code body]
     (if (codeOK? code)
       (ifModSince? method lastMod code body conds)
       [code body])
     [code body]
     (if (codeOK? code)
       (ifUnmodSince? method lastMod code body conds)
       [code body])
     [code body]
     (if (and (= "HEAD" method)
              (codeOK? code))
       [code nil]
       [code body])
     [body clen]
     (cond
       (c/ist? InputStream body)
       [(HttpChunkedInput.
          (ChunkedStream. ^InputStream body)) -1]

       (c/ist? czlab.nettio.ranges.HttpRanges body)
       [(HttpChunkedInput. ^ChunkedInput body)
        (.length ^ChunkedInput body)]

       (m/instBytes? body)
       [body (alength ^bytes body)]

       (c/ist? File body)
       [(HttpChunkedInput.
          (ChunkedNioFile. ^File body))
        (.length ^File body)]

       (nil? body)
       [nil 0]
       :else
       (c/throwIOE "Unsupported result content"))
     [rsp body]
     (cond
       (m/instBytes? body)
       [(nc/httpFullReply<> code body (.alloc ch)) nil]
       (or (== clen 0)
           (nil? body))
       [(nc/httpFullReply<> code) body]
       :else
       [(nc/httpReply<> code) body])
     hds (writeHeaders rsp headers)]
    (log/debug "response = %s" rsp)
    (log/debug "body = %s" body)
    (log/debug "body-len = %s" clen)
    (->> (and (not (c/ist? FullHttpResponse rsp))
              (some? body))
         (HttpUtil/setTransferEncodingChunked rsp ))
    (if-not (c/sneg? clen)
      (HttpUtil/setContentLength rsp clen))
    (if (c/sneg? clen)
      (HttpUtil/setKeepAlive rsp false)
      (HttpUtil/setKeepAlive rsp (:isKeepAlive? req)))
    (if (or (nil? body)
            (== clen 0))
      (.remove hds HttpHeaderNames/CONTENT_TYPE))
    (doseq [s (nc/encodeJavaCookies (vals cookies))]
      (log/debug "resp: setting cookie: %s" s)
      (.add hds HttpHeaderNames/SET_COOKIE s))
    (if (and (c/spos? lastMod)
             (not (get-in rhds [:last-mod :has?])))
      (.set hds
            HttpHeaderNames/LAST_MODIFIED
            (DateUtil/formatHttpDate ^long lastMod)))
    (if (and (s/hgl? eTag)
             (not (get-in rhds [:etag :has?])))
      (.set hds HttpHeaderNames/ETAG eTag))
    (let [c? (HttpUtil/isKeepAlive rsp)
          cf (.write ch rsp)
          cf (if body
               (.writeAndFlush ch body)
               (do (.flush ch) cf))]
      (log/debug "resp replied, keep-alive? = %s" c?)
      (nc/closeCF cf c?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

