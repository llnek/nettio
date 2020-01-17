;; Copyright © 2013-2020, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.nettio.http

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.nettio.core :as n]
            [czlab.niou.core :as cc]
            [czlab.niou.upload :as cu]
            [czlab.niou.routes :as cr]
            [czlab.nettio.cors :as cors])

  (:import [io.netty.handler.stream ChunkedWriteHandler]
           [java.util ArrayList HashMap Map List]
           [czlab.niou.core WsockMsg]
           [czlab.niou Headers]
           [io.netty.handler.codec.http.cors
            CorsConfigBuilder
            CorsConfig
            CorsHandler]
           [java.net URL URI InetSocketAddress]
           [czlab.nettio
            DuplexHandler
            InboundHandler]
           [czlab.basal FailFast XData]
           [io.netty.util AttributeKey]
           [io.netty.handler.codec.http
            DefaultHttpHeaders
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
            PingWebSocketFrame
            PongWebSocketFrame
            TextWebSocketFrame
            CloseWebSocketFrame
            BinaryWebSocketFrame
            WebSocketFrameAggregator
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
(c/def- ct-form-url "application/x-www-form-urlencoded")
(c/def- ct-form-mpart "multipart/form-data")
(c/def- hd-upgrade "upgrade")
(c/def- hd-websocket "websocket")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn config->cors

  "Convert input to Netty's CorsConfig."
  {:tag CorsConfig
   :arglists '([args])}
  [args]

  (let [{:keys [enabled?
                allow-creds?
                allowed-req-headers
                allowed-req-methods
                null-origin?
                expose-headers
                any-origin?
                origins
                max-age
                preflight-rsp-headers?
                preflight-rsp-header
                short-circuit?]} args]
    (let [^CorsConfigBuilder
          b (cond
              any-origin?
              (CorsConfigBuilder/forAnyOrigin)
              (not-empty origins)
              (if (c/one? origins)
                (CorsConfigBuilder/forOrigin (c/_1 origins))
                (CorsConfigBuilder/forOrigins (into-array String origins)))
              :else
              (u/throw-BadArg "cors-config must define any-origin or some origins."))]
      (if (number? max-age)
        (.maxAge b (long max-age)))
      (if allow-creds?
        (.allowCredentials b))
      (if null-origin?
        (.allowNullOrigin b))
      (if (not-empty allowed-req-headers)
        (.allowedRequestHeaders b
                                #^"[Ljava.lang.String;"
                                (into-array String allowed-req-headers)))
      (when (not-empty allowed-req-methods)
        (->> allowed-req-methods
             (map #(HttpMethod/valueOf (cs/upper-case %1)))
             (into-array HttpMethod)
             (.allowedRequestMethods b)))
      (if (not-empty expose-headers)
        (.exposeHeaders b
                        #^"[Ljava.lang.String;"
                        (into-array String expose-headers)))
      (if (false? preflight-rsp-headers?)
        (.noPreflightResponseHeaders b))
      (when (not-empty preflight-rsp-header)
        (let [^CharSequence n (nth preflight-rsp-header 0)]
          (.preflightResponseHeader b
                                    n
                                    ^java.lang.Iterable
                                    (into [] (drop 1 preflight-rsp-header)))))
      (if short-circuit? (.shortCircuit b))
      (if (false? enabled?) (.disable b))
      (.build b))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol Netty->RingMap
  (netty->ring [_ ctx body]
               "Convert a Netty message to ring compliant map."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;reusables
(c/def- ^HttpResponse expected-ok
  (n/http-reply<+> (n/scode* CONTINUE)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^HttpResponse expected-failed
  (n/http-reply<+> (n/scode* EXPECTATION_FAILED)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- decoder<>

  [ctx msg]

  `(HttpPostRequestDecoder.
     (czlab.nettio.core/dfac?? ~ctx)
     ~(with-meta msg {:tag 'HttpRequest})
     (czlab.nettio.core/get-charset ~msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn std->headers

  "Convert internal Headers to Netty Headers."
  {:tag HttpHeaders
   :arglists '([hds])}
  [hds]
  {:pre [(c/is? Headers hds)]}

  (reduce
    (fn [^HttpHeaders acc ^String n]
      (let [lst (.get ^Headers hds n)]
        (if (== 1 (.size lst))
          (.set acc n ^String (.get lst 0))
          (doseq [v lst]
            (.add acc n ^String v))) acc))
    (DefaultHttpHeaders.)
    (.keySet ^Headers hds)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- headers->std

  ^Headers
  [^HttpHeaders hds]

  (reduce
    (fn [^Headers acc ^String n]
      (doseq [v (.getAll hds n)]
       (.add acc n ^String v)) acc) (Headers.) (.names hds)))

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
      (assoc! acc
              n
              (mapv #(str %) (.get params n)))) (.keySet params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol Netty->RingMap
  HttpRequest
  (netty->ring [req ctx body]
    (let [ssl (n/get-ssl?? ctx)
          ch (n/ch?? ctx)
          hs (.headers req)
          u (.uri req)
          ccert (c/try! (some-> ssl
                                .engine
                                .getSession
                                .getPeerCertificates))
          q (QueryStringDecoder. u)
          uriObj (URI. u)
          laddr (c/cast? InetSocketAddress
                         (.localAddress ch))
          out {:keep-alive? (HttpUtil/isKeepAlive req)
               :protocol (.. req protocolVersion text)
               :request-method (n/get-method req)
               :headers (headers->std hs)
               :scheme (if ssl :https :http)
               :ssl-client-cert (first ccert)
               :ssl? (some? ssl)
               :remote-port (c/s->long (.get hs "remote_port") 0)
               :remote-addr (str (.get hs "remote_addr"))
               :remote-host (str (.get hs "remote_host"))
               :server-port (c/s->long (.get hs "server_port") 0)
               :server-name (str (.get hs "server_name"))
               :parameters (params->map (.parameters q))
               :query-string (.getRawQuery uriObj)
               :uri (.getRawPath uriObj)
               :body (XData. body)
               :socket ch
               :uri2 uriObj
               :charset (n/get-charset req)
               :cookies (n/crack-cookies req)
               :local-host (some-> laddr .getHostName)
               :local-port (some-> laddr .getPort)
               :local-addr (some-> laddr .getAddress .getHostAddress)}]
      (n/akey+ ctx n/origin-key (.get hs (n/h1hdr* ORIGIN)))
      (c/object<> czlab.niou.core.Http1xMsg
                  (assoc out
                         :route
                         (n/match-one-route?? ctx out)))))
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
                  :headers (headers->std (.headers res))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def

  ^{:tag ChannelHandler
    :doc "A WebSocket Handler."}

  ws-monolith<>

  (proxy [DuplexHandler][true]
    (preWrite [ctx msg]
      (if-not (c/is? WsockMsg msg)
        msg
        (let [ch (n/ch?? ctx)
              {:keys [is-close?
                      is-ping?
                      is-pong?
                      is-text?
                      ^XData body]} msg]
          (c/debug "about to write a websock-frame.")
          (cond is-close?
                (CloseWebSocketFrame.)
                is-ping?
                (PingWebSocketFrame.)
                is-pong?
                nil;(PongWebSocketFrame.)
                is-text?
                (try (TextWebSocketFrame. (.strit body))
                     (finally (c/debug "writing a text ws-frame.")))
                (some? body)
                (try (BinaryWebSocketFrame. (n/bbuf?? (.getBytes body) ch))
                     (finally (c/debug "writing a binary ws-frame.")))))))
    (onRead [ctx ch msg]
      (let [bb (cond
                 (c/is? PingWebSocketFrame msg)
                 (do :ping
                     (n/write-msg ctx (PongWebSocketFrame.)))
                 (c/is? PongWebSocketFrame msg)
                 :pong
                 (c/is? CloseWebSocketFrame msg)
                 (c/do->nil (n/close! ctx))
                 (or (c/is? TextWebSocketFrame msg)
                     (c/is? BinaryWebSocketFrame msg))
                 (.content ^WebSocketFrame msg))]
        (c/debug "reading a ws-frame = %s." msg)
        (some->> (some-> (cond
                           (= :pong bb) {:is-pong? true}
                           (= :ping bb) {:is-ping? true}
                           (some? bb)
                           {:body (XData. (n/bbuf->bytes bb))
                            :is-text? (c/is? TextWebSocketFrame msg)})
                         (assoc :socket (n/ch?? ctx))
                         cc/ws-msg<>)
                 (n/fire-msg ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cfg-websock

  [^ChannelHandlerContext ctx ^HttpRequest req]

  (let [cc (n/cache?? ctx)
        uri (.uri req)
        path (.path (QueryStringDecoder. uri))
        {:keys [wsock-path max-frame-size]} (n/chcfg?? ctx)]
    (if (c/hgl? wsock-path)
      (when-not (cs/starts-with? path wsock-path)
        (c/mdel! cc :mode)
        (n/reply-status ctx (n/scode* FORBIDDEN))
        (u/throw-FFE "mismatch websock path in config.")))
    (let [cn (.name ctx)
          pp (n/cpipe?? ctx)
          q (c/mget cc :queue)
          mock (DefaultFullHttpRequest.
                 (.protocolVersion req)
                 (.method req) (.uri req))]
      ;websock, so no pipeline!
      (if q (.clear ^List q))
      ;fake the headers
      (.add (.headers mock)
            (.headers req))

      ;alter pipeline
      (n/pp->next pp cn "WSSCH" (WebSocketServerCompressionHandler.))
      (n/pp->next pp "WSSCH" "WSSPH" (WebSocketServerProtocolHandler. uri nil true))

      (n/pp->next pp "WSSPH"
                  "WSACC" (WebSocketFrameAggregator. max-frame-size))
      (n/pp->next pp "WSACC" "wsock" ws-monolith<>)

      (n/remove-handler* pp
                              [HttpContentDecompressor
                               HttpContentCompressor
                               CorsHandler
                               ChunkedWriteHandler "h1" cn])
      (n/dbg-pipeline pp)
      (c/debug "morphed server pipeline into websock pipline - ok.")
      (n/fire-msg ctx mock))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- clear-adder!

  [ctx]

  (let [impl (c/mdel! (n/cache?? ctx) :adder)]
    (c/try!
      (some-> (c/cast? Attribute impl) .release))
    (c/try!
      (some-> (c/cast? InterfaceHttpPostRequestDecoder impl) .destroy))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- assert-decoded-ok!

  "Make sure message is structurally ok."
  [ctx msg]

  (when (n/decoder-err? msg)
    (->> (n/scode* BAD_REQUEST)
         (n/reply-status ctx))
    (some-> (n/decoder-err-cause?? msg) (c/exception))
    (u/throw-FFE "bad request.")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- do-read-part

  [ctx ^HttpContent part]

  (let [end? (n/last-part? part)
        cc (n/cache?? ctx)
        impl (c/mget cc :adder)]
    (if false
      (c/debug "received%schunk: %s."
               (if end? " last " " ") part))
    (cond (n/ihprd? impl)
          (n/offer! impl part)
          (n/mp-attr? impl)
          (n/add->mp-attr! impl part end?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- do-last-part

  [ctx ^LastHttpContent part]

  (let [cc (n/cache?? ctx)
        ^HttpMessage
        msg (c/mget cc :msg)
        impl (c/mget cc :adder)
        body (if-not (n/ihprd? impl)
               (n/get-mp-attr impl)
               (n/parse-form-multipart impl))
        gist (do (.add (.headers msg)
                       (.trailingHeaders part))
                 (netty->ring msg ctx body))]
    ;(c/debug "gist = %s." (i/fmt->edn gist))
    (clear-adder! ctx)
    (c/mdel! cc :msg)
    gist))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- on-content

  [ctx part]

  (try
    (let [cc (n/cache?? ctx)
          cur (c/mget cc :msg)]
      (when-not (nil? cur)
        (assert-decoded-ok! ctx part)
        (do-read-part ctx part)))
    (catch Throwable e
      (or (c/is? FailFast e) (throw e)))
    (finally
      (n/ref-del part))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- do-100-cont

  [ctx req]

  (letfn
    [(cont-100? []
       (let [{:keys [max-msg-size]}
             (n/chcfg?? ctx)
             err? (and (pos? max-msg-size)
                       (HttpUtil/isContentLengthSet req)
                       (> (HttpUtil/getContentLength req) max-msg-size))]
         (-> (->> (if-not err?
                    expected-ok expected-failed)
                  (n/write-msg ctx))
             (n/cf-cb (if err? (n/cfop<z>))))
         (not err?)))]
    (if (and (HttpUtil/is100ContinueExpected req)
             (not (cont-100?)))
      (u/throw-FFE "failed 100 continue."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- do-cache-req

  [ctx req]

  (let [m (n/get-method req)
        post? (n/put-post? m)
        ct (->> (n/h1hdr* CONTENT_TYPE)
                (n/get-header req) c/lcase)
        ws? (and (= :get m)
                 (-> (->> (n/h1hdr* UPGRADE)
                          (n/get-header req) c/lcase)
                     (c/embeds? hd-websocket))
                 (-> (->> (n/h1hdr* CONNECTION)
                          (n/get-header req) c/lcase)
                     (c/embeds? hd-upgrade)))
        rc (cond (c/embeds? ct ct-form-url)
                 (if post? :post :url)
                 (and post?
                      (c/embeds? ct ct-form-mpart)) :multipart)
        {:keys [max-mem-size]}
        (n/chcfg?? ctx)
        cc (n/cache?? ctx)]
    (when ws?
      (c/mput! cc :mode :wsock)
      (c/debug "request is detected as a websock upgrade."))
    (c/mput! cc :msg req)
    (c/mput! cc :adder (if (c/or?? [= rc] :post :multipart)
                         (decoder<> ctx req)
                         (n/data-attr<> max-mem-size)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- do-cache-rsp

  [ctx msg]

  (let [{:keys [max-mem-size]}
        (n/chcfg?? ctx)
        cc (n/cache?? ctx)]
    (c/mput! cc :msg msg)
    (c/mput! cc :adder (n/data-attr<> max-mem-size))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- do-read-msg

  [ctx msg]
  (if (c/is? HttpContent msg) (on-content ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- on-response

  [ctx rsp]

  (try
    (assert-decoded-ok! ctx rsp)
    (do-cache-rsp ctx rsp)
    (do-read-msg ctx rsp)
    (catch Throwable e
      (n/ref-del rsp)
      (c/mdel! (n/cache?? ctx) :msg)
      (or (c/is? FailFast e) (throw e)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- on-request

  [ctx req]

  (try
    (c/debug "REQ: %s." req)
    (assert-decoded-ok! ctx req)
    (do-100-cont ctx req)
    (do-cache-req ctx req)
    (do-read-msg ctx req)
    (catch Throwable e
      (n/ref-del req)
      (some-> ctx
              n/cache??
              (c/mdel! :msg))
      (or (c/is? FailFast e) (throw e)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- read-complete

  [ctx part]

  (let [cc (n/cache?? ctx)
        msg (c/mget cc :msg)
        mode (c/mget cc :mode)
        gist (do-last-part ctx part)]
    (try
      (cors/cors-read ctx gist)
      (c/mput! cc :cur msg)
      (if (not= :wsock mode)
        (n/fire-msg ctx gist)
        (cfg-websock ctx msg))
      (catch Throwable e
        (or (c/is? FailFast e) (throw e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- on-read

  [ctx msg]

  (c/condp?? instance? msg
    HttpResponse (on-response ctx msg)
    HttpRequest (on-request ctx msg)
    HttpContent (on-content ctx msg))
  (let [cc (n/cache?? ctx)
        cur (c/mget cc :msg)]
    (when-not (nil? cur)
      (if (n/last-part? msg) (read-complete ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- !cont-100?

  [msg]

  `(not= HttpResponseStatus/CONTINUE
         (some-> (c/cast? FullHttpResponse ~msg) .status)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- on-write

  [ctx msg]

  (letfn
    [(delhead [^List q] (if-not
                          (.isEmpty q)
                          (.remove q 0)))]
    (when (and (!cont-100? msg)
               (n/h1end? msg))
      (c/debug "checking for pending requests.")
      ;just replied, next check to process queued requests
      (let [c (n/cache?? ctx)
            out (ArrayList.)
            q (c/mget c :queue)]
        ;clear flag
        (c/mput! c :cur nil)
        ;grab next request & its parts
        (loop [m (delhead q)]
          (cond
            (n/last-part? m) (.add out m) ;ok got one complete msg
            (nil? m) (.clear out) ;something is wrong
            :else (do (.add out m)
                      (recur (delhead q)))))
        (if-not (.isEmpty out)
          (c/debug "**dequeue** request %s." (u/objid?? (.get out 0))))
        ;process the queued request & its parts
        (loop [m (delhead out)]
          (when m
            (on-read ctx m)
            (recur (delhead out))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def

  ^{:tag ChannelHandler
    :doc "A Http 1.x handler without pipelining."}

  h1-simple<>

  (proxy [DuplexHandler][]
    (onHandlerAdded [ctx]
      (n/akey+ ctx n/cache-key (HashMap.)))
    (preWrite [ctx msg]
      (cors/cors-write ctx msg))
    (onRead [ctx ch msg]
      ;;(c/debug "onRead === msg = %s" msg)
      (if (n/h1msg? msg)
        (on-read ctx msg) (n/fire-msg ctx msg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def

  ^{:tag ChannelHandler
    :doc "A Http 1.x handler supporting pipelining."}

  h1-complex<>

  (proxy [DuplexHandler][]
    (onRead [ctx ch msg]
      (let [cc (n/cache?? ctx)
            cur (c/mget cc :cur)
            mode (c/mget cc :mode)
            queue (c/mget cc :queue)]
        (cond
          (some? cur)
          (when (not= :wsock mode)
            (.add ^List queue msg)
            (if (n/hreq? msg)
              (c/debug "**queue** request %s." (u/objid?? msg))))
          :else
          (if (n/h1msg? msg)
            (on-read ctx msg)
            (n/fire-msg ctx msg)))))
    (preWrite [ctx msg]
      (cors/cors-write ctx msg))
    (onWrite [ctx msg _]
      (on-write ctx msg))
    (onHandlerAdded [ctx]
      (doto (n/akey+ ctx
                     n/cache-key (HashMap.))
        (c/mput! :cur nil) (c/mput! :queue (ArrayList.))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-pipeline

  "Pipeline for http 1.x server."
  {:tag ChannelPipeline
   :arglists '([p args])}
  [p args]
  {:pre [(c/is? ChannelPipeline p)]}

  (let [{:keys [pipelining?
                cors-cfg user-cb]} args]
    (c/info "h1-pipelining mode = %s" (boolean pipelining?))
    (n/pp->last p "codec" (HttpServerCodec.))
    (if (not-empty cors-cfg)
      (n/akey+ (n/ch?? p)
               n/corscfg-key cors-cfg))
    (n/pp->last p
                "h1"
                (if pipelining? h1-complex<> h1-simple<>))
    (n/pp->last p "chunker" (ChunkedWriteHandler.))
    (n/pp->last p n/user-cb (n/app-handler user-cb))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

