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
(c/defonce- ^AttributeKey wsockRkey (n/akey<> :wsock-res))
(c/defonce- ^AttributeKey h1pipeQkey (n/akey<> :h1pipe-q))
(c/defonce- ^AttributeKey h1pipeCkey (n/akey<> :h1pipe-c))
(c/defonce- ^AttributeKey h1pipeMkey (n/akey<> :h1pipe-m))
(c/defonce- ^AttributeKey h1pipeDkey (n/akey<> :h1pipe-d))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;reusables
(c/defonce- ^HttpResponse expected-ok
  (n/http-reply<+> (n/scode* CONTINUE)))
(c/defonce- ^HttpResponse expected-failed
  (n/http-reply<+> (n/scode* EXPECTATION_FAILED)))

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
    (fn [acc n]
      (assoc acc
             n
             (mapv #(str %) (.get params n)))) (.keySet params)))

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
               :charset (n/get-msg-charset req)
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
                  :charset (n/get-msg-charset res)
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
        (n/set-akey ctx n/req-key msg)
        (if (HttpUtil/is100ContinueExpected msg)
          (let [err? (and (pos? max-msg-size)
                          (HttpUtil/isContentLengthSet msg)
                          (> (HttpUtil/getContentLength msg) max-msg-size))]
            (-> (n/write-msg ctx
                             (if-not err?
                               expected-ok expected-failed))
                (n/cf-cb (if err? (n/cfop<z>)))))))
      (n/fire-msg ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-aggregator
  "A handler which aggregates chunks into a full request.  For http-header-expect,
  returns 100-continue if the payload size is below limit.  Also optionally handle
  http 1.1 pipelining by default."
  ^ChannelHandler
  [{:keys [h1-pipelining?
           max-mem-size max-msg-size] :as args}]
  (letfn
    [(form-post?? [msg]
       (when (n/hreq? msg)
         (let [ct (c/lcase (n/get-header msg
                                         (n/h1hdr* CONTENT_TYPE)))
               post? (n/put-post? (n/get-method msg))
               rc (cond (c/embeds? ct
                                    "application/x-www-form-urlencoded")
                        (if post? :post :url)
                        (and post?
                             (c/embeds? ct "multipart/form-data"))
                        :multipart)]
           (if rc (l/debug "got a form post: %s." ct)) rc)))
     (prep-body [msg ctx]
       (let [req (c/cast? HttpRequest msg)
             rc (form-post?? msg)
             df (n/dfac?? ctx)
             cs (n/get-msg-charset msg)]
         [(if (or (= rc :post)
                  (= rc :multipart))
            (HttpPostRequestDecoder. df msg cs)
            (.createAttribute df msg n/body-id)) msg rc]))
     (test-next? [^InterfaceHttpPostRequestDecoder deco]
       (try (.hasNext deco)
            (catch HttpPostRequestDecoder$EndOfDataDecoderException _ false)))
     (parse-post [^InterfaceHttpPostRequestDecoder deco]
       (l/debug "about to parse a form-post, decoder: %s." deco)
       (loop [out (cu/form-items<>)]
         (if-not (test-next? deco)
           (XData. out)
           (let [n (.next deco)
                 z (condp instance? n
                     FileUpload
                     (let [u (c/cast? FileUpload n)]
                       (cu/file-item<> false
                                       (.getContentType u)
                                       nil
                                       (.getName u)
                                       (.getFilename u)
                                       (n/get-http-data u true)))
                     Attribute
                     (let [a (c/cast? Attribute n)]
                       (cu/file-item<> true
                                       ""
                                       nil
                                       (.getName a)
                                       ""
                                       (n/get-http-data a true)))
                     (c/do#nil (l/warn "Unknown post content: %s." n)))]
             (recur (if z (cu/add-item out z) out))))))
     (take-part [part ctx last?]
       (let [[impl _] (n/get-akey ctx h1pipeDkey)]
         (cond (c/is? Attribute impl)
               (.addContent ^Attribute impl (n/retain! part) last?)
               (c/is? InterfaceHttpPostRequestDecoder impl)
               (.offer ^InterfaceHttpPostRequestDecoder impl part))))
     (read-part [part ctx]
       (let [msg (n/get-akey ctx h1pipeMkey)
             last? (c/is? LastHttpContent part)]
         (try (l/debug "received%schunk: %s."
                       (if last? " last " " ") part)
              (if (n/decoder-success? part)
                (take-part part ctx last?)
                (if (c/is? HttpResponse msg)
                  (n/close! ctx)
                  (n/reply-status ctx
                                  (n/scode* BAD_REQUEST))))
                   (finally (n/ref-del part)))
         ;if last content, then we need to do a couple of things
         ;1. aggregate all parts into one (process form-data)
         ;2. handle http-pipelining if on
         (when last?
           (let [[impl req] (n/get-akey ctx h1pipeDkey)
                 pipeQ (n/get-akey ctx h1pipeQkey)
                 cur (n/get-akey ctx h1pipeCkey)
                 codec (c/cast? InterfaceHttpPostRequestDecoder impl)
                 attr (c/cast? Attribute impl)
                 gist (netty->ring msg
                                   ctx
                                   (if codec
                                     (parse-post codec)
                                     (n/get-http-data attr)))]
             (some-> codec .destroy)
             (if attr
               (try (.removeHttpDataFromClean (n/dfac?? ctx)
                                              ^HttpRequest req attr)
                    (finally (.release attr))))
             (n/del-akey ctx h1pipeMkey)
             (n/del-akey ctx h1pipeDkey)
             (if-not h1-pipelining?
               (n/fire-msg ctx gist)
               (if (some? cur)
                 (.add ^List pipeQ gist) ;hold it
                 (do (n/set-akey ctx h1pipeCkey gist)
                     (n/fire-msg ctx gist))))))))
     (read-mesg [msg ctx]
       (let [req? (n/hreq? msg)]
         (l/debug "reading msg: %s." (u/gczn msg))
         (if (not (n/decoder-success? msg))
           (if-not req?
             (n/close! ctx)
             (n/reply-status ctx (n/scode* BAD_REQUEST)))
           (let [req (if req? msg (n/fake-req<>))]
             (n/set-akey ctx h1pipeMkey msg) ;save the msg
             ;create holder for pending msg content
             (->> (prep-body req ctx)
                  (n/set-akey ctx h1pipeDkey))
             (if (c/is? HttpContent msg) (read-part msg ctx))))))
     (dequeue [ctx msg]
       (when (and h1-pipelining?
                  (or (c/is? LastHttpContent msg)
                      (c/is? FullHttpResponse msg)))
         (let [cur (n/get-akey ctx h1pipeCkey)
               ^List q (n/get-akey ctx h1pipeQkey)]
           (u/assert-ISE (some? q) "request queue is null.")
           (u/assert-ISE (some? cur) "response but no request.")
           (let [c (if-not
                     (.isEmpty q) (.remove q 0))]
             (n/set-akey ctx h1pipeCkey c)
             (n/fire-msg ctx c)))))]
    (proxy [DuplexHandler][]
      (onInactive [ctx]
        (n/del-akey ctx h1pipeQkey)
        (n/del-akey ctx h1pipeMkey)
        (n/del-akey ctx h1pipeCkey))
      (onActive [ctx]
        (n/set-akey ctx h1pipeMkey nil)
        (n/set-akey ctx h1pipeCkey nil)
        (n/set-akey ctx h1pipeQkey (ArrayList.)))
      (onWrite [ctx msg cp]
        (if-not (and (c/is? FullHttpResponse msg)
                     (= HttpResponseStatus/CONTINUE
                        (.status ^FullHttpResponse msg)))
          (dequeue ctx msg)))
      (readMsg [ctx msg]
        (condp instance? msg
          HttpMessage (read-mesg msg ctx)
          HttpContent (read-part msg ctx)
          (n/fire-msg ctx msg))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^ChannelHandler websock-aggregator
  (letfn
    [(read-ws-ex [ctx ^WebSocketFrame msg]
       (let [last? (.isFinalFragment msg)
             rc (n/get-akey ctx wsockRkey)
             [^Attribute attr
              ^HttpRequest req]
             (n/get-akey ctx h1pipeDkey)]
         (.addContent attr (n/retain! msg) last?)
         (n/ref-del msg)
         (when last?
           (.removeHttpDataFromClean (n/dfac?? ctx) req attr)
           (try (n/fire-msg ctx
                            (wsmsg<> (assoc rc :body (n/gattr attr))))
                (finally (.release attr))))))
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
               (let [req (n/fake-req<>)
                     a (.createAttribute (n/dfac?? ctx) req n/body-id)]
                 (.addContent a (n/retain! msg) false)
                 (n/set-akey ctx wsockRkey rc)
                 (n/set-akey ctx h1pipeDkey [a req])))
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
(c/defonce- ^ChannelHandler websock-upgrade??
  (letfn
    [(mock [ctx req]
       (let [{:keys [headers uri2
                     protocol request-method]} req
             req' (n/get-akey ctx n/req-key)]
         (n/del-akey ctx n/req-key)
         (assert (c/is? HttpRequest req'))
         (c/do-with [rc (DefaultFullHttpRequest.
                          (HttpVersion/valueOf protocol)
                          (HttpMethod/valueOf
                            (c/ucase (str request-method))) uri2)]
           (n/set-headers rc
                          (.headers ^HttpRequest req')))))]
    (proxy [DuplexHandler][]
      (readMsg [ctx req]
        (if (and (c/embeds? (c/lcase (->> (n/h1hdr* CONNECTION)
                                          (cc/msg-header req))) "upgrade")
                 (= :get (:request-method req))
                 (c/embeds? (c/lcase (->> (n/h1hdr* UPGRADE)
                                          (cc/msg-header req))) "websocket"))
          (let [pp (n/cpipe?? ctx)
                ^String uri (:uri req)
                {:keys [wsock-path]}
                (n/get-akey ctx n/chcfg-key)]
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
                              "websock-aggregator" websock-aggregator)
                  (n/safe-remove-handler*
                    pp
                    [HttpContentDecompressor
                     HttpContentCompressor
                     ChunkedWriteHandler
                     "aggregator"
                     "continue-expected??" this])
                  (n/dbg-pipeline pp)
                  (n/fire-msg ctx (mock ctx req))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-pipeline
  [ch args]
  (let [p (n/cpipe?? ch)
        {:keys [user-handler user-cb
                max-msg-size cors-cfg]} args]
    (n/pp->last p "codec" (HttpServerCodec.))
    (if cors-cfg
      (n/pp->last p "cors" (CorsHandler. cors-cfg)))
    (n/pp->last p
                "continue-expected??"
                (continue-expected?? max-msg-size))
    (n/pp->last p
                "aggregator" (http-aggregator args))
    (n/pp->last p "websock-upgrade??" websock-upgrade??)
    (n/pp->last p "chunker" (ChunkedWriteHandler.))
    (n/pp->last p "user-func" (n/app-handler user-handler user-cb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF
