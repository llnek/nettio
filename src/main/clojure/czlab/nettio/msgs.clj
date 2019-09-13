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
(c/defonce- ^AttributeKey wsockRkey (nc/akey<> :wsock-res))
(c/defonce- ^AttributeKey h1pipeQkey (nc/akey<> :h1pipe-q))
(c/defonce- ^AttributeKey h1pipeCkey (nc/akey<> :h1pipe-c))
(c/defonce- ^AttributeKey h1pipeMkey (nc/akey<> :h1pipe-m))
(c/defonce- ^AttributeKey h1pipeDkey (nc/akey<> :h1pipe-d))
(c/defonce- ^AttributeKey h2msgHkey (nc/akey<> :h2msg-h))
(c/defonce- ^AttributeKey h2msgDkey (nc/akey<> :h2msg-d))
(c/defonce- ^String body-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol DecoderAPI
  ""
  (parse-post [_] "")
  (safe-has-next? [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol MsgAPI
  ""
  (get-msg-charset [_] "")
  (gist-msg [_ ctx body] "")
  (gist-req [_ ctx body] "")
  (gist-rsp [_ ctx body] "")
  (prepare-body [_ ctx] "")
  (h11x-msg [_ ctx pline?] "")
  (chk-form-post [_] "")
  (handle-100? [_ ctx]
               [_ ctx maxSize] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol CtxAPI
  ""
  (match-one-route [_ msg] "")
  (set-akey* [_ kvs] "")
  (del-akey* [_ keys] "")
  (gist-h1-request [_ msg] "")
  (gist-h1-response [_ msg] "")
  (gist-h1-msg [_ msg] "")
  (agg-h1-read [_ msg pipelining?] "")
  (dequeue-req [_ msg pipeline?] "")
  (fire-msg [_ msg] "")
  (finito [_ sid] "")
  (read-h2-frame [_ sid] "")
  (read-h2-frameEx [_ sid data end?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord H1Aggr [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fake-req<>
  ^HttpRequest []
  (DefaultHttpRequest. HttpVersion/HTTP_1_1
                       HttpMethod/POST "/" (DefaultHttpHeaders.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fcr!!
  [ctx msg] (if msg (.fireChannelRead ^ChannelHandlerContext ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- dfac??
  ^HttpDataFactory [ctx] (nc/get-akey nc/dfac-key ctx))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- retain!
  ^ByteBufHolder
  [^ByteBufHolder part] (.. part content retain))

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
(defprotocol PartAPI
  ""
  (take-part [_ ctx last?] "")
  (read-part [_ ctx pipelining?] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol PartAPI
  HttpContent
  (take-part [part ctx last?]
    (let [[impl _] (nc/get-akey h1pipeDkey ctx)]
      (cond (c/is? Attribute impl)
            (.addContent ^Attribute impl
                         (retain! part) last?)
            (c/is? InterfaceHttpPostRequestDecoder impl)
            (.offer ^InterfaceHttpPostRequestDecoder impl part))))

  (read-part [part ctx pipelining?]
    (let [msg (nc/get-akey h1pipeMkey ctx)
          last? (c/is? LastHttpContent part)]
      (l/debug "received%schunk %s."
               (if last? " last " " ") part)
      (try (if-not (nc/decoder-success? part)
             (if (c/is? HttpResponse msg)
               (nc/close-ch ctx)
               (->> (nc/scode* BAD_REQUEST)
                    (nc/reply-status ctx)))
             (take-part part ctx last?))
           (finally (nc/ref-del part)))
      (when last?
        (let [[impl req] (nc/get-akey h1pipeDkey ctx)
              pipeQ (nc/get-akey h1pipeQkey ctx)
              cur (nc/get-akey h1pipeCkey ctx)
              gist
              (->> (if (c/is? InterfaceHttpPostRequestDecoder impl)
                     (parse-post impl)
                     (get-http-data impl))
                   (gist-msg msg ctx ))]
          (if (c/is? InterfaceHttpPostRequestDecoder impl)
            (.destroy ^InterfaceHttpPostRequestDecoder impl)
            (try (.removeHttpDataFromClean
                   (dfac?? ctx) ^HttpRequest req ^Attribute impl)
                 (finally (.release ^Attribute impl))))
          (del-akey* ctx [h1pipeMkey h1pipeDkey])
          (if pipelining?
            (if (nil? cur)
              (do (nc/set-akey h1pipeCkey ctx gist)
                  (fcr!! ctx gist))
              (do (.add ^List pipeQ gist)
                  (l/debug "pipelining holding msg %s." gist)))
            (fcr!! ctx gist)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol WSFrameAPI
  ""
  (read-ws-frame [_ ctx] "")
  (read-ws-frame-ex [_ ctx] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol WSFrameAPI
  WebSocketFrame
  (read-ws-frame-ex [msg _]
    (let [ctx (c/cast? ChannelHandlerContext _)
          last? (.isFinalFragment msg)
          {:as rc
           :keys [^Attribute attr
                  ^HttpRequest fake]}
          (nc/get-akey wsockRkey ctx)]
      (.addContent attr
                   (retain! msg) last?)
      (nc/ref-del msg)
      (when last?
        (.removeHttpDataFromClean (dfac?? ctx) fake attr)
        (try (fcr!! ctx
                    (wsmsg<>
                      (-> (dissoc rc :attr :fake)
                          (assoc :body (gattr attr)))))
             (finally (.release attr))))))
  (read-ws-frame [msg ctx]
    (let [rc {:charset (u/charset?? "utf-8")
              :isText? (c/is? TextWebSocketFrame msg)}]
      (cond (c/is? PongWebSocketFrame msg)
            (fcr!! ctx
                   (wsmsg<> (assoc rc :pong? true)))
            (.isFinalFragment msg)
            (fcr!! ctx (->> (.content msg)
                            (nc/bbuf->bytes)
                            XData.
                            (assoc rc :body ) wsmsg<>))
            :else
            (let [req (fake-req<>)
                  a (.createAttribute (dfac?? ctx)
                                      req body-id)]
              (.addContent a (retain! msg) false)
              (->> (assoc rc :attr a :fake req)
                   (nc/set-akey wsockRkey ctx))))
      (nc/ref-del msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol MsgAPI
  HttpMessage
  (gist-msg [msg ctx body]
    (c/condp?? instance? msg
      HttpRequest (gist-req msg ctx body)
      HttpResponse (gist-rsp msg ctx body)))
  (gist-req [msg ctx body]
    (let [req (c/cast? HttpRequest msg)
          ^Channel ch (nc/ch?? ctx)
          hs (.headers req)
          laddr (c/cast? InetSocketAddress
                         (.localAddress ch))
          out {:is-keep-alive? (HttpUtil/isKeepAlive req)
               :version (.. req protocolVersion text)
               :headers hs
               :ssl? (nc/maybe-ssl? (nc/cpipe ctx))
               :remote-port (c/s->long (.get hs "remote_port") 0)
               :remote-addr (str (.get hs "remote_addr"))
               :remote-host (str (.get hs "remote_host"))
               :server-port (c/s->long (.get hs "server_port") 0)
               :server-name (str (.get hs "server_name"))
               :parameters (nc/get-uri-params req)
               :method (nc/get-method req)
               :body (XData. body)
               :socket ch
               :uri2 (str (.uri req))
               :uri (nc/get-uri-path req)
               :charset (nc/get-msg-charset req)
               :cookies (nc/crack-cookies req)
               :local-host (some-> laddr .getHostName)
               :local-port (some-> laddr .getPort)
               :chunked? (HttpUtil/isTransferEncodingChunked req)
               :local-addr (some-> laddr .getAddress .getHostAddress)}
          {:as ro
           :keys [matcher status?
                  route-info redirect]}
          (match-one-route ctx out)
          ri (if (and status?
                      matcher
                      route-info)
               (cr/ri-collect-info route-info matcher))]
      (c/object<> czlab.niou.core.Http1xMsg
                  (merge out
                         {:scheme (if (:ssl? out) "https" "http")
                          :route (merge (dissoc ro
                                                :matcher
                                                :route-info)
                                        {:info route-info} ri)}))))
  (gist-rsp [msg ctx body]
    (let [res (c/cast? HttpResponse msg)
          s (.status res)]
      (c/object<> czlab.niou.core.Http1xMsg
                  :is-keep-alive? (HttpUtil/isKeepAlive res)
                  :version (.. res protocolVersion text)
                  :socket (nc/ch?? ctx)
                  :body (XData. body)
                  :ssl? (nc/maybe-ssl? (nc/cpipe ctx))
                  :headers (.headers res)
                  :charset (nc/get-msg-charset res)
                  :cookies (nc/crack-cookies res)
                  :status {:code (.code s)
                           :reason (.reasonPhrase s)}
                  :chunked? (HttpUtil/isTransferEncodingChunked res))))
  (get-msg-charset [msg]
    (HttpUtil/getCharset msg (Charset/forName "utf-8")))
  (prepare-body [msg ctx]
    (let [req (c/cast? HttpRequest msg)
          df (dfac?? ctx)
          rc (chk-form-post msg)
          cs (get-msg-charset msg)]
      [(if (or (= rc :post)(= rc :multipart))
         (HttpPostRequestDecoder. df req cs)
         (.createAttribute df req body-id)) req]))
  (handle-100?
    ([msg _c]
     (handle-100? msg _c -1))
    ([msg ctx maxSize]
     (if (and (nc/hreq? msg)
              (HttpUtil/is100ContinueExpected msg))
       (let [err? (and (c/spos? maxSize)
                       (HttpUtil/isContentLengthSet msg)
                       (> (HttpUtil/getContentLength msg) maxSize))
             rsp (nc/http-reply<+>
                   (if-not err?
                     (nc/scode* CONTINUE)
                     (nc/scode* EXPECTATION_FAILED)))]
         (-> (.writeAndFlush ^ChannelHandlerContext ctx rsp)
             (nc/future-cb (if err? (nc/cfop<z>))))
         (not err?))
       true)))
  (h11x-msg [msg _c pipelining?]
    (let [{:keys [max-msg-size
                  max-mem-size]}
          (nc/get-akey nc/chcfg-key _c)
          ctx (c/cast? ChannelHandlerContext _c)]
      (l/debug "reading %s." (u/gczn msg))
      (cond (not (nc/decoder-success? msg))
            (if-not (nc/hreq? msg)
              (nc/close-ch ctx)
              (nc/reply-status ctx
                               (nc/scode* BAD_REQUEST)))
            (not (handle-100? msg ctx max-msg-size))
            nil
            :else
            (let [req (or (c/cast? HttpRequest msg)
                          (fake-req<>))]
              ;save the request/response
              (nc/set-akey h1pipeMkey ctx msg)
              ;create the data holder
              (->> (prepare-body req ctx)
                   (nc/set-akey h1pipeDkey ctx))
              (if (c/is? HttpContent msg)
                (read-part msg ctx pipelining?))))))
  (chk-form-post [msg]
    (when (nc/hreq? msg)
      (let [method (nc/get-method msg)
            ct (->> (nc/h1hdr* CONTENT_TYPE)
                    (nc/get-header msg) str (c/lcase))
            ok? (cond (c/embeds? ct "application/x-www-form-urlencoded")
                      (if (c/eq-any? method ["POST" "PUT"]) :post :url)
                      (and (c/embeds? ct "multipart/form-data")
                           (c/eq-any? method ["POST" "PUT"])) :multipart)]
        (if ok?
          (l/debug "got a form post: %s" ct)) ok?))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol CtxAPI
  ChannelHandlerContext
  (fire-msg [_ msg]
    (if msg (.fireChannelRead _ msg)))
  (set-akey* [ctx kvs]
    (doseq [[k v]
            (partition 2 kvs)]
      (nc/set-akey k ctx v)))
  (del-akey* [ctx keys]
    (doseq [k keys] (nc/del-akey k ctx)))
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
  (agg-h1-read [ctx msg pipelining?]
    (cond (c/is? HttpMessage msg)
          (h11x-msg msg ctx pipelining?)
          (c/is? HttpContent msg)
          (read-part msg ctx pipelining?)
          :else
          (fcr!! ctx msg)))
  (dequeue-req [ctx msg pipeline?]
    (when (and (or (c/is? FullHttpResponse msg)
                   (c/is? LastHttpContent msg)) pipeline?)
      (let [^List q (nc/get-akey h1pipeQkey ctx)
            cur (nc/get-akey h1pipeCkey ctx)]
        (if (nil? q)
          (u/throw-ISE "request queue is null."))
        (if (nil? cur)
          (u/throw-ISE "response but no request, msg=%s." msg))
        (let [c (if-not
                  (.isEmpty q) (.remove q 0))]
          (nc/set-akey h1pipeCkey ctx c) (fcr!! ctx c)))))
  (finito [ctx sid]
    (let [^Map hh (nc/get-akey h2msgHkey ctx)
          ^Map dd (nc/get-akey h2msgDkey ctx)
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
    (let [m (nc/get-akey h2msgDkey ctx)
          [_ ^Attribute attr] (.get ^Map m sid)]
      (.addContent attr
                   (.retain ^ByteBuf data) (boolean end?))
      (if end? (finito ctx sid))))
  (read-h2-frame [ctx sid]
    (let [^Map m (or (nc/get-akey h2msgDkey ctx)
                     (nc/set-akey h2msgDkey ctx (HashMap.)))
          [fake _] (.get m sid)]
      (if (nil? fake)
        (let [r (fake-req<>)]
          (.put m
                sid
                [r (.createAttribute
                     (dfac?? ctx) r body-id)]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def
  ^{:tag ChannelHandler
    :doc "A handler that aggregates frames into a full message."}
  wsock-aggregator<>
  (proxy [DuplexHandler][false]
    (readMsg [ctx msg]
      (cond
        (c/is? ContinuationWebSocketFrame msg)
        (read-ws-frame-ex msg ctx)
        (or (c/is? TextWebSocketFrame msg)
            (c/is? PongWebSocketFrame msg)
            (c/is? BinaryWebSocketFrame msg))
        (read-ws-frame msg ctx)
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
       (l/debug "rec'ved h2-data: sid#%s, end?=%s." sid end?)
       (c/do-with [b (+ pad (.readableBytes ^ByteBuf data))]
         (read-h2-frame ctx sid)
         (read-h2-frameEx ctx sid data end?)))
     (onHeadersRead
       ([ctx sid hds pad end?]
        (l/debug "rec'ved h2-headers: sid#%s, end?=%s." sid end?)
        (let [^Map m (or (nc/get-akey h2msgHkey ctx)
                         (nc/set-akey h2msgHkey ctx (HashMap.)))]
          (.put m sid hds)
          (if end? (finito ctx sid))))
       ([ctx sid hds
         dep wgt ex? pad end?]
        (.onHeadersRead ^Http2FrameAdapter this ctx sid hds pad end?))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1req-aggregator<>
  "A handler which aggregates chunks into a full request.  For http-header-expect,
  returns 100-continue if the payload size is below limit.  Also optionally handle
  http 1.1 pipelining by default."
  {:tag ChannelHandler}
  ([] (h1req-aggregator<> true))
  ([pipelining?]
   (proxy [DuplexHandler][false]
     (onActive [ctx]
       (set-akey* ctx
                  [h1pipeMkey nil
                   h1pipeCkey nil
                   h1pipeQkey (ArrayList.)]))
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
       (del-akey* ctx [h1pipeQkey h1pipeMkey h1pipeCkey])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF



