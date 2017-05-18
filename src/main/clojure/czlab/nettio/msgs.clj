;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.msgs

  (:require [czlab.convoy.upload :as cu]
            [czlab.convoy.core :as cc]
            [czlab.nettio.core :as nc]
            [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

  (:import [io.netty.util AttributeKey ReferenceCountUtil]
           [czlab.nettio.core NettyH2Msg NettyWsockMsg]
           [czlab.nettio DuplexHandler H1DataFactory]
           [io.netty.handler.codec.http.websocketx
            BinaryWebSocketFrame
            TextWebSocketFrame
            CloseWebSocketFrame
            WebSocketFrame
            PongWebSocketFrame
            PingWebSocketFrame
            ContinuationWebSocketFrame]
           [java.util Map HashMap List ArrayList]
           [io.netty.handler.codec DecoderResult]
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
           [io.netty.handler.codec.http2
            Http2FrameAdapter
            Http2Headers
            Http2FrameListener]
           [io.netty.handler.codec
            DecoderResult
            DecoderResultProvider]
           [java.nio.charset Charset]
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
            ChannelPromise
            Channel
            ChannelHandlerContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(defonce ^:private ^AttributeKey h1pipe-Q-key (nc/akey<> "h1pipe-q"))
(defonce ^:private ^AttributeKey h1pipe-C-key (nc/akey<> "h1pipe-c"))
(defonce ^:private ^AttributeKey h1pipe-M-key (nc/akey<> "h1pipe-m"))
(defonce ^:private ^AttributeKey h2msg-h-key (nc/akey<> "h2msg-hdrs"))
(defonce ^:private ^AttributeKey h2msg-d-key (nc/akey<> "h2msg-data"))
(defonce ^:private ^AttributeKey wsock-res-key (nc/akey<> "wsock-res"))
(def ^:private ^String body-attr-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- addContent "" [whole ^HttpContent c isLast?]
  (let [{:keys [impl]} (nc/deref-msg whole)]
    (cond
      (c/ist? Attribute impl)
      (.addContent ^Attribute impl
                   (.. c content retain) isLast?)
      (c/ist? HttpPostRequestDecoder impl)
      (.offer ^HttpPostRequestDecoder impl c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- appendContent "" [whole ^HttpContent c isLast?]
  (let [{:keys [body impl
                fac owner]} (nc/deref-msg whole)]
    (addContent whole c isLast?)
    (if isLast?
      (cond
        (c/ist? Attribute impl)
        (do
          (.removeHttpDataFromClean
            ^HttpDataFactory
            fac
            ^HttpRequest
            owner
            ^Attribute
            impl)
          (.reset ^XData body
                  (nc/end-msg-content whole))
          (.release ^Attribute impl))
        (c/ist? HttpPostRequestDecoder impl)
        (do
          (.reset ^XData body
                  (nc/end-msg-content whole))
          (.destroy ^HttpPostRequestDecoder impl))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- fireMsg "" [ctx msg]
  (when (some? msg)
    ;;(log/debug "about to firemsg ===> %s" msg)
    (some->> (or (some-> (nc/gistH1Msg ctx msg)
                         nc/nettyMsg<>) msg)
             (.fireChannelRead ^ChannelHandlerContext ctx))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rename to self ,trick code to not delete the file
(defn- getHttpData "" [^HttpData d]
  (if (.isInMemory d) (.get d) (c/doto->> (.getFile d) (.renameTo d))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- parsePost "" [^HttpPostRequestDecoder deco]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- prepareBody "" [^HttpDataFactory df ^HttpRequest msg]
  (if (-> (nc/chkFormPost msg)
          (s/eqAny? ["multipart" "post"]))
    (HttpPostRequestDecoder. df msg (nc/getMsgCharset msg))
    (.createAttribute df msg body-attr-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wholeRequest<> "" [^HttpDataFactory fac ^HttpRequest owner]
  (let [impl (prepareBody fac owner)
        _ (assert (some? impl))
        target {:impl impl :fac fac
                :owner owner :body (i/xdata<>)}]
    (reify HttpRequest
      (getMethod [me] (.method me))
      (getUri [me] (.uri me))
      (method [me]
        (c/if-some+ [x (-> (.headers owner)
                           (.get "X-HTTP-Method-Override"))]
                    (HttpMethod/valueOf x)
                    (.method owner)))
      (setProtocolVersion [_ _]
        (throw (UnsupportedOperationException.)))
      (setMethod [_ _]
        (throw (UnsupportedOperationException.)))
      (setUri [_ _]
        (throw (UnsupportedOperationException.)))
      (uri [_] (.uri owner))
      (getDecoderResult [me] (.decoderResult me))
      (decoderResult [_] (.decoderResult owner))
      (setDecoderResult [_ _]
        (throw (UnsupportedOperationException.)))
      (getProtocolVersion [me] (.protocolVersion me))
      (headers [_] (.headers owner))
      (protocolVersion [_] (.protocolVersion owner))
      nc/WholeMsgProto
      (append-msg-content [me c last?]
        (appendContent me c last?))
      (add-msg-content [me c last?]
        (addContent me c last?))
      (deref-msg [_] target)
      (end-msg-content [me]
        (cond
          (c/ist? HttpPostRequestDecoder impl)
          (parsePost impl)
          (c/ist? Attribute impl)
          (getHttpData impl))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wholeResponse<> "" [^HttpDataFactory fac ^HttpResponse resp]
  (let [owner (nc/fakeARequest<>)
        impl (prepareBody fac owner)
        _ (assert (some? impl))
        target {:impl impl :fac fac
                :owner owner :body (i/xdata<>)}]
    (reify HttpResponse
      (setProtocolVersion [_ _]
        (throw (UnsupportedOperationException.)))
      (getDecoderResult [me] (.decoderResult me))
      (decoderResult [_] (.decoderResult owner))
      (setDecoderResult [_ _]
        (throw (UnsupportedOperationException.)))
      (getProtocolVersion [me] (.protocolVersion me))
      (headers [_] (.headers resp))
      (protocolVersion [_] (.protocolVersion owner))
      (getStatus [me] (.status me))
      (setStatus [_ _]
        (throw (UnsupportedOperationException.)))
      (status [_] (.status resp))
      nc/WholeMsgProto
      (append-msg-content [me c last?]
        (appendContent me c last?))
      (add-msg-content [me c last?]
        (addContent me c last?))
      (deref-msg [_] target)
      (end-msg-content [me]
        (getHttpData impl)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- handleExpect? "" [ctx msg]
  (let [{:keys [maxContentSize]}
        (nc/getAKey ctx nc/chcfg-key)]
    (nc/maybeHandle100? ctx msg maxContentSize)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h11Msg<> "" [ctx msg]
  (if (c/ist? HttpRequest msg)
    (wholeRequest<> (nc/getAKey ctx nc/dfac-key) msg)
    (wholeResponse<> (nc/getAKey ctx nc/dfac-key) msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readH1Chunk "" [ctx part pipelining?]
  ;;(log/debug "received chunk for msg")
  (let
    [last? (c/ist? LastHttpContent part)
     msg (nc/getAKey ctx h1pipe-M-key)]
    (try
      (if-not (nc/decoderSuccess? part)
        (if (c/ist? HttpRequest msg)
          (nc/replyStatus ctx
                          (nc/scode HttpResponseStatus/BAD_REQUEST))
          (nc/closeCH ctx))
        (nc/append-msg-content msg part last?))
      (finally
        (nc/ref-del part)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readLastH1Chunk "" [ctx part pipelining?]

  (readH1Chunk ctx part pipelining?)
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
(defn- readH1Message "" [ctx msg pipelining?]
  ;;no need to release msg -> request or response
  (let [{:keys [maxContentSize
                maxInMemory]} (nc/getAKey ctx nc/chcfg-key)]
    (log/debug "reading message: %s" msg)
    (cond
      (not (nc/decoderSuccess? msg))
      (if (c/ist? HttpRequest msg)
        (nc/replyStatus ctx
                        (nc/scode HttpResponseStatus/BAD_REQUEST))
        (nc/closeCH ctx))
      (not (handleExpect? ctx msg))
      nil
      :else
      (let [wo (h11Msg<> ctx msg)]
        (nc/setAKey ctx h1pipe-M-key wo)
        (if (c/ist? LastHttpContent msg)
          (readLastH1Chunk ctx msg pipelining?))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- aggH1Read "" [ctx msg pipelining?]
  (cond
    (c/ist? HttpMessage msg)
    (readH1Message ctx msg pipelining?)
    (c/ist? LastHttpContent msg)
    (readLastH1Chunk ctx msg pipelining?)
    (c/ist? HttpContent msg)
    (readH1Chunk ctx msg pipelining?)
    :else
    (fireMsg ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- dequeueReq "" [^ChannelHandlerContext ctx msg pipe?]
  (when (and (or (c/ist? FullHttpResponse msg)
                 (c/ist? LastHttpContent msg))
             pipe?)
    (let [^List q (nc/getAKey ctx h1pipe-Q-key)
          cur (nc/getAKey ctx h1pipe-C-key)]
      (if (nil? cur)
        (c/throwISE "response but no request"))
      (if (nil? q)
        (c/throwISE "request queue is null"))
      (let [c (if-not (.isEmpty q)
                (.remove q 0))]
        (nc/setAKey ctx h1pipe-C-key c)
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
   (proxy [DuplexHandler][false]
     (onActive [ctx]
       (nc/setAKey* ctx
                 h1pipe-Q-key (ArrayList.)
                 h1pipe-M-key nil h1pipe-C-key nil))
     (onInactive [ctx]
       (nc/delAKey* ctx h1pipe-Q-key h1pipe-M-key h1pipe-C-key))
     (readMsg [ctx msg]
       (aggH1Read ctx msg pipelining?))
     (onWrite [ctx msg cp]
       (let [skip?
             (and (c/ist? FullHttpResponse msg)
                  (= (.status ^FullHttpResponse msg)
                     HttpResponseStatus/CONTINUE))]
         (if-not skip?
           (dequeueReq ctx msg pipelining?)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h1resAggregator<>
  "A handler which aggregates chunks into a full response"
  ^ChannelHandler
  []
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg] (aggH1Read ctx msg false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- finito "" [^ChannelHandlerContext ctx sid]
  (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
        ^Map hh (nc/getAKey ctx h2msg-h-key)
        ^Map dd (nc/getAKey ctx h2msg-d-key)
        [^HttpRequest fake
         ^Attribute attr]
        (some-> dd (.get sid))
        hds (some-> hh (.get sid))]
    (if fake
      (.removeHttpDataFromClean df fake attr))
    (some-> dd (.remove sid))
    (some-> hh (.remove sid))
    (let [x
          (if attr
            (i/xdata<> (if (.isInMemory attr)
                         (.get attr)
                         (c/doto->> (.getFile attr)
                                    (.renameTo attr))))
            (i/xdata<>))
          msg (c/object<> NettyH2Msg
                          {:headers hds :body x})]
      (some-> attr .release)
      (log/debug "finito: fire msg upstream: %s" msg)
      (.fireChannelRead ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readH2FrameEx "" [^ChannelHandlerContext ctx
                         sid
                         ^ByteBuf data end?]
  (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
        ^Map m (nc/getAKey ctx h2msg-d-key)
        [_ ^Attribute attr] (.get m sid)]
    (.addContent attr (.retain data) end?)
    (if end? (finito ctx sid))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readH2Frame "" [^ChannelHandlerContext ctx sid]
  (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
        ^Map m (or (nc/getAKey ctx h2msg-d-key)
                   (c/doto->> (HashMap.)
                              (nc/setAKey ctx h2msg-d-key)))
        [fake attr] (.get m sid)]
    (if (nil? fake)
      (let [r (nc/fakeARequest<>)]
        (.put m
              sid
              [r (.createAttribute df r body-attr-id)])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn h20Aggregator<>
  "A handler which aggregates frames into a full message"
  {:tag Http2FrameListener}
  ([] (h20Aggregator<> nil))
  ([^ChannelPromise pm]
   (proxy [Http2FrameAdapter][]
     (onSettingsRead [ctx ss]
       (c/trye! nil (some-> pm .setSuccess)))
     (onDataRead [ctx sid data pad end?]
       (log/debug "rec'ved data: sid#%s, end?=%s" sid end?)
       (c/do-with [b (+ pad (.readableBytes ^ByteBuf data))]
                  (readH2Frame ctx sid)
                  (readH2FrameEx ctx sid data end?)))
     (onHeadersRead
       ([ctx sid hds pad end?]
        (log/debug "rec'ved headers: sid#%s, end?=%s" sid end?)
        (let [^Map m (or (nc/getAKey ctx h2msg-h-key)
                         (c/doto->> (HashMap.)
                                    (nc/setAKey ctx h2msg-h-key)))]
          (.put m sid hds)
          (if end? (finito ctx sid))))
       ([ctx sid hds
         dep wgt ex? pad end?]
        (.onHeadersRead ^Http2FrameAdapter this ctx sid hds pad end?))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readWSFrameEx "" [^ChannelHandlerContext ctx
                         ^ContinuationWebSocketFrame msg]
  (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
        last? (.isFinalFragment msg)
        {:keys [^Attribute attr fake] :as rc}
        (nc/getAKey ctx wsock-res-key)]
    (.addContent attr (.. msg content retain) last?)
    (nc/ref-del msg)
    (when last?
      (.removeHttpDataFromClean df fake attr)
      (let [x (i/xdata<>
                (if (.isInMemory attr)
                  (.get attr)
                  (c/doto->> (.getFile attr) (.renameTo attr))))]
        (.release attr)
        (->> (assoc (dissoc rc :attr :fake) :body x)
             (c/object<> NettyWsockMsg )
             (.fireChannelRead ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readWSFrame "" [^ChannelHandlerContext ctx ^WebSocketFrame msg]
  (let [rc {:isText? (c/ist? TextWebSocketFrame msg)
            :charset (Charset/forName "utf-8")} ]
    (cond
      (c/ist? PongWebSocketFrame msg)
      (->> (c/object<> NettyWsockMsg
                       (assoc rc :pong? true))
           (.fireChannelRead ctx ))
      (.isFinalFragment msg)
      (->> (c/object<> NettyWsockMsg
                       (assoc rc
                              :body (i/xdata<>
                                      (nc/toByteArray
                                        (.content msg)))))
           (.fireChannelRead ctx ))
      :else
      (let [^HttpDataFactory df (nc/getAKey ctx nc/dfac-key)
            req (nc/fakeARequest<>)
            ^Attribute a (.createAttribute df
                                           req body-attr-id)
            rc (assoc rc :attr a)]
        (.addContent a (.. msg content retain) false)
        (nc/setAKey ctx wsock-res-key rc)))
    (nc/ref-del msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private wsock-agg
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg]
      (cond
        (c/ist? ContinuationWebSocketFrame msg)
        (readWSFrameEx ctx msg)
        (c/ist? TextWebSocketFrame msg)
        (readWSFrame ctx msg)
        (c/ist? BinaryWebSocketFrame msg)
        (readWSFrame ctx msg)
        (c/ist? PongWebSocketFrame msg)
        (readWSFrame ctx msg)
        :else
        (.fireChannelRead ^ChannelHandlerContext ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wsockAggregator<>
  "A handler that
  aggregates frames into a full message" ^ChannelHandler [] wsock-agg)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF




