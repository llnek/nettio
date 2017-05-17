;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.aggh11

  (:require [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.nettio.wmsg11 :as nw]
            [czlab.nettio.core :as nc]
            [czlab.convoy.upload :as cu]
            [czlab.convoy.core :as cc]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

  (:import [io.netty.util AttributeKey ReferenceCountUtil]
           [czlab.nettio DuplexHandler H1DataFactory]
           [io.netty.handler.codec.http.multipart
            AbstractDiskHttpData
            HttpDataFactory
            HttpData
            FileUpload
            Attribute
            HttpPostRequestDecoder]
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
            DefaultHttpRequest
            HttpRequest
            HttpResponseStatus
            HttpHeaders]
           [io.netty.handler.codec
            DecoderResult
            DecoderResultProvider]
           [java.nio.charset Charset]
           [java.util List ArrayList]
           [java.io OutputStream]
           [czlab.jasal XData]
           [io.netty.buffer
            Unpooled
            ByteBuf
            ByteBufHolder
            ByteBufAllocator]
           [io.netty.channel
            ChannelPipeline
            ChannelHandler
            Channel
            ChannelHandlerContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
(defonce ^:private ^AttributeKey h1pipe-Q-key (nc/akey<> "h1pipe-q"))
(defonce ^:private ^AttributeKey h1pipe-C-key (nc/akey<> "h1pipe-c"))
(defonce ^:private ^AttributeKey h1pipe-M-key (nc/akey<> "h1pipe-m"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- fireMsg "" [ctx msg]
  (some->>
    (or (some-> (nc/gistH1Msg ctx msg) nc/nettyMsg<>) msg)
    (.fireChannelRead ^ChannelHandlerContext ctx)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rename to self ,trick code to not delete the file
(defn- getHttpData "" [^HttpData d]
  (if (.isInMemory d)
    (.get d)
    (c/doto->> (.getFile d) (.renameTo d ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- parsePost
  "" [^HttpPostRequestDecoder deco]
  (reduce
    #(when-some
       [z (cond
            (c/ist? FileUpload %2)
            (let [u (c/cast? FileUpload %2)
                  c (getHttpData u)]
              (cu/fileItem<> false
                             (.getContentType u)
                             nil
                             (.getName u)
                             (.getFilename u) c))
            (c/ist? Attribute %2)
            (let [a (c/cast? Attribute %2)
                  c (getHttpData a)]
              (cu/fileItem<> true "" nil (.getName a) "" c)))]
       (if-some [d (c/cast? AbstractDiskHttpData %2)]
         (if-not (.isInMemory d)
           (.removeHttpDataFromClean deco ^HttpData %2)))
       ;;no need to release since we will call destroy on the decoder
       ;;(.release x)
       (cu/add-item %1 z))
    (cu/formItems<>)
    (.getBodyHttpDatas deco)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- handleExpect? "" [ctx msg]
  (let [{:keys
         [maxContentSize]}
        (nc/getAKey ctx nc/chcfg-key)]
    (nc/maybeHandle100? ctx msg maxContentSize)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h11Msg<> "" [ctx msg]
  (if (c/ist? HttpRequest msg)
    (nw/wholeRequest (nc/getAKey ctx nc/dfac-key) msg)
    (nw/wholeResponse (nc/getAKey ctx nc/dfac-key) msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readChunk
  "" [ctx part pipelining?]
  ;;(log/debug "received chunk for msg")
  (let
    [last? (c/ist? LastHttpContent part)
     msg (nc/getAKey ctx h1pipe-M-key)]
    (try
      (if-not (nc/decoderSuccess? part)
        (if (c/ist? HttpRequest msg)
          (nc/replyStatus ctx
                          (.code HttpResponseStatus/BAD_REQUEST))
          (.close ^ChannelHandlerContext ctx))
        (append-msg-content msg part last?))
      (finally
        (ReferenceCountUtil/release part)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readLastChunk
  "" [ctx part pipelining?]

  (readChunk ctx part pipelining?)
  (let
    [q (nc/getAKey ctx h1pipe-Q-key)
     msg (nc/getAKey ctx h1pipe-M-key)
     cur (nc/getAKey ctx h1pipe-C-key)]
    (log/debug "got last chunk for msg %s" msg)
    (nc/delAKey ctx h1pipe-M-key)
    (if pipelining?
      (cond
        (nil? cur)
        (do
          (nc/setAKey ctx h1pipe-C-key msg)
          (fireMsg ctx msg))
        :else
        (do (.add ^List q msg)))
      (fireMsg ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readMessage
  "" [ctx msg pipelining?]
  ;;no need to release msg -> request or response
  (let [{:keys [maxContentSize
                maxInMemory]} (nc/getAKey ctx nc/chcfg-key)]
    (log/debug "reading message: %s" msg)
    (cond
      (not (nc/decoderSuccess? msg))
      (if (c/ist? HttpRequest msg)
        (nc/replyStatus ctx
                        (.code HttpResponseStatus/BAD_REQUEST))
        (.close ^ChannelHandlerContext ctx))
      (not (nc/handleExpect? ctx msg))
      nil
      :else
      (let [wo (h11Msg<> ctx msg)]
        (nc/setAKey ctx h1pipe-M-key wo)
        (if (c/ist? LastHttpContent msg)
          (readLastChunk ctx msg pipelining?))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- aggRead
  "" [ctx msg pipelining?]

  (cond
    (c/ist? HttpMessage msg)
    (readMessage ctx msg pipelining?)
    (c/ist? LastHttpContent msg)
    (readLastChunk ctx msg pipelining?)
    (c/ist? HttpContent msg)
    (readChunk ctx msg pipelining?)
    :else
    (fireMsg ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- dequeueReq "" [^ChannelHandlerContext ctx msg pipe?]

  (when (and (or (c/ist? FullHttpResponse msg)
                 (c/ist? LastHttpContent msg))
             pipe?)
    (let [^List q (nc/getAKey ctx nc/h1pipe-Q-key)
          cur (nc/getAKey ctx nc/h1pipe-C-key)]
      (if (nil? cur)
        (c/trap! IllegalStateException
                 "response but no request"))
      (if (nil? q)
        (c/trap! IllegalStateException
                 "request queue is null"))
      (let [c (if-not (.isEmpty q)
                (.remove q 0))]
        (nc/setAKey ctx nc/h1pipe-C-key c)
        (fireMsg ctx c)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1reqAggregator<>
  "A handler which aggregates chunks into a full request.  For http-header-expect,
  returns 100-continue if the payload size is below limit.  Also optionally handle
  http 1.1 pipelining by default"
  {:tag ChannelHandler}

  ([] (h1reqAggregator<> true))
  ([pipelining?]
   (proxy [DuplexHandler][]
     (onActive [ctx]
       (setAKey* ctx
                 h1pipe-Q-key (ArrayList.)
                 h1pipe-M-key nil
                 h1pipe-C-key nil))
     (onInactive [ctx]
       (delAKey* ctx
                 h1pipe-Q-key
                 h1pipe-M-key h1pipe-C-key))
     (onRead [ctx msg]
       (aggRead ctx msg pipelining?))
     (onWrite [ctx msg cp]
       (let [skip?
             (and (c/ist? FullHttpResponse msg)
                  (= (.status ^FullHttpResponse msg)
                     HttpResponseStatus/CONTINUE))]
         (if-not skip?
           (dequeueReq ctx msg)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1resAggregator<>
  "A handler which aggregates chunks into a full response"
  ^ChannelHandler
  []
  (proxy [DuplexHandler][]
    (onRead [ctx msg] (aggRead ctx msg false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

