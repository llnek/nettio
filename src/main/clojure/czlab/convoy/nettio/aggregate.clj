;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.convoy.nettio.aggregate

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.convoy.nettio.core]
        [czlab.convoy.net.upload]
        [czlab.basal.str]
        [czlab.basal.io]
        [czlab.basal.core])

  (:import [io.netty.util AttributeKey ReferenceCountUtil]
           [czlab.convoy.net ULFormItems ULFileItem]
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
           [czlab.convoy.nettio
            WholeMessage
            WholeRequest
            H1ReqAggregator
            H1ResAggregator
            H1DataFactory
            H1Aggregator
            WholeResponse]
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
(defonce ^:private ^AttributeKey h1pipe-Q-key (akey<> "h1pipe-q"))
(defonce ^:private ^AttributeKey h1pipe-C-key (akey<> "h1pipe-c"))
(defonce ^:private ^AttributeKey h1pipe-M-key (akey<> "h1pipe-m"))
(def ^:private ^String body-attr-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private fireMsg
  "" [ctx msg] `(some->>
                  ~msg
                  (.fireChannelRead ~(with-meta ctx
                                                {:tag 'ChannelHandlerContext}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- getHttpData "" [^HttpData d]

  (if (.isInMemory d)
    (.get d)
    (doto->> (.getFile d) (.renameTo d ))))
  ;; trick code to not delete the file

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- parsePost
  "" [^HttpPostRequestDecoder deco]

  (let [bag (ULFormItems.)]
    (doseq [^HttpData x (.getBodyHttpDatas deco)]
      (when-some
        [z (cond
             (ist? FileUpload x)
             (let [u (cast? FileUpload x)
                   c (getHttpData u)]
               (fileItem<> false
                           (.getContentType u)
                           nil
                           (.getName u)
                           (.getFilename u) c))
             (ist? Attribute x)
             (let [a (cast? Attribute x)
                   c (getHttpData a)]
               (fileItem<> true "" nil (.getName a) "" c)))]
        (if-some [d (cast? AbstractDiskHttpData x)]
          (if-not (.isInMemory d)
            (.removeHttpDataFromClean deco x)))
        ;;no need to release since we will call destroy on the decoder
        ;;(.release x)
        (.add bag z)))
    bag))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h11res<>
  "" ^WholeResponse [ctx rsp]

  (let [gs (scanGist ctx rsp)]
    (doto
      (proxy [WholeResponse][rsp]
        (prepareBody [df msg]
          (. ^HttpDataFactory df
             createAttribute ^HttpRequest msg body-attr-id))
        (endContent [_] (getHttpData _))
        (gist [] gs))
      (. init ^HttpDataFactory (getAKey ctx dfac-key)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h11req<>
  "" ^WholeRequest [ctx req]

  (let [gs (scanGist ctx req)]
    (doto
      (proxy [WholeRequest][req]
        (prepareBody [df msg]
          (if (-> (chkFormPost msg)
                  (eqAny? ["multipart" "post"]))
            (HttpPostRequestDecoder.
              ^HttpDataFactory df ^HttpRequest msg (getMsgCharset msg))
            (. ^HttpDataFactory df
               createAttribute ^HttpRequest msg body-attr-id)))
        (endContent [c]
          (cond
            (ist? HttpPostRequestDecoder c)
            (parsePost c)
            (ist? Attribute c)
            (getHttpData c)))
        (gist [] gs))
      (. init ^HttpDataFactory (getAKey ctx dfac-key)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- handleExpect? "" [ctx msg]
  (let [{:keys
         [maxContentSize]}
        (getAKey ctx chcfg-key)] (maybeHandle100? ctx msg maxContentSize)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h11Msg<> "" [ctx msg]
  (if (ist? HttpRequest msg) (h11req<> ctx msg) (h11res<> ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readChunk
  "" [ctx part pipelining?]
  ;;(log/debug "received chunk for msg")
  (let
    [last? (ist? LastHttpContent part)
     msg (getAKey ctx h1pipe-M-key)]
    (try
      (if-not (decoderSuccess? part)
        (if (ist? HttpRequest msg)
          (replyStatus ctx
                       (.code HttpResponseStatus/BAD_REQUEST))
          (. ^ChannelHandlerContext ctx close))
        (. ^WholeMessage msg appendContent ^HttpContent part last?))
      (finally
        (ReferenceCountUtil/release part)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readLastChunk
  "" [ctx part pipelining?]

  (readChunk ctx part pipelining?)
  (let
    [^List q (getAKey ctx h1pipe-Q-key)
     msg (getAKey ctx h1pipe-M-key)
     cur (getAKey ctx h1pipe-C-key)]
    (log/debug "got last chunk for msg %s" msg)
    (delAKey ctx h1pipe-M-key)
    (if pipelining?
      (cond
        (nil? cur)
        (do
          (setAKey ctx h1pipe-C-key msg)
          (fireMsg ctx msg))
        :else
        (do
          (.add q msg)))
      (fireMsg ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readMessage
  "" [ctx msg pipelining?]
  ;;no need to release msg -> request or response
  (let [{:keys [maxContentSize
                maxInMemory]} (getAKey ctx chcfg-key)]
    (log/debug "reading message: %s" msg)
    (cond
      (not (decoderSuccess? msg))
      (if (ist? HttpRequest msg)
        (replyStatus ctx
                     (.code HttpResponseStatus/BAD_REQUEST))
        (. ^ChannelHandlerContext ctx close))
      (not (handleExpect? ctx msg))
      nil
      :else
      (let [wo (h11Msg<> ctx msg)]
        (setAKey ctx h1pipe-M-key wo)
        (if (ist? LastHttpContent msg)
          (readLastChunk ctx msg pipelining?))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- aggRead
  "" [ctx msg pipelining?]

  (cond
    (ist? HttpMessage msg)
    (readMessage ctx msg pipelining?)
    (ist? LastHttpContent msg)
    (readLastChunk ctx msg pipelining?)
    (ist? HttpContent msg)
    (readChunk ctx msg pipelining?)
    :else
    (fireMsg ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1reqAggregator<>
  "A handler which aggregates chunks into a full request.  For http-header-expect,
  returns 100-continue if the payload size is below limit.  Also optionally handle
  http 1.1 pipelining by default"
  {:tag ChannelHandler}

  ([] (h1reqAggregator<> true))
  ([pipelining?]
   (proxy [H1ReqAggregator][]
     (onChannelActive [ctx]
       (setAKey ctx h1pipe-Q-key (ArrayList.))
       (setAKey ctx h1pipe-M-key nil)
       (setAKey ctx h1pipe-C-key nil))
     (onChannelInactive [ctx]
       (delAKey ctx h1pipe-Q-key)
       (delAKey ctx h1pipe-M-key)
       (delAKey ctx h1pipe-C-key))
     (channelRead [ctx msg]
       (aggRead ctx msg pipelining?))
     (dequeue [ctx msg]
       (when (and (or (ist? FullHttpResponse msg)
                      (ist? LastHttpContent msg))
                  pipelining?)
         (let [^List q (getAKey ctx h1pipe-Q-key)
               cur (getAKey ctx h1pipe-C-key)]
           (if (nil? cur)
             (trap! IllegalStateException
                    "response but no request"))
           (if (nil? q)
             (trap! IllegalStateException
                    "request queue is null"))
           (let
             [c (if-not (.isEmpty q)
                  (.remove q 0))]
             (setAKey ctx h1pipe-C-key c)
             (fireMsg ctx c))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1resAggregator<>
  "A handler which aggregates chunks into a full response"
  ^ChannelHandler
  []
  (proxy [H1ResAggregator][]
    (channelRead [ctx msg] (aggRead ctx msg false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

