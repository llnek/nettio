;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.resp

  (:require [czlab.convoy.mime :as mm]
            [czlab.convoy.core :as cc]
            [czlab.nettio.core :as nc]
            [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.dates :as d]
            [czlab.basal.util :as u]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.convoy.webss :as ss]
            [czlab.nettio.ranges :as nr])

  (:import [io.netty.channel ChannelFuture Channel ChannelHandlerContext]
           [io.netty.buffer Unpooled ByteBuf ByteBufAllocator]
           [java.io IOException File InputStream]
           [io.netty.util ReferenceCountUtil]
           [clojure.lang APersistentVector]
           [java.nio.charset Charset]
           [java.net HttpCookie URL]
           [java.util Date]
           [czlab.basal XData]
           [czlab.nettio DateUtil]
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
(defrecord NettyResultObj []
  cc/HttpResultMsg
  cc/HttpMsgGist
  (msg-header? [msg h]
    (.contains (nc/mg-headers?? msg) (nc/mg-cs?? h)))
  (msg-header [msg h]
    (.get (nc/mg-headers?? msg) (nc/mg-cs?? h)))
  (msg-header-keys [msg]
    (set (.names (nc/mg-headers?? msg))))
  (msg-header-vals [msg h]
    (vec (.getAll (nc/mg-headers?? msg) (nc/mg-cs?? h)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare replyer<> result<>)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- result<> "" [theReq status]
  {:pre [(or (nil? status)
             (number? status))]}
  (assoc (NettyResultObj.)
         :ver (.text HttpVersion/HTTP_1_1)
         :headers (DefaultHttpHeaders.)
         :request theReq
         :cookies {}
         :framework :netty
         :status (or status (nc/scode HttpResponseStatus/OK))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-type czlab.nettio.core.NettyH1Msg
  cc/HttpResultMsgCreator
  (http-result
    ([theReq] (cc/http-result theReq 200))
    ([theReq status] (result<> theReq status))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-type czlab.nettio.resp.NettyResultObj
  cc/HttpResultMsgReplyer
  (reply-result
    ([theRes] (cc/reply-result theRes nil))
    ([theRes arg] (replyer<> theRes arg)))
  cc/HttpResultMsgModifier
  (res-header-del [res name]
    (c/do-with
      [res res]
      (.remove ^HttpHeaders (:headers res) ^CharSequence name)))
  (res-header-add [res name value]
    (c/do-with
      [res res]
      (nc/add-header (:headers res) name value)))
  (res-header-set [res name value]
    (c/do-with
      [res res]
      (nc/set-header (:headers res) name value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn etag-from-file
  "ETag based on a file object" [^File f]
  (format "\"%s-%s\"" (.lastModified f) (.hashCode f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private code-ok?
  "" [c] `(let [c# ~c]
            (and (>= c# 200)(< c# 300))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private cond-err-code "" [m]
  `(if
     (s/eq-any? ~m ["GET" "HEAD"])
     (nc/scode HttpResponseStatus/NOT_MODIFIED)
     (nc/scode HttpResponseStatus/PRECONDITION_FAILED)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-none-match?
  "Condition fails if the reply eTag
  matches, or if filter is a wildcard"
  [method eTag code body conds]

  (let [ec (cond-err-code method)
        {:keys [value has?]}
        (:if-none-match conds)
        value (s/strim value)
        c (cond
            (or (not has?)
                (s/nichts? value))
            code
            (s/eq-any? value ["*" "\"*\""])
            (if (s/hgl? eTag) ec code)
            (s/eq-any? eTag (map #(s/strim %)
                                 (.split value ",")))
            ec
            :else code)]
    [c body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-match?
  "Condition fails if reply eTag doesn't
  match, or if filter is wildcard and no eTag"
  [method eTag code body conds]

  (let [ec (cond-err-code method)
        {:keys [value has?]}
        (:if-match conds)
        value (s/strim value)
        c (cond
            (or (not has?)
                (s/nichts? value))
            code
            (s/eq-any? value ["*" "\"*\""])
            (if (s/hgl? eTag) code ec)
            (not (s/eq-any? eTag
                            (map #(s/strim %)
                                 (.split value ","))))
            ec
            :else code)]
    [c body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-unmod-since?
  "Condition fails if the last-modified
  timestamp is greater than the given date"
  [method lastMod code body conds]

  (let [rc (cond-err-code method)
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
(defn- if-mod-since?
  "Condition fails if the last-modified
  time-stamp is less than the given date"
  [method lastMod code body conds]

  (let [rc (cond-err-code method)
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
(defn- if-range?
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
     g (nr/eval-ranges (if has? value nil) cType body)]
    (cond
      (or (not has?)
          (s/nichts? value))
      [code body]
      (nil? g)
      [ec nil]
      (s/nichts? hd)
      (let []
        [pc g])
      (and (cs/ends-with? hd "GMT")
           (cs/index-of hd \,)
           (cs/index-of hd \:))
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
(defn converge
  "" [^Charset cs body]

  (let [body (if (c/is? XData body)
               (.content ^XData body) body)]
    (cond
      (bytes? body)
      body
      (string? body)
      (i/x->bytes body cs)
      :else body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- zmap-headers "" [msg headers]
  (zipmap (map #(let [[k v] %1] k) headers)
          (map #(let [[k v] %1]
                  {:has? (cc/msg-header? msg v)
                   :value (cc/msg-header msg v)}) headers)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- write-headers
  "" ^HttpHeaders
  [^HttpResponse rsp headers] (.set (.headers rsp) headers))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- replyer<> "" [res sessionObj]
  (l/debug "replyer called with res = %s.\nsessionObj = %s." res sessionObj)
  (let
    [res (ss/downstream res sessionObj)
     req (:request res)
     ^Channel
     ch (:socket req)
     method (:method req)
     {:keys [headers
             last-mod
             etag
             cookies] :as cfg}
     res
     cs (u/charset?? (:charset res))
     code (:status res)
     body0 (->> (:body res)
                (converge cs))
     _ (l/debug "resp: body0===> %s." body0)
     conds (zmap-headers req conds-hds)
     rhds (zmap-headers res resp-hds)
     cType (get-in rhds [:ctype :value])
     [code body]
     (if (code-ok? code)
       (if-range? etag last-mod code cType body0 conds)
       [code body0])
     [code body]
     (if (code-ok? code)
       (if-match? method etag code body conds)
       [code body])
     [code body]
     (if (code-ok? code)
       (if-none-match? method etag code body conds)
       [code body])
     [code body]
     (if (code-ok? code)
       (if-mod-since? method last-mod code body conds)
       [code body])
     [code body]
     (if (code-ok? code)
       (if-unmod-since? method last-mod code body conds)
       [code body])
     [code body]
     (if (and (= "HEAD" method)
              (code-ok? code))
       [code nil]
       [code body])
     rangeRef
     (if (c/is? czlab.nettio.ranges.HttpRanges body) body)
     [body clen]
     (cond
       (c/is? InputStream body)
       [(HttpChunkedInput.
          (ChunkedStream. ^InputStream body)) -1]

       (some? rangeRef)
       [(HttpChunkedInput. ^ChunkedInput body)
        (.length ^ChunkedInput body)]

       (bytes? body)
       [body (alength ^bytes body)]

       (c/is? File body)
       [(HttpChunkedInput.
          (ChunkedNioFile. ^File body))
        (.length ^File body)]

       (nil? body)
       [nil 0]
       :else
       (u/throw-IOE "Unsupported result content"))
     [rsp body]
     (cond
       (bytes? body)
       [(nc/http-reply<+> code body (.alloc ch)) nil]
       (nil? body)
       [(nc/http-reply<+> code) nil]
       (and (zero? clen)
            (nil? body))
       [(nc/http-reply<+> code) nil]
       :else
       [(nc/http-reply<> code) body])
     hds (write-headers rsp headers)]
    (cond
      (= code 416)
      (nr/fmt-error hds body0)
      (some? rangeRef)
      (nr/fmt-success hds rangeRef))
    (l/debug "response = %s" rsp)
    (l/debug "body = %s" body)
    (l/debug "body-len = %s" clen)
    (->> (and (not (c/is? FullHttpResponse rsp))
              (some? body))
         (HttpUtil/setTransferEncodingChunked rsp ))
    (if-not (c/sneg? clen)
      (HttpUtil/setContentLength rsp clen))
    (if (c/sneg? clen)
      (HttpUtil/setKeepAlive rsp false)
      (HttpUtil/setKeepAlive rsp (:is-keep-alive? req)))
    (if (or (nil? body)
            (and (zero? clen)(nil? body)))
      (.remove hds HttpHeaderNames/CONTENT_TYPE))
    (doseq [s (nc/encode-java-cookies (vals cookies))]
      (l/debug "resp: setting cookie: %s" s)
      (.add hds HttpHeaderNames/SET_COOKIE s))
    (if (and (c/spos? last-mod)
             (not (get-in rhds [:last-mod :has?])))
      (.set hds
            HttpHeaderNames/LAST_MODIFIED
            (DateUtil/formatHttpDate ^long last-mod)))
    (if (and (s/hgl? etag)
             (not (get-in rhds [:etag :has?])))
      (.set hds HttpHeaderNames/ETAG etag))
    (let [c? (HttpUtil/isKeepAlive rsp)
          cf (if (nil? body)
               (do
                 (l/debug "reply has NO body, write and flush %s" rsp)
                 (.writeAndFlush ch rsp))
               (do
                 (l/debug "reply has SOME body, write and flush %s" rsp)
                 (.write ch rsp)
                 (l/debug "reply body, write and flush body: %s" body)
                 (.writeAndFlush ch body)))]
      (l/debug "resp replied, keep-alive? = %s" c?)
      (nc/close-cf cf c?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

