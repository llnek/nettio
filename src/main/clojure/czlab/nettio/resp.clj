;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.nettio.resp

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.niou.mime :as mm]
            [czlab.niou.core :as cc]
            [czlab.niou.webss :as ss]
            [czlab.basal.log :as l]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.basal.dates :as d]
            [czlab.nettio.core :as n]
            [czlab.nettio.http :as h1]
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
           [czlab.niou Headers DateUtil]
           [czlab.niou.core WsockMsg Http1xMsg HttpResultMsg]
           [czlab.nettio.ranges HttpRangesObj]
           [io.netty.handler.codec.http.cookie
            Cookie
            ServerCookieEncoder]
           [io.netty.handler.codec.http
            HttpResponseStatus
            DefaultHttpHeaders
            FullHttpResponse
            HttpChunkedInput
            HttpVersion
            HttpUtil
            HttpMessage
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- conds-hds
  [[:if-unmod-since (str (n/h1hdr* IF_UNMODIFIED_SINCE))]
   [:if-mod-since (str (n/h1hdr* IF_MODIFIED_SINCE))]
   [:if-none-match (str (n/h1hdr* IF_NONE_MATCH))]
   [:if-match (str (n/h1hdr* IF_MATCH))]
   [:if-range (str (n/h1hdr* IF_RANGE))]
   [:range (str (n/h1hdr* RANGE))]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- resp-hds
   [[:last-mod (str (n/h1hdr* LAST_MODIFIED))]
    [:etag (str (n/h1hdr* ETAG))]
    [:ctype (str (n/h1hdr* CONTENT_TYPE))]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare replyer<> result<>)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- result<>
  [theReq status]
  {:pre [(or (nil? status)
             (number? status))]}
  (c/object<> HttpResultMsg
              :headers (Headers.)
              :request theReq
              :cookies {}
              :protocol (.text HttpVersion/HTTP_1_1)
              :status (or status (n/scode* OK))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpResultMsgCreator
  Http1xMsg
  (http-result
    ([theReq] (cc/http-result theReq 200))
    ([theReq status] (result<> theReq status))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpResultMsgReplyer
  HttpResultMsg
  (reply-result
    ([theRes] (cc/reply-result theRes nil))
    ([theRes arg] (replyer<> theRes arg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpResultMsgReplyer
  WsockMsg
  (reply-result
    ([theRes] (cc/reply-result theRes nil))
    ([theRes _] (let [ch (:socket theRes)]
                  (some-> ch (n/write-msg theRes))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol cc/HttpResultMsgModifier
  HttpResultMsg
  (res-cookie-add [res cookie]
    (update-in res
               [:cookies]
               assoc (.getName ^HttpCookie cookie) cookie))
  (res-body-set [res body]
    (assoc res :body body))
  (res-header-add [res name value]
    (c/do-with [res]
      (.add (n/ghdrs res) ^String name value)))
  (res-header-set [res name value]
    (c/do-with [res]
      (.set (n/ghdrs res) ^String name value)))
  (res-header-del [res name]
    (c/do-with [res]
      (.remove (n/ghdrs res) ^String name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn etag-file

  "ETag based on a file object."
  ^String [in]

  (if-some [f (io/file in)]
    (format "\"%s-%s\"" (.lastModified f) (.hashCode f))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- code-ok?

  [c] `(let [c# ~c] (and (>= c# 200)(< c# 300))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- cond-err-code

  [m] `(let [m# ~m]
         (if (or (= m# :get) (= m# :head))
           (n/scode* ~'NOT_MODIFIED)
           (n/scode* ~'PRECONDITION_FAILED))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-none-match?

  "Condition fails if the reply eTag
  matches, or if filter is a wildcard."
  [method eTag code body conds]

  (let [{:keys [value has?]} (:if-none-match conds)
        value (c/strim value)
        ec (cond-err-code method)]
    [(cond (or (not has?) (c/nichts? value)) code
            (c/eq-any? value ["*" "\"*\""]) (if (c/hgl? eTag) ec code)
            (c/eq-any? eTag (map #(c/strim %)
                                 (c/split value ","))) ec :else code) body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-match?

  "Condition fails if reply eTag doesn't
  match, or if filter is wildcard and no eTag."
  [method eTag code body conds]

  (let [{:keys [value has?]} (:if-match conds)
        value (c/strim value)
        ec (cond-err-code method)]
    [(cond (or (not has?) (c/nichts? value)) code
           (c/eq-any? value ["*" "\"*\""]) (if (c/hgl? eTag) code ec)
           (not (c/eq-any? eTag
                           (map #(c/strim %)
                                (c/split value ",")))) ec :else code) body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-unmod-since?

  "Condition fails if the last-modified
  timestamp is greater than the given date."
  [method lastMod code body conds]

  (let [{:keys [value has?]} (:if-unmod-since conds)
        value (c/strim value)
        rc (cond-err-code method)
        t (DateUtil/parseHttpDate value -1)]
    [(if (and (c/spos? t)
              (c/spos? lastMod))
       (if (< lastMod t) code rc) code) body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-mod-since?

  "Condition fails if the last-modified
  time-stamp is less than the given date."
  [method lastMod code body conds]

  (let [{:keys [value has?]} (:if-mod-since conds)
        value (c/strim value)
        rc (cond-err-code method)
        t (DateUtil/parseHttpDate value -1)]
    [(if (and (c/spos? t)
              (c/spos? lastMod))
       (if (> lastMod t) code rc) code) body]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- if-range?

  "Sort out the range if any, then apply
  the if-range condition."
  [eTag lastMod code cType body conds]

  (let [ec (n/scode* REQUESTED_RANGE_NOT_SATISFIABLE)
        pc (n/scode* PARTIAL_CONTENT)
        ^String hd (get-in conds [:if-range :value])
        {:keys [value has?]} (:range conds)
        value (c/strim value)
        g (nr/http-ranges<> (if has? value nil) cType body)]
    (cond (or (not has?)
              (c/nichts? value)) [code body]
          (nil? g) [ec nil]
          (c/nichts? hd) [pc g]
          (and (cs/ends-with? hd "GMT")
               (cs/index-of hd \,)
               (cs/index-of hd \:))
          (let [t (DateUtil/parseHttpDate hd -1)]
            (if (and (c/spos? lastMod)
                     (c/spos? t)
                     (> lastMod t)) [code body] [pc g]))
          (not= hd eTag) [code body] :else [pc g])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- encode-cookies

  [cookies]

  (c/preduce<vec>
    #(let [^Cookie c
           (condp instance? %2
             Cookie %2
             HttpCookie (n/netty-cookie<> %2)
             (u/throw-BadArg "Bad cookie"))]
       (conj! %1 (.encode ServerCookieEncoder/STRICT c))) cookies))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- replyer<>

  [res sessionObj]

  (l/debug "replyer called with res = %s.\nsessionObj = %s." res sessionObj)
  (letfn
    [(zmap-headers [msg headers]
       (zipmap (map #(let [[k v] %1] k) headers)
               (map #(let [[k v] %1]
                       {:has? (cc/msg-header? msg v)
                        :value (cc/msg-header msg v)}) headers)))
     (converge [body cs]
       (cond (bytes? body)
             body
             (string? body)
             (i/x->bytes body cs)
             (c/is? XData body)
             (converge (.content ^XData body) cs) :else body))]
    (let [{:keys [body status request charset] :as res}
          (ss/downstream res sessionObj)
          {:keys [headers last-mod etag cookies] :as cfg} res
          {:keys [keep-alive?
                  ^Channel socket request-method]} request
          ;^Channel ch (:socket req)
          ;method (:method req)
          cs (u/charset?? charset)
          ;code (:status res)
          body0 (converge body cs)
          _ (l/debug "resp: body0===> %s." body0)
          conds (zmap-headers request conds-hds)
          rhds (zmap-headers res resp-hds)
          cType (get-in rhds [:ctype :value])
          [status body]
          (if (code-ok? status)
            (if-range? etag last-mod status cType body0 conds)
            [status body0])
          [status body]
          (if (code-ok? status)
            (if-match? request-method etag status body conds)
            [status body])
          [status body]
          (if (code-ok? status)
            (if-none-match? request-method etag status body conds)
            [status body])
          [status body]
          (if (code-ok? status)
            (if-mod-since? request-method last-mod status body conds)
            [status body])
          [status body]
          (if (code-ok? status)
            (if-unmod-since? request-method last-mod status body conds)
            [status body])
          [status body]
          (if (and (= :head request-method)
                   (code-ok? status))
            [status nil] [status body])
          rangeRef
          (if-some
            [ro (c/cast? HttpRangesObj body)] (c/finz ro))
          [body clen]
          (cond (c/is? InputStream body)
                [(HttpChunkedInput.
                   (ChunkedStream. ^InputStream body)) -1]
                rangeRef
                [(HttpChunkedInput. ^ChunkedInput body)
                 (.length ^ChunkedInput body)]
                (bytes? body)
                [body (count body)]
                (c/is? File body)
                [(HttpChunkedInput.
                   (ChunkedNioFile. ^File body))
                 (.length ^File body)]
                (nil? body)
                [nil 0]
                :else
                (u/throw-IOE "Unsupported result content"))
          _ (l/debug "body = %s." body)
          [rsp body]
          (cond (bytes? body)
                [(n/http-reply<+> status body (.alloc socket)) nil]
                (nil? body)
                [(n/http-reply<+> status) nil]
                (and (zero? clen)
                     (nil? body))
                [(n/http-reply<+> status) nil]
                :else
                [(n/http-reply<> status) body])
          hds (.set (.headers ^HttpMessage rsp)
                    (h1/std->headers headers))]
      (cond (== status 416)
            (nr/fmt-error hds body0)
            rangeRef
            (nr/fmt-success hds rangeRef))
      (l/debug "response = %s." rsp)
      (l/debug "body-len = %s." clen)
      (->> (boolean (and body (not (c/is? FullHttpResponse rsp))))
         (HttpUtil/setTransferEncodingChunked rsp ))
      (if-not (c/sneg? clen)
        (HttpUtil/setContentLength rsp clen))
      (if (c/sneg? clen)
        (HttpUtil/setKeepAlive rsp false)
        (HttpUtil/setKeepAlive rsp keep-alive?))
      (if (or (nil? body)
              (and (zero? clen)(nil? body)))
        (.remove hds (n/h1hdr* CONTENT_TYPE)))
      (doseq [s (encode-cookies (vals cookies))]
        (l/debug "resp: setting cookie: %s." s)
        (.add hds (n/h1hdr* SET_COOKIE) s))
      (if (and (c/spos? last-mod)
               (not (get-in rhds [:last-mod :has?])))
        (.set hds
              (n/h1hdr* LAST_MODIFIED)
              (DateUtil/formatHttpDate ^long last-mod)))
      (if (and (c/hgl? etag)
               (not (get-in rhds [:etag :has?])))
        (.set hds (n/h1hdr* ETAG) etag))
      (let [c? (HttpUtil/isKeepAlive rsp)
            cf (if (nil? body)
                 (do (l/debug "reply has no chunked body, write and flush %s." rsp)
                     (.writeAndFlush socket rsp))
                 (do (l/debug "reply has chunked body, write and flush %s." rsp)
                     (.write socket rsp)
                     (l/debug "reply body, write and flush body: %s." body)
                     (.writeAndFlush socket body)))]
        (l/debug "resp replied, keep-alive? = %s." c?)
        (n/cf-close cf c?)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

