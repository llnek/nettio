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

  (:require [czlab.niou.upload :as cu]
            [czlab.niou.core :as cc]
            [czlab.nettio.core :as nc]
            [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
            [czlab.basal.core :as c]
            [czlab.niou.routes :as cr])

  (:import [io.netty.util AttributeKey ReferenceCountUtil]
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
            DefaultHttpHeaders
            HttpRequest
            HttpResponseStatus
            HttpHeaders]
           [java.net
            InetAddress
            URL
            HttpCookie
            InetSocketAddress]
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
(defonce ^:private ^AttributeKey h1pipe-Q-key (nc/akey<> :h1pipe-q))
(defonce ^:private ^AttributeKey h1pipe-C-key (nc/akey<> :h1pipe-c))
(defonce ^:private ^AttributeKey h1pipe-M-key (nc/akey<> :h1pipe-m))
(defonce ^:private ^AttributeKey h2msg-h-key (nc/akey<> :h2msg-hdrs))
(defonce ^:private ^AttributeKey h2msg-d-key (nc/akey<> :h2msg-data))
(defonce ^:private ^AttributeKey wsock-res-key (nc/akey<> :wsock-res))
(def ^:private ^String body-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol DecoderAPI
  ""
  (parse-post [_] "")
  (safe-has-next? [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol MsgAPI
  ""
  (chk-form-post [req] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol CtxAPI
  ""
  (match-one-route [_ msg] "")
  (gist-h1-request [_ msg] "")
  (gist-h1-response [_ msg] "")
  (gist-h1-msg [_ msg] "")
  (fire-msg [_ msg] "")
  (append-msg-content [_ whole ct isLast?] "")
  (end-msg-content [_ whole] "")
  (prepare-body [_ msg] "")
  (h11msg<> [_ msg] "")
  (read-h1-chunk [_ part pipelining?] "")
  (read-last-h1-chunk [_ part pipelining?] "")
  (read-h1-message [_ msg pipelining?] "")
  (agg-h1-read [_ msg pipelining?] "")
  (dequeue-req [_ msg pipeline?] "")
  (finito [_ sid] "")
  (read-h2-frame [_ sid] "")
  (read-ws-frame [_ m] "")
  (read-ws-frame-ex [_ m] "")
  (read-h2-frameEx [_ sid data end?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fcr!!
  [ctx msg] (if msg (.fireChannelRead ^ChannelHandlerContext ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- dfac??
  ^HttpDataFactory [ctx] (nc/get-akey nc/dfac-key ctx))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rename to self ,trick code to not delete the file
(defn- get-http-data
  ([d] (get-http-data d nil))
  ([^HttpData d wrap?]
   (let [x (when d
             (if (.isInMemory d)
               (.get d)
               (c/doto->> (.getFile d)
                          (.renameTo d))))] (if wrap? (XData. x) x))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- gattr
  [^Attribute attr] (get-http-data attr true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsmsg<>
  [m] (c/object<> czlab.niou.core.WsockMsg
                  (merge m {:route {:status? true}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol DecoderAPI
  InterfaceHttpPostRequestDecoder
  (safe-has-next? [deco]
    (try (.hasNext deco)
         (catch HttpPostRequestDecoder$EndOfDataDecoderException _ false)))
  (parse-post [deco]
    (l/debug "about to parse a form-post, decoder= %s." deco)
    (loop [out (cu/form-items<>)]
      (if-not (safe-has-next? deco)
        (XData. out)
        (let [n (.next deco)
              z (condp instance? n
                  FileUpload (let [u (c/cast? FileUpload n)]
                               (cu/file-item<> false
                                               (.getContentType u)
                                               nil
                                               (.getName u)
                                               (.getFilename u)
                                               (get-http-data u true)))
                  Attribute (let [a (c/cast? Attribute n)]
                              (cu/file-item<> true
                                              "" nil
                                              (.getName a) ""
                                              (get-http-data a true)))
                  (c/do#nil (l/warn "Unknown post content %s." n)))]
          (recur (if z (cu/add-item out z) out)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol MsgAPI
  HttpMessage
  (chk-form-post [msg]
    (if (c/is? HttpRequest msg)
      (let [method (nc/get-method msg)
            ct (->> HttpHeaderNames/CONTENT_TYPE
                    (nc/get-header msg) str (s/lcase))]
        (cond (s/embeds? ct "application/x-www-form-urlencoded")
              (if (s/eq-any? method ["POST" "PUT"]) :post :url)
              (and (s/embeds? ct "multipart/form-data")
                   (s/eq-any? method ["POST" "PUT"])) :multipart)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fake-request<>
  ^HttpRequest []
  (DefaultHttpRequest. HttpVersion/HTTP_1_1
                       HttpMethod/POST "/" (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol CtxAPI
  ChannelHandlerContext
  (append-msg-content [ctx whole ct isLast?]
    (let [c (c/cast? HttpContent ct)
          {:keys [body impl req res]} whole]
      (cond (c/is? Attribute impl)
            (.addContent ^Attribute impl
                         (.. c content retain) (boolean isLast?))
            (c/is? InterfaceHttpPostRequestDecoder impl)
            (.offer ^InterfaceHttpPostRequestDecoder impl c))
      (when isLast?
        (.reset ^XData body (end-msg-content ctx whole))
        (cond (c/is? Attribute impl)
              (do (.removeHttpDataFromClean
                    (dfac?? ctx)
                    ^HttpRequest req ^Attribute impl)
                  (.release ^Attribute impl))
              (c/is? InterfaceHttpPostRequestDecoder impl)
              (try (.destroy
                     ^InterfaceHttpPostRequestDecoder impl)
                   (catch Throwable _ (l/exception  _ ))))
        (nc/set-akey h1pipe-M-key ctx (dissoc whole :impl)))))
  (match-one-route [ctx msg]
    (l/debug "match route for msg: %s." msg)
    ;make sure it's a request
    (or (c/when-some+ [u2 (:uri2 msg)]
          (when-some [c (nc/get-akey nc/routes-key ctx)]
            (l/debug "cracker == %s, uri= %s." c u2)
            (when (cr/rc-has-routes? c)
              (->> (select-keys msg [:method :uri])
                   (cr/rc-crack-route c)))))
        {:status? true}))
  (gist-h1-request [ctx msg]
    (let [{:keys [body ^HttpRequest req]} msg
          hs (.headers req)
          laddr (c/cast? InetSocketAddress
                         (.localAddress ^Channel (nc/ch?? ctx)))
          out {:is-keep-alive? (HttpUtil/isKeepAlive req)
               :version (.. req protocolVersion text)
               :headers hs
               :ssl? (nc/maybe-ssl? (nc/cpipe ctx))
               :remote-port (c/s->long (.get hs "remote_port") 0)
               :remote-addr (str (.get hs "remote_addr"))
               :remote-host (str (.get hs "remote_host"))
               :server-port (c/s->long (.get hs "server_port") 0)
               :server-name (str (.get hs "server_name"))
               :method (nc/get-method req)
               :body body
               :socket (nc/ch?? ctx)
               :parameters (nc/get-uri-params req)
               :uri2 (str (.uri req))
               :uri (nc/get-uri-path req)
               :charset (nc/get-msg-charset req)
               :cookies (nc/crack-cookies req)
               :local-host (some-> laddr .getHostName)
               :local-port (some-> laddr .getPort)
               :chunked? (HttpUtil/isTransferEncodingChunked req)
               :local-addr (some-> laddr .getAddress .getHostAddress)}
          {:keys [matcher status?
                  route-info redirect] :as ro}
          (match-one-route ctx out)
          ri (if (and status? route-info matcher)
               (cr/ri-collect-info route-info matcher))]
      (c/object<> czlab.niou.core.Http1xMsg
                  (merge out
                         {:scheme (if (:ssl? out) "https" "http")
                          :route (merge (dissoc ro
                                                :matcher :route-info)
                                        {:info route-info} ri)}))))
  (gist-h1-response [ctx msg]
    (let [{:keys [^HttpResponse res body]} msg
          s (.status res)]
      (c/object<> czlab.niou.core.Http1xMsg
                  :is-keep-alive? (HttpUtil/isKeepAlive res)
                  :version (.. res protocolVersion text)
                  :socket (nc/ch?? ctx)
                  :body body
                  :ssl? (nc/maybe-ssl? (nc/cpipe ctx))
                  :headers (.headers res)
                  :charset (nc/get-msg-charset res)
                  :cookies (nc/crack-cookies res)
                  :status {:code (.code s)
                           :reason (.reasonPhrase s)}
                  :chunked? (HttpUtil/isTransferEncodingChunked res))))
  (gist-h1-msg [ctx msg]
    (if (map? msg)
      (c/do-with [m (cond (c/is? HttpResponse (:res msg))
                          (gist-h1-response ctx msg)
                          (c/is? HttpRequest (:req msg))
                          (gist-h1-request ctx msg))]
        (l/debug "gisted h1-msg: %s."
                 (if m (i/fmt->edn m) "nil")))))
  (fire-msg [ctx msg]
    (fcr!! ctx
           (or (some->> msg
                        (gist-h1-msg ctx)) msg)))
  (end-msg-content [ctx whole]
    (let [{:keys [req res impl]} whole]
      (if res
        (get-http-data impl)
        (try (cond (c/is? InterfaceHttpPostRequestDecoder impl)
                   (parse-post impl)
                   (c/is? Attribute impl)
                   (get-http-data impl))
             (catch Throwable _ (l/exception _) (throw _))))))
  (prepare-body [_ msg]
    (let [req (c/cast? HttpRequest msg)
          df (dfac?? _)
          rc (chk-form-post msg)
          cs (nc/get-msg-charset msg)]
      (if (or (= rc :post)
              (= rc :multipart))
        (do (l/debug "got form-post: %s." rc)
            (HttpPostRequestDecoder. df req cs))
        (.createAttribute df req body-id))))
  (h11msg<> [ctx msg]
    (let [[req res] (if (c/is? HttpRequest msg)
                      [msg nil] [(fake-request<>) msg])]
      {:body (XData.)
       :req req
       :res res
       :fac (dfac?? ctx)
       :impl (prepare-body ctx req)}))
  (read-h1-chunk [ctx part pipelining?]
    (let [last? (c/is? LastHttpContent part)
          msg (nc/get-akey h1pipe-M-key ctx)]
      (if-not last?
        (l/debug "received chunk %s." part))
      (try (if-not (nc/decoder-success? part)
             (if-not (c/is? HttpRequest msg)
               (nc/close-ch ctx)
               (nc/reply-status ctx
                                (.code HttpResponseStatus/BAD_REQUEST)))
             (append-msg-content ctx msg part last?))
           (finally
             (nc/ref-del part)))))
  (read-last-h1-chunk [ctx part pipelining?]
    (l/debug "received last-chunk %s." part)
    (read-h1-chunk ctx part pipelining?)
    (let [q (nc/get-akey h1pipe-Q-key ctx)
          msg (nc/get-akey h1pipe-M-key ctx)
          cur (nc/get-akey h1pipe-C-key ctx)]
      (nc/del-akey h1pipe-M-key ctx)
      (l/debug "got last chunk for msg %s." msg)
      (if pipelining?
        (if (nil? cur)
          (do (nc/set-akey h1pipe-C-key ctx msg)
              (fire-msg ctx msg))
          (do (.add ^List q msg)
              (l/debug "H1 Pipelining is being used! The Marmushka!!!")))
        (fire-msg ctx msg))))
  (read-h1-message [ctx msg pipelining?]
    ;;no need to release msg -> request or response
    (let [{:keys [max-in-memory
                  max-content-size]}
          (nc/get-akey nc/chcfg-key ctx)]
      (l/debug "reading %s: %s." (u/gczn msg) msg)
      (cond (not (nc/decoder-success? msg))
            (if-not (c/is? HttpRequest msg)
              (nc/close-ch ctx)
              (nc/reply-status ctx
                               (.code HttpResponseStatus/BAD_REQUEST)))
            (not (nc/maybe-handle-100? ctx msg max-content-size))
            nil
            :else
            (do (nc/set-akey h1pipe-M-key
                             ctx (h11msg<> ctx msg))
                (cond (c/is? LastHttpContent msg)
                      (read-last-h1-chunk ctx msg pipelining?)
                      (c/is? HttpContent msg)
                      (read-h1-chunk ctx msg pipelining?))))))
  (agg-h1-read [ctx msg pipelining?]
    (cond (c/is? HttpMessage msg)
          (read-h1-message ctx msg pipelining?)
          (c/is? LastHttpContent msg)
          (read-last-h1-chunk ctx msg pipelining?)
          (c/is? HttpContent msg)
          (read-h1-chunk ctx msg pipelining?)
          :else
          (fire-msg ctx msg)))
  (dequeue-req [ctx msg pipeline?]
    (when (and (or (c/is? FullHttpResponse msg)
                   (c/is? LastHttpContent msg)) pipeline?)
      (let [^List q (nc/get-akey h1pipe-Q-key ctx)
            cur (nc/get-akey h1pipe-C-key ctx)]
        (if (nil? q)
          (u/throw-ISE "request queue is null."))
        (if (nil? cur)
          (u/throw-ISE "response but no request, msg=%s." msg))
        (let [c (if-not
                  (.isEmpty q) (.remove q 0))]
          (nc/set-akey h1pipe-C-key ctx c) (fire-msg ctx c)))))
  (finito [_ sid]
    (let [ctx (c/cast? ChannelHandlerContext _)
          ^Map hh (nc/get-akey h2msg-h-key ctx)
          ^Map dd (nc/get-akey h2msg-d-key ctx)
          [^HttpRequest fake
           ^Attribute attr]
          (some-> dd (.get sid))
          hds (some-> hh (.get sid))]
      (if fake
        (.removeHttpDataFromClean (dfac?? ctx) fake attr))
      (some-> dd (.remove sid))
      (some-> hh (.remove sid))
      (try (fcr!! ctx
                  (c/object<>
                    czlab.niou.core.Http2xMsg
                    :headers hds :body (gattr attr)))
           (finally
             (some-> attr .release)))))
  (read-h2-frameEx [ctx sid data end?]
    (let [m (nc/get-akey h2msg-d-key ctx)
          [_ ^Attribute attr] (.get ^Map m sid)]
      (.addContent attr (.retain ^ByteBuf data) (boolean end?))
      (if end? (finito ctx sid))))
  (read-h2-frame [_ sid]
    (let [ctx (c/cast? ChannelHandlerContext _)
          ^Map m (or (nc/get-akey h2msg-d-key ctx)
                     (nc/set-akey h2msg-d-key ctx (HashMap.)))
          [fake _] (.get m sid)]
      (if (nil? fake)
        (let [r (fake-request<>)]
          (.put m
                sid
                [r (.createAttribute
                     (dfac?? ctx) r body-id)])))))
  (read-ws-frame-ex [_ m]
    (let [msg (c/cast? ContinuationWebSocketFrame m)
          ctx (c/cast? ChannelHandlerContext _)
          last? (.isFinalFragment msg)
          {:as rc
           :keys [^Attribute attr
                  ^HttpRequest fake]}
          (nc/get-akey wsock-res-key ctx)]
      (.addContent attr
                   (.. msg content retain) last?)
      (nc/ref-del msg)
      (when last?
        (.removeHttpDataFromClean (dfac?? ctx) fake attr)
        (try (fcr!! ctx
                    (wsmsg<>
                      (-> (dissoc rc :attr :fake)
                          (assoc :body (gattr attr)))))
             (finally (.release attr))))))
  (read-ws-frame [ctx m]
    (let [msg (c/cast? WebSocketFrame m)
          rc {:charset (u/charset?? "utf-8")
              :isText? (c/is? TextWebSocketFrame msg)}]
      (cond (c/is? PongWebSocketFrame msg)
            (fcr!! ctx
                   (wsmsg<> (assoc rc :pong? true)))
            (.isFinalFragment msg)
            (fcr!! ctx (->> (.content msg)
                            (nc/bbuf->bytes)
                            (XData. )
                            (assoc rc :body )
                            (wsmsg<> )))
            :else
            (let [req (fake-request<>)
                  a (.createAttribute (dfac?? ctx)
                                      req body-id)]
              (.addContent a (.. msg content retain) false)
              (->> (assoc rc :attr a :fake req)
                   (nc/set-akey wsock-res-key ctx))))
      (nc/ref-del msg))))

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
        (fcr!! ctx msg)))))

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
        (let [^Map m (or (nc/get-akey h2msg-h-key ctx)
                         (nc/set-akey h2msg-h-key ctx (HashMap.)))]
          (.put m sid hds)
          (if end? (finito ctx sid))))
       ([ctx sid hds
         dep wgt ex? pad end?]
        (.onHeadersRead ^Http2FrameAdapter this ctx sid hds pad end?))))))

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
       (nc/set-akey h1pipe-Q-key ctx (ArrayList.))
       (nc/set-akey h1pipe-M-key ctx nil)
       (nc/set-akey h1pipe-C-key ctx nil))
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
       (nc/del-akey h1pipe-Q-key ctx)
       (nc/del-akey h1pipe-M-key ctx)
       (nc/del-akey h1pipe-C-key ctx)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF




