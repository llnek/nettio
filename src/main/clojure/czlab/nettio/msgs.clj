;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
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
            [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
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
            HttpPostRequestDecoder
            HttpPostMultipartRequestDecoder
            InterfaceHttpPostRequestDecoder
            HttpPostRequestDecoder$EndOfDataDecoderException]
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
           [czlab.basal XData]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fmt-wsmsg "" [m]
  (merge m {:route {:status? true}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- add-content "" [whole ^HttpContent c isLast?]
  (let [{:keys [impl]} (nc/deref-msg whole)]
    (cond
      (c/is? Attribute impl)
      (.addContent ^Attribute impl
                   (.. c content retain) isLast?)
      (c/is? InterfaceHttpPostRequestDecoder impl)
      (.offer ^InterfaceHttpPostRequestDecoder impl c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- append-content "" [whole ^HttpContent c isLast?]
  (let [{:keys [body impl
                fac owner]} (nc/deref-msg whole)]
    (add-content whole c isLast?)
    (if isLast?
      (cond
        (c/is? Attribute impl)
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
        (c/is? InterfaceHttpPostRequestDecoder impl)
        (do (.reset ^XData body (nc/end-msg-content whole))
            (try (.destroy
                   ^InterfaceHttpPostRequestDecoder impl)
                 (catch Throwable _ (l/exception  _ ))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fire-msg "" [ctx msg]
  (when (some? msg)
    (l/debug "about to firemsg ===> %s." msg)
    (some->> (or (some-> (nc/gist-h1-msg ctx msg)
                         nc/netty-msg<>) msg)
             (.fireChannelRead ^ChannelHandlerContext ctx))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rename to self ,trick code to not delete the file
(defn- get-http-data "" [^HttpData d]
  (XData. (if (.isInMemory d) (.get d) (c/doto->> (.getFile d) (.renameTo d)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- safe-has-next? "" [^InterfaceHttpPostRequestDecoder deco]
  (try (.hasNext deco)
       (catch HttpPostRequestDecoder$EndOfDataDecoderException _ false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- parse-post "" [^InterfaceHttpPostRequestDecoder deco]
  (l/debug "about to parse a form-post, decoder= %s." deco)
  (loop [out (cu/form-items<>)]
    (if-not (safe-has-next? deco)
      (XData. out)
      (let [n (.next deco)
            z (cond
                (c/is? FileUpload n)
                (let [u (c/cast? FileUpload n)]
                  (cu/file-item<> false
                                  (.getContentType u)
                                  nil
                                  (.getName u)
                                  (.getFilename u)
                                  (get-http-data u)))
                (c/is? Attribute n)
                (let [a (c/cast? Attribute n)]
                  (cu/file-item<> true
                                  "" nil
                                  (.getName a) ""
                                  (get-http-data a)))
                :else (l/error "Unknown post content %s." n))]
        (recur
          (if z (cu/add-item out z) out))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- chk-form-post
  "Detects if a form post?" [req]
  (let
    [ct (->> HttpHeaderNames/CONTENT_TYPE
             (nc/get-header req) str (s/lcase))
     method (nc/get-method req)]
    (cond
      (s/embeds? ct "application/x-www-form-urlencoded")
      (if (s/eq-any? method ["POST" "PUT"]) :post :url)
      (and (s/embeds? ct "multipart/form-data")
           (s/eq-any? method ["POST" "PUT"])) :multipart)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- prepare-body "" [^HttpDataFactory df ^HttpRequest msg]
  (let [rc (chk-form-post msg)
        cs (nc/get-msg-charset msg)]
    (if (or (= rc :post)
            (= rc :multipart))
      (do (l/debug "got form-post: %s" rc)
          (HttpPostRequestDecoder. df msg cs))
      (.createAttribute df msg body-attr-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- whole-request<+> "" [^HttpDataFactory fac ^HttpRequest owner]
  (let [impl (prepare-body fac owner)
        _ (assert (some? impl))
        target {:impl impl :fac fac
                :owner owner :body (XData.)}]
    (reify HttpRequest
      (getMethod [me] (.method me))
      (getUri [me] (.uri me))
      (method [me]
        (c/if-some+ [x (-> (.headers owner)
                           (.get "X-HTTP-Method-Override"))]
          (HttpMethod/valueOf x)
          (.method owner)))
      (setProtocolVersion [_ _] (u/throw-UOE ""))
      (setMethod [_ _] (u/throw-UOE ""))
      (setUri [_ _] (u/throw-UOE ""))
      (uri [_] (.uri owner))
      (getDecoderResult [me] (.decoderResult me))
      (decoderResult [_] (.decoderResult owner))
      (setDecoderResult [_ _] (u/throw-UOE ""))
      (getProtocolVersion [me] (.protocolVersion me))
      (headers [_] (.headers owner))
      (protocolVersion [_] (.protocolVersion owner))
      nc/WholeMsgAPI
      (append-msg-content [me c last?]
        (append-content me c last?))
      (add-msg-content [me c last?]
        (add-content me c last?))
      (deref-msg [_] target)
      (end-msg-content [me]
        (try (cond (c/is? InterfaceHttpPostRequestDecoder impl)
                   (parse-post impl)
                   (c/is? Attribute impl)
                   (get-http-data impl))
             (catch Throwable _
               (l/exception _)
               (throw _)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- whole-response<+> [^HttpDataFactory fac ^HttpResponse resp]
  (let [owner (nc/fake-request<>)
        impl (prepare-body fac owner)
        target {:impl impl :fac fac
                :owner owner :body (XData.)}]
    (reify HttpResponse
      (setProtocolVersion [_ _] (u/throw-UOE ""))
      (getDecoderResult [me] (.decoderResult me))
      (decoderResult [_] (.decoderResult owner))
      (setDecoderResult [_ _] (u/throw-UOE ""))
      (getProtocolVersion [me] (.protocolVersion me))
      (headers [_] (.headers resp))
      (protocolVersion [_] (.protocolVersion owner))
      (getStatus [me] (.status me))
      (setStatus [_ _] (u/throw-UOE ""))
      (status [_] (.status resp))
      nc/WholeMsgAPI
      (append-msg-content [me c last?]
        (append-content me c last?))
      (add-msg-content [me c last?]
        (add-content me c last?))
      (deref-msg [_] target)
      (end-msg-content [me]
        (get-http-data impl)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h11msg<> [ctx msg]
  (let [fac (nc/get-akey ctx nc/dfac-key)]
    (if (c/is? HttpRequest msg)
      (whole-request<+> fac msg) (whole-response<+> fac msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-h1-chunk [ctx part pipelining?]
  (let
    [last? (c/is? LastHttpContent part)
     msg (nc/get-akey ctx h1pipe-M-key)]
    (if-not last?
      (l/debug "received chunk for msg-part %s." part))
    (try
      (if-not (nc/decoder-success? part)
        (if (c/is? HttpRequest msg)
          (nc/reply-status ctx
                           (nc/scode HttpResponseStatus/BAD_REQUEST))
          (nc/close-ch ctx))
        (nc/append-msg-content msg part last?))
      (finally
        (nc/ref-del part)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-last-h1-chunk [ctx part pipelining?]
  (l/debug "received last-chunk for msg-part %s." part)
  (read-h1-chunk ctx part pipelining?)
  (let
    [q (nc/get-akey ctx h1pipe-Q-key)
     msg (nc/get-akey ctx h1pipe-M-key)
     cur (nc/get-akey ctx h1pipe-C-key)]
    (nc/del-akey ctx h1pipe-M-key)
    (l/debug "got last chunk for msg %s." msg)
    (if pipelining?
      (cond (nil? cur)
            (do (nc/set-akey ctx h1pipe-C-key msg)
                (fire-msg ctx msg))
            :else
            (do (.add ^List q msg)
                (l/debug "H1 Pipelining is being used! The Marmushka!!!")))
      ;else
      (fire-msg ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-h1-message [ctx msg pipelining?]
  ;;no need to release msg -> request or response
  (let [{:keys [max-in-memory
                max-content-size]} (nc/get-akey ctx nc/chcfg-key)]
    (l/debug "reading message: %s." msg)
    (cond
      (not (nc/decoder-success? msg))
      (if (c/is? HttpRequest msg)
        (nc/reply-status ctx
                         (nc/scode HttpResponseStatus/BAD_REQUEST))
        (nc/close-ch ctx))
      (not (nc/maybe-handle-100? ctx msg max-content-size))
      nil
      :else
      (let [wo (h11msg<> ctx msg)]
        (nc/set-akey ctx h1pipe-M-key wo)
        (cond (c/is? LastHttpContent msg)
              (do (read-last-h1-chunk ctx msg pipelining?)
                  (l/debug "last-content same as message..."))
              (c/is? HttpContent msg)
              (do (read-h1-chunk ctx msg pipelining?)
                  (l/debug "content same as message....")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn agg-h1-read "" [ctx msg pipelining?]
  (cond (c/is? HttpMessage msg)
        (read-h1-message ctx msg pipelining?)
        (c/is? LastHttpContent msg)
        (read-last-h1-chunk ctx msg pipelining?)
        (c/is? HttpContent msg)
        (read-h1-chunk ctx msg pipelining?)
        :else
        (fire-msg ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- dequeue-req [^ChannelHandlerContext ctx msg pipe?]
  (when (and (or (c/is? FullHttpResponse msg)
                 (c/is? LastHttpContent msg)) pipe?)
    (let [^List q (nc/get-akey ctx h1pipe-Q-key)
          cur (nc/get-akey ctx h1pipe-C-key)]
      (if (nil? cur) (u/throw-ISE "response but no request, msg=%s." msg))
      (if (nil? q) (u/throw-ISE "request queue is null."))
      (let [c (if-not (.isEmpty q) (.remove q 0))]
        (nc/set-akey ctx h1pipe-C-key c) (fire-msg ctx c)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1req-aggregator<>
  "A handler which aggregates chunks into a full request.  For http-header-expect,
  returns 100-continue if the payload size is below limit.  Also optionally handle
  http 1.1 pipelining by default"
  {:tag ChannelHandler}

  ([] (h1req-aggregator<> true))
  ([pipelining?]
   (proxy [DuplexHandler][false]
     (onActive [ctx]
       (nc/set-akey* ctx
                     h1pipe-Q-key (ArrayList.)
                     h1pipe-M-key nil h1pipe-C-key nil))
     (readMsg [ctx msg]
       (agg-h1-read ctx msg pipelining?))
     (onWrite [ctx msg cp]
       (let [skip?
             (and (c/is? FullHttpResponse msg)
                  (= (.status ^FullHttpResponse msg)
                     HttpResponseStatus/CONTINUE))]
         (if-not skip?
           (dequeue-req ctx msg pipelining?))))
     (onInactive [ctx]
       (nc/del-akey* ctx h1pipe-Q-key h1pipe-M-key h1pipe-C-key)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- finito [^ChannelHandlerContext ctx sid]
  (let [^Map hh (nc/get-akey ctx h2msg-h-key)
        ^Map dd (nc/get-akey ctx h2msg-d-key)
        df (nc/get-akey ctx nc/dfac-key)
        [^HttpRequest fake ^Attribute attr]
        (some-> dd (.get sid))
        hds (some-> hh (.get sid))]
    (if fake
      (.removeHttpDataFromClean ^HttpDataFactory df fake attr))
    (some-> dd (.remove sid))
    (some-> hh (.remove sid))
    (let [x (XData. (if attr
                      (if (.isInMemory attr)
                        (.get attr)
                        (c/doto->> (.getFile attr)
                                   (.renameTo attr)))))
          msg (assoc (NettyH2Msg.) :headers hds :body x)]
      (some-> attr .release)
      (l/debug "finito: fire msg upstream: %s." msg)
      (.fireChannelRead ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-h2-frameEx [ctx sid ^ByteBuf data end?]
  (let [^Map m (nc/get-akey ctx h2msg-d-key)
        [_ attr] (.get m sid)]
    (.addContent ^Attribute attr (.retain data) end?)
    (if end? (finito ctx sid))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-h2-frame [^ChannelHandlerContext ctx sid]
  (let [df (nc/get-akey ctx nc/dfac-key)
        ^Map m (or (nc/get-akey ctx h2msg-d-key)
                   (nc/set-akey ctx h2msg-d-key (HashMap.)))
        [fake attr] (.get m sid)]
    (if (nil? fake)
      (let [r (nc/fake-request<>)]
        (.put m sid [r (.createAttribute ^HttpDataFactory df r body-attr-id)])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h20-aggregator<>
  "A handler which aggregates frames into a full message."
  {:tag Http2FrameListener}
  ([] (h20-aggregator<> nil))
  ([^ChannelPromise pm]
   (proxy [Http2FrameAdapter][]
     (onSettingsRead [ctx ss]
       (c/try! (some-> pm .setSuccess)))
     (onDataRead [ctx sid data pad end?]
       (l/debug "rec'ved data: sid#%s, end?=%s." sid end?)
       (c/do-with [b (+ pad (.readableBytes ^ByteBuf data))]
         (read-h2-frame ctx sid)
         (read-h2-frameEx ctx sid data end?)))
     (onHeadersRead
       ([ctx sid hds pad end?]
        (l/debug "rec'ved headers: sid#%s, end?=%s." sid end?)
        (let [^Map m (or (nc/get-akey ctx h2msg-h-key)
                         (nc/set-akey ctx h2msg-h-key (HashMap.)))]
          (.put m sid hds)
          (if end? (finito ctx sid))))
       ([ctx sid hds
         dep wgt ex? pad end?]
        (.onHeadersRead ^Http2FrameAdapter this ctx sid hds pad end?))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-ws-frame-ex [^ChannelHandlerContext ctx
                         ^ContinuationWebSocketFrame msg]
  (let [^HttpDataFactory df (nc/get-akey ctx nc/dfac-key)
        last? (.isFinalFragment msg)
        {:keys [^Attribute attr fake] :as rc}
        (nc/get-akey ctx wsock-res-key)]
    (.addContent attr (.. msg content retain) last?)
    (nc/ref-del msg)
    (when last?
      (.removeHttpDataFromClean df fake attr)
      (let [x (XData.
                (if (.isInMemory attr)
                  (.get attr)
                  (c/doto->> (.getFile attr) (.renameTo attr))))]
        (.release attr)
        (.fireChannelRead ctx
                          (merge (NettyWsockMsg.)
                                 (dissoc (fmt-wsmsg (assoc rc :body x)) :attr :fake)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-ws-frame [^ChannelHandlerContext ctx ^WebSocketFrame msg]
  (let [rc {:charset (u/charset?? "utf-8")
            :isText? (c/is? TextWebSocketFrame msg)}]
    (cond
      (c/is? PongWebSocketFrame msg)
      (.fireChannelRead ctx
                        (merge (NettyWsockMsg.)
                               (fmt-wsmsg (assoc rc :pong? true))))
      (.isFinalFragment msg)
      (.fireChannelRead ctx
                        (merge (NettyWsockMsg.)
                               (fmt-wsmsg (assoc rc
                                                 :body (XData.
                                                         (nc/bbuf->bytes (.content msg)))))))
      :else
      (let [df (nc/get-akey ctx nc/dfac-key)
            req (nc/fake-request<>)
            a (.createAttribute ^HttpDataFactory
                                df req body-attr-id)]
        (.addContent ^Attribute
                     a (.. msg content retain) false)
        (->> (assoc rc :attr a :fake req)
             (nc/set-akey ctx wsock-res-key))))
    (nc/ref-del msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def
  ^{:tag ChannelHandler
    :doc "A handler that aggregates frames into a full message."}
  wsock-aggregator<>
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg]
      (cond
        (c/is? ContinuationWebSocketFrame msg)
        (read-ws-frame-ex ctx msg)
        (or (c/is? TextWebSocketFrame msg)
            (c/is? PongWebSocketFrame msg)
            (c/is? BinaryWebSocketFrame msg))
        (read-ws-frame ctx msg)
        :else
        (.fireChannelRead ^ChannelHandlerContext ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF




