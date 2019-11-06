;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.nettio.http

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.log :as l]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.nettio.core :as n]
            [czlab.niou.core :as cc]
            [czlab.niou.upload :as cu]
            [czlab.niou.routes :as cr])

  (:import [io.netty.handler.stream ChunkedWriteHandler]
           [io.netty.handler.codec.http.cors
            CorsConfig
            CorsHandler]
           [java.net URL InetSocketAddress]
           [java.util ArrayList Map List]
           [czlab.nettio DuplexHandler]
           [czlab.basal XData]
           [io.netty.util AttributeKey]
           [io.netty.handler.codec.http
            LastHttpContent
            HttpHeaderNames
            HttpResponse
            HttpRequest
            HttpMessage
            HttpContent
            HttpMethod
            HttpUtil
            HttpHeaders
            HttpVersion
            HttpServerCodec
            FullHttpResponse
            HttpResponseStatus
            QueryStringDecoder
            HttpContentCompressor
            DefaultFullHttpRequest
            HttpContentDecompressor]
           [io.netty.channel
            ChannelPipeline
            ChannelFuture
            Channel
            ChannelHandler
            ChannelHandlerContext]
           [io.netty.handler.codec.http.websocketx
            WebSocketFrame
            PongWebSocketFrame
            TextWebSocketFrame
            BinaryWebSocketFrame
            ContinuationWebSocketFrame
            WebSocketServerProtocolHandler]
           [io.netty.handler.codec.http.multipart
            FileUpload
            Attribute
            HttpPostRequestDecoder
            InterfaceHttpPostRequestDecoder
            HttpPostRequestDecoder$EndOfDataDecoderException]
           [io.netty.handler.codec.http.websocketx.extensions.compression
            WebSocketServerCompressionHandler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol Netty->RingMap
  (netty->ring [_ ctx body] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce- ^AttributeKey wsock-rkey (n/akey<> :wsock-res))
(c/defonce- ^AttributeKey h1p-qkey (n/akey<> :h1pipe-q))
(c/defonce- ^AttributeKey h1p-ckey (n/akey<> :h1pipe-c))
(c/defonce- ^AttributeKey h1p-mkey (n/akey<> :h1pipe-m))
(c/defonce- ^AttributeKey h1p-dkey (n/akey<> :h1pipe-d))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;reusables
(c/defonce- ^HttpResponse expected-ok
  (n/http-reply<+> (n/scode* CONTINUE)))
(c/defonce- ^HttpResponse expected-failed
  (n/http-reply<+> (n/scode* EXPECTATION_FAILED)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- decoder<>
  [ctx msg]
  `(HttpPostRequestDecoder.
     (czlab.nettio.core/dfac?? ~ctx)
     ~(with-meta msg {:tag 'HttpRequest})
     (czlab.nettio.core/get-charset ~msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- has-next?
  [deco]
  `(try (.hasNext ~deco)
        (catch HttpPostRequestDecoder$EndOfDataDecoderException ~'_ false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- headers->map
  [^HttpHeaders hds]
  (c/preduce<map>
    (fn [acc ^String n]
      (assoc acc
             (cs/lower-case n)
             (mapv #(str %) (.getAll hds n)))) (.names hds)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- params->map
  [^Map params]
  (c/preduce<map>
    (fn [acc ^String n]
      (assoc acc
             n
             (mapv #(str %) (.get params n)))) (.keySet params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- finz-decoder
  [impl]
  `(some-> (c/cast? InterfaceHttpPostRequestDecoder ~impl) .destroy))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- not-cont-100?
  [msg]
  `(not= HttpResponseStatus/CONTINUE
         (some-> (c/cast? FullHttpResponse ~msg) .status)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol Netty->RingMap
  HttpRequest
  (netty->ring [req ctx body]
    (let [ssl (n/get-ssl?? ctx)
          ch (n/ch?? ctx)
          hs (.headers req)
          ccert (some-> ssl
                        .engine
                        .getSession
                        .getPeerCertificates)
          q (QueryStringDecoder. (.uri req))
          laddr (c/cast? InetSocketAddress
                         (.localAddress ch))
          out {:keep-alive? (HttpUtil/isKeepAlive req)
               :protocol (.. req protocolVersion text)
               :request-method (n/get-method req)
               :headers (headers->map hs)
               :scheme (if ssl :https :http)
               :ssl-client-cert (first ccert)
               :ssl? (some? ssl)
               :remote-port (c/s->long (.get hs "remote_port") 0)
               :remote-addr (str (.get hs "remote_addr"))
               :remote-host (str (.get hs "remote_host"))
               :server-port (c/s->long (.get hs "server_port") 0)
               :server-name (str (.get hs "server_name"))
               :parameters (params->map (.parameters q))
               :query-string (.rawQuery q)
               :body (XData. body)
               :socket ch
               :uri2 (str (.uri req))
               :uri (.path q)
               :charset (n/get-charset req)
               :cookies (n/crack-cookies req)
               :local-host (some-> laddr .getHostName)
               :local-port (some-> laddr .getPort)
               :local-addr (some-> laddr .getAddress .getHostAddress)}
        {:as ro
         :keys [matcher status?
                route-info redirect]}
        (n/match-one-route?? ctx out)
        ri (if (and status?
                    matcher
                    route-info)
             (cr/ri-collect-info route-info matcher))]
    (c/object<> czlab.niou.core.Http1xMsg
                (assoc out
                       :route (merge (dissoc ro
                                             :matcher
                                             :route-info)
                                     {:info route-info} ri)))))
  HttpResponse
  (netty->ring [res ctx body]
    (let [s (.status res)]
      (c/object<> czlab.niou.core.Http1xMsg
                  :keep-alive? (HttpUtil/isKeepAlive res)
                  :protocol (.. res protocolVersion text)
                  :ssl? (some? (n/get-ssl?? ctx))
                  :socket (n/ch?? ctx)
                  :body (XData. body)
                  :charset (n/get-charset res)
                  :cookies (n/crack-cookies res)
                  :status (.code s)
                  :status-reason (.reasonPhrase s)
                  :headers (headers->map (.headers res))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- wsmsg<>
  [m] (c/object<> czlab.niou.core.WsockMsg
                  (assoc m :route {:status? true})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn continue-expected??
  ^ChannelHandler
  [{:keys [max-msg-size] :as args}]
  (proxy [DuplexHandler][]
    (readMsg [ctx msg]
      (when (n/hreq? msg)
        (n/akey+ ctx n/req-key msg)
        (if (HttpUtil/is100ContinueExpected msg)
          (let [err? (and (pos? max-msg-size)
                          (HttpUtil/isContentLengthSet msg)
                          (> (HttpUtil/getContentLength msg) max-msg-size))]
            (-> (->> (if-not err?
                       expected-ok expected-failed)
                     (n/write-msg ctx))
                (n/cf-cb (if err? (n/cfop<z>)))))))
      (n/fire-msg ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-adder
  "A handler which aggregates chunks into a full request.  For http-header-expect,
  returns 100-continue if the payload size is below limit.  Also optionally handle
  http 1.1 pipelining by default."
  ^ChannelHandler
  [{:keys [h1-pipelining?
           max-mem-size max-msg-size] :as args}]
  (letfn
    [(form?? [msg]
       (if (n/hreq? msg)
         (let [post? (n/put-post? (n/get-method msg))
               ct (c/lcase (n/get-header msg (n/h1hdr* CONTENT_TYPE)))]
           (cond (c/embeds? ct n/ct-form-url)
                 (if post? :post :url)
                 (and post?
                      (c/embeds? ct n/ct-form-mpart)) :multipart))))
     (parse-post [^InterfaceHttpPostRequestDecoder deco]
       (l/debug "about to parse a form-post, decoder: %s." deco)
       (loop [out (cu/form-items<>)]
         (if-not (has-next? deco)
           (try (XData. out)
                (finally (.destroy deco)))
           (let [n (.next deco)
                 nm (.getName n)
                 b (n/get-http-data n true)]
             (->> (or (if-some
                        [u (c/cast? FileUpload n)]
                        (cu/file-item<>
                          false
                          (.getContentType u)
                          nil
                          nm
                          (.getFilename u) b))
                      (if-some
                        [a (c/cast? Attribute n)]
                        (cu/file-item<> true "" nil nm "" b))
                      (u/throw-IOE "Bad http data."))
                  (cu/add-item out) recur)))))
     (add?? [impl part last?]
       (cond (n/ihprd? impl)
             (n/offer! impl part)
             (n/mp-attr? impl)
             (n/add->mp-attr! impl part last?)))
     (err?? [msg ctx]
       (if-not (n/hreq? msg)
         (n/close! ctx)
         (n/reply-status ctx (n/scode* BAD_REQUEST))))
     (h1pipe [gist ctx]
       (let [cur (n/akey?? ctx h1p-ckey)
             pipeQ (n/akey?? ctx h1p-qkey)]
         (if (some? cur)
           (.add ^List pipeQ gist) ;hold it
           (do (n/akey+ ctx h1p-ckey gist)
               (n/fire-msg ctx gist)))))
     (last-part [ctx]
       (let [impl (n/akey?? ctx h1p-dkey)
             msg (n/akey?? ctx h1p-mkey)
             gist (->> (if (n/ihprd? impl)
                         (parse-post impl)
                         (n/get-mp-attr impl))
                       (netty->ring msg ctx))]
         (n/del-akey* ctx h1p-mkey h1p-dkey)
         (if h1-pipelining?
           (h1pipe gist ctx)
           (n/fire-msg ctx gist))))
     (read-part [ctx part]
       (let [end? (n/last-part? part)
             impl (n/akey?? ctx h1p-dkey)]
         (l/debug "received%schunk: %s."
                  (if end? " last " " ") part)
         (try (if (n/decoder-err? part)
                (err?? impl ctx)
                (do (add?? impl part end?)
                    (if end? (last-part ctx))))
              (finally (n/ref-del part)))))]
    (proxy [DuplexHandler][]
      (onInactive [ctx]
        (n/del-akey* ctx h1p-qkey h1p-mkey h1p-ckey))
      (onActive [ctx]
        (n/set-akey* ctx
                     [h1p-mkey nil]
                     [h1p-ckey nil]
                     [h1p-qkey (ArrayList.)]))
      (onWrite [ctx msg _]
        (if (and h1-pipelining?
                 (not-cont-100? msg)
                 (c/or?? [msg instance?]
                         LastHttpContent
                         FullHttpResponse))
          (let [cur (n/akey?? ctx h1p-ckey)
               ^List q (n/akey?? ctx h1p-qkey)]
           (u/assert-ISE (some? cur) "h1pipeline corrupt!")
           (c/doto->> (if-not
                        (.isEmpty q) (.remove q 0))
             (n/akey+ ctx h1p-ckey)
             (n/fire-msg ctx)))))
      (readMsg [ctx msg]
        ;(l/debug "reading msg: %s." (u/gczn msg))
        (condp instance? msg
          HttpMessage
          (if (n/decoder-err? msg)
            (if-not (n/hreq? msg)
              (n/close! ctx)
              (n/reply-status ctx (n/scode* BAD_REQUEST)))
            (do (n/akey+ ctx h1p-mkey msg) ;save the msg
                (n/akey+ ctx
                         h1p-dkey
                         (if (c/or??
                               [(form?? msg) =] :post :multipart)
                           (decoder<> ctx msg)
                           (n/data-attr<> max-mem-size)))
                (if (c/is? HttpContent msg) (read-part ctx msg))))
          HttpContent
          (read-part ctx msg)
          ;else
          (n/fire-msg ctx msg))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^ChannelHandler websock-adder
  (letfn
    [(read-ws-ex [ctx ^WebSocketFrame msg]
       (let [last? (.isFinalFragment msg)
             rc (n/akey?? ctx wsock-rkey)
             attr (c/_1 (n/akey?? ctx h1p-dkey))]
         (n/add->mp-attr! attr msg last?)
         (n/ref-del msg)
         (when last?
           (try (n/fire-msg ctx
                            (wsmsg<> (assoc rc :body (n/gattr attr))))
                (finally (n/ref-del attr))))))
     (read-ws [ctx ^WebSocketFrame msg]
       (let [rc {:charset (u/charset?? "utf-8")
                 :is-text? (c/is? TextWebSocketFrame msg)}]
         (cond (c/is? PongWebSocketFrame msg)
               (n/fire-msg ctx (wsmsg<> (assoc rc :pong? true)))
               (.isFinalFragment msg)
               (n/fire-msg ctx (wsmsg<> (->> (.content msg)
                                             (n/bbuf->bytes)
                                             XData.
                                             (assoc rc :body))))
               :else
               (let [{:keys [max-mem-size]}
                     (n/akey?? ctx n/chcfg-key)
                     a (n/data-attr<> max-mem-size)]
                 (n/add->mp-attr! a msg false)
                 (n/akey+ ctx wsock-rkey rc)
                 (n/akey+ ctx h1p-dkey [a nil])))
         (n/ref-del msg)))]
    (proxy [DuplexHandler][]
      (readMsg [ctx msg]
        (cond (c/is? ContinuationWebSocketFrame msg)
              (read-ws-ex ctx msg)
              (or (c/is? TextWebSocketFrame msg)
                  (c/is? PongWebSocketFrame msg)
                  (c/is? BinaryWebSocketFrame msg))
              (read-ws ctx msg)
              :else
              (n/fire-msg ctx msg))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce- ^ChannelHandler websock??
  (letfn
    [(mock [ctx req]
       (let [{:keys [headers uri2
                     protocol request-method]} req
             req' (n/akey?? ctx n/req-key)]
         (n/akey- ctx n/req-key)
         (assert (c/is? HttpRequest req'))
         (c/do-with [rc (DefaultFullHttpRequest.
                          (HttpVersion/valueOf protocol)
                          (HttpMethod/valueOf
                            (c/ucase (str request-method))) uri2)]
           (n/set-headers rc
                          (.headers ^HttpRequest req')))))]
    (proxy [DuplexHandler][]
      (readMsg [ctx req]
        (if (and (= :get (:request-method req))
                 (c/embeds? (c/lcase (->> (n/h1hdr* CONNECTION)
                                          (cc/msg-header req))) "upgrade")
                 (c/embeds? (c/lcase (->> (n/h1hdr* UPGRADE)
                                          (cc/msg-header req))) "websocket"))
          (let [pp (n/cpipe?? ctx)
                ^String uri (:uri req)
                {:keys [wsock-path]}
                (n/akey?? ctx n/chcfg-key)]
            (if-not (if-not (set? wsock-path)
                      (.equals uri wsock-path) (c/in? wsock-path uri))
              (n/reply-status ctx (n/scode* FORBIDDEN))
              (do (n/pp->next pp
                              (n/ctx-name pp this)
                              "WSSCH"
                              (WebSocketServerCompressionHandler.))
                  (n/pp->next pp
                              "WSSCH"
                              "WSSPH"
                              (WebSocketServerProtocolHandler. uri nil true))
                  (n/pp->next pp
                              "WSSPH"
                              "websock-adder" websock-adder)
                  (n/safe-remove-handler*
                    pp
                    [HttpContentDecompressor
                     HttpContentCompressor
                     ChunkedWriteHandler
                     "http-adder"
                     "continue-expected??" this])
                  (n/dbg-pipeline pp)
                  (n/fire-msg ctx (mock ctx req))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-pipeline
  [p args]
  (let [{:keys [user-handler user-cb
                max-msg-size cors-cfg]} args]
    (n/pp->last p "codec" (HttpServerCodec.))
    (some->> cors-cfg
             CorsHandler.
             (n/pp->last p "cors"))
    (n/pp->last p
                "continue-expected??"
                (continue-expected?? max-msg-size))
    (n/pp->last p
                "http-adder" (http-adder args))
    (n/pp->last p "websock??" websock??)
    (n/pp->last p "chunker" (ChunkedWriteHandler.))
    (n/pp->last p "user-func" (n/app-handler user-handler user-cb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-pipelining
  ""
  ^ChannelHandler
  [args]
  (proxy [DuplexHandler][]
    (onInactive [ctx]
      (n/del-akey* ctx h1p-qkey h1p-ckey))
    (onActive [ctx]
      (n/set-akey* ctx
                   [h1p-ckey nil]
                   [h1p-qkey (ArrayList.)]))
    (readMsg [ctx msg]
      (n/fire-msg ctx msg))
    (onWrite [ctx msg _]
      (if (and (not-cont-100? msg)
               (c/or?? [msg instance?]
                       LastHttpContent
                       FullHttpResponse))
        (let [cur (n/akey?? ctx h1p-ckey)
              ^List q (n/akey?? ctx h1p-qkey)]
          (u/assert-ISE (some? cur)
                        "h1pipeline corrupt!")
          (c/doto->> (if-not
                       (.isEmpty q) (.remove q 0))
            (n/akey+ ctx h1p-ckey) (n/fire-msg ctx)))))))



