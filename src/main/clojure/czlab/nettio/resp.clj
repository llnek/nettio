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

  (:require [czlab.convoy.mime :refer [guessContentType]]
            [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.nettio.core]
        [czlab.convoy.core]
        [czlab.convoy.wess]
        [czlab.basal.dates]
        [czlab.basal.meta]
        [czlab.basal.str]
        [czlab.basal.io]
        [czlab.basal.core])

  (:import [io.netty.channel ChannelFuture Channel ChannelHandlerContext]
           [io.netty.buffer Unpooled ByteBuf ByteBufAllocator]
           [java.io IOException File InputStream]
           [io.netty.util ReferenceCountUtil]
           [clojure.lang APersistentVector]
           [czlab.nettio HttpRanges]
           [czlab.convoy MvcUtils]
           [java.nio.charset Charset]
           [java.net HttpCookie URL]
           [czlab.jasal XData]
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
(defstateful HttpResultMsgObj

  HttpMsgGist
  (msgHeader [_ nm]
    (.get ^HttpHeaders
          (:headers @data) ^CharSequence nm))
  (msgHeader? [_ nm]
    (.contains ^HttpHeaders
               (:headers @data) ^CharSequence nm))
  (msgHeaderKeys [_] )
  (msgHeaderVals [_ h] )

  HttpResultMsg
  (setResContentType [this c]
    (.set ^HttpHeaders
          (:headers @data) HttpHeaderNames/CONTENT_TYPE c))
  (getResContentType [me]
    (.msgHeader me HttpHeaderNames/CONTENT_TYPE))
  (setResETag [this e]
    (.set ^HttpHeaders (:headers @data) HttpHeaderNames/ETAG e))
  (addResCookie [me c]
    (if (some? c)
      (alterStateful
        me
        update-in
        [:cookies]
        assoc (.getName ^HttpCookie c) c)))
  (removeResHeader [_ nm] (.remove ^HttpHeaders
                                   (:headers @data) ^CharSequence nm))
  (clearResHeaders [_] (.clear ^HttpHeaders (:headers @data)))
  (clearResCookies [me]
    (alterStateful me assoc :cookies {}))
  (addResHeader [_ nm v]
    (.add ^HttpHeaders (:headers @data) ^CharSequence nm v))
  (setResHeader [_ nm v]
    (.set ^HttpHeaders (:headers @data) ^CharSequence nm v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- result<> "" [ch theReq status]
  {:pre [(or (nil? status)
             (number? status))]}
  (entity<> HttpResultMsgObj
            {:status (or status (.code HttpResponseStatus/OK))
             :ver (.text HttpVersion/HTTP_1_1)
             :headers (DefaultHttpHeaders.)
             :request theReq
             :cookies {}
             :framework :netty}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(extend-protocol HttpResultMsgCreator
  io.netty.channel.Channel
  (httpResult [ch theReq] (httpResult ch theReq 200))
  (httpResult [ch theReq status] (result<> ch theReq status)))

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
     (eqAny? ~m ["GET" "HEAD"])
     (.code HttpResponseStatus/NOT_MODIFIED)
     (.code HttpResponseStatus/PRECONDITION_FAILED)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- ifNoneMatch?
  "Condition fails if the reply eTag
  matches, or if filter is a wildcard"
  [method eTag code body conds]

  (let [ec (condErrCode method)
        {:keys [value has?]}
        (:if-none-match conds)
        value (strim value)
        c (cond
            (or (not has?)
                (nichts? value))
            code
            (eqAny? value ["*" "\"*\""])
            (if (hgl? eTag) ec code)
            (eqAny? eTag (map #(strim %)
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
        value (strim value)
        c (cond
            (or (not has?)
                (nichts? value))
            code
            (eqAny? value ["*" "\"*\""])
            (if (hgl? eTag) code ec)
            (not (eqAny? eTag
                         (map #(strim %)
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
        value (strim value)
        t (MvcUtils/parseHttpDate value -1)
        c (if (and (spos? lastMod)
                   (spos? t))
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
        value (strim value)
        t (MvcUtils/parseHttpDate value -1)
        c (if (and (spos? lastMod)
                   (spos? t))
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
    [ec (.code HttpResponseStatus/REQUESTED_RANGE_NOT_SATISFIABLE)
     pc (.code HttpResponseStatus/PARTIAL_CONTENT)
     ^String hd (get-in conds [:if-range :value])
     {:keys [value has?]}
     (:range conds)
     value (strim value)
     g (HttpRanges/eval
         ^String (if has? value nil) cType body)]
    (cond
      (or (not has?)
          (nichts? value))
      [code body]
      (nil? g)
      [ec nil]
      (nichts? hd)
      [pc g]
      (and (.endsWith hd "GMT")
           (> (.indexOf hd (int \,)) 0)
           (> (.indexOf hd (int \:)) 0))
      (let [t (MvcUtils/parseHttpDate hd -1)]
        (if (and (spos? lastMod)
                 (spos? t)
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

  (let [body (if (ist? XData body)
               (.content ^XData body) body)]
    (cond
      (isBytes? body)
      body
      (string? body)
      (.getBytes ^String body cs)
      :else body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- zmapHeaders "" [msg headers]
  (zipmap (map #(let [[k v] %1] k) headers)
          (map #(let [[k v] %1]
                  {:has? (msgHeader? msg v)
                   :value (msgHeader msg v)}) headers)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- writeHeaders
  "" ^HttpHeaders
  [^HttpResponse rsp headers] (.set (.headers rsp) headers))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- replyer<> "" [res sessionObj]

  (downstream res sessionObj)
  (let
    [req (:request @res)
     ^Channel
     ch (:socket @req)
     method (:method @req)
     {:keys [headers
             lastMod
             eTag
             cookies] :as cfg}
     @res
     cs (or (:charset @res)
            (Charset/forName "utf-8"))
     code (:status @res)
     body0 (->> (:body @res)
                (converge cs))
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
       (ist? InputStream body)
       [(HttpChunkedInput.
          (ChunkedStream. ^InputStream body)) -1]

       (ist? HttpRanges body)
       [(HttpChunkedInput. ^HttpRanges body)
        (.length ^HttpRanges body)]

       (instBytes? body)
       [body (alength ^bytes body)]

       (ist? File body)
       [(HttpChunkedInput.
          (ChunkedNioFile. ^File body))
        (.length ^File body)]

       (nil? body)
       [nil 0]
       :else
       (trap! IOException "Unsupported result content"))
     [rsp body]
     (cond
       (instBytes? body)
       [(httpFullReply<> code body (.alloc ch)) nil]
       (or (== clen 0)
           (nil? body))
       [(httpFullReply<> code) body]
       :else
       [(httpReply<> code) body])
     hds (writeHeaders rsp headers)]
    (->> (and (not (ist? FullHttpResponse rsp))
              (some? body))
         (HttpUtil/setTransferEncodingChunked rsp ))
    (if-not (neg? clen)
      (HttpUtil/setContentLength rsp clen))
    (if (neg? clen)
      (HttpUtil/setKeepAlive rsp false)
      (HttpUtil/setKeepAlive rsp (:isKeepAlive? @req)))
    (if (or (nil? body)
            (== clen 0))
      (.remove hds HttpHeaderNames/CONTENT_TYPE))
    (doseq [s (encodeJavaCookies (vals cookies))]
      (log/debug "resp: setting cookie: %s" s)
      (.add hds HttpHeaderNames/SET_COOKIE s))
    (if (and (spos? lastMod)
             (not (get-in rhds [:last-mod :has?])))
      (.set hds
            HttpHeaderNames/LAST_MODIFIED
            (MvcUtils/formatHttpDate ^long lastMod)))
    (if (and (hgl? eTag)
             (not (get-in rhds [:etag :has?])))
      (.set hds HttpHeaderNames/ETAG eTag))
    (let [cf (.write ch rsp)
          cf (if body
               (.writeAndFlush ch body)
               (do (.flush ch) cf))]
      (closeCF cf (HttpUtil/isKeepAlive rsp)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(extend-protocol HttpResultMsgReplyer
  io.netty.channel.Channel
  (replyResult [ch theRes] (replyResult ch theRes nil))
  (replyResult [ch theRes arg] (replyer<> theRes arg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

