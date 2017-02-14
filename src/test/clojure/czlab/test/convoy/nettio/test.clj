;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.convoy.nettio.test

  (:require [czlab.convoy.nettio.discarder :refer [discardHTTPD<>]]
            [czlab.convoy.nettio.filesvr :refer :all]
            [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [czlab.convoy.nettio.snooper :refer [snoopHTTPD<>]])

  (:use [czlab.convoy.nettio.aggregate]
        [czlab.convoy.net.routes]
        [czlab.convoy.nettio.http11]
        [czlab.convoy.nettio.core]
        [czlab.convoy.nettio.resp]
        [czlab.convoy.nettio.server]
        [czlab.convoy.nettio.client]
        [czlab.convoy.net.server]
        [czlab.convoy.net.core]
        [czlab.convoy.net.upload]
        [czlab.basal.process]
        [czlab.basal.meta]
        [czlab.basal.io]
        [czlab.basal.core]
        [czlab.basal.str]
        [clojure.test])

  (:import [czlab.convoy.net RouteCracker RouteInfo ULFormItems ULFileItem]
           [io.netty.buffer Unpooled ByteBuf ByteBufHolder]
           [io.netty.handler.codec.http.websocketx
            BinaryWebSocketFrame
            TextWebSocketFrame
            CloseWebSocketFrame
            PongWebSocketFrame
            PingWebSocketFrame]
           [io.netty.handler.codec.http.multipart
            HttpDataFactory
            Attribute
            HttpPostRequestDecoder]
           [io.netty.handler.codec.http
            HttpResponseStatus]
           [java.nio.charset Charset]
           [java.net URL URI]
           [czlab.jasal XData]
           [jregex Matcher]
           [czlab.convoy.nettio
            WholeResponse
            WholeRequest
            WholeMessage
            H1DataFactory
            ClientConnect
            InboundHandler]
           [io.netty.channel
            ChannelHandler
            Channel
            ChannelHandlerContext]
           [czlab.convoy.net HttpRequest]
           [io.netty.channel.embedded EmbeddedChannel]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- mockRequest "" [ch ^WholeRequest w]
  (reify HttpRequest
    (cookie [_ n] )
    (cookies [_] )
    (body [_] (.content w))
    (msgGist [_] (.msgGist w))
    (localAddr [_] )
    (localHost [_] )
    (localPort [_] 0)
    (remoteAddr [_] )
    (remoteHost [_] )
    (remotePort [_] 0)
    (serverName [_] )
    (serverPort [_] 0)
    (scheme [_] )
    (isSSL [_] false)
    (session [_] )
    (socket [_] ch)
    (routeGist [_] )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- serverHandler<> "" []
  (proxy [InboundHandler][]
    (channelRead0 [ctx msg]
      (assert (inst? WholeRequest msg))
      (let [^WholeRequest req msg
            c (.. req content getBytes)
            ch (ch?? ctx)
            gist (.msgGist req)
            r (httpFullReply<>
                (.code HttpResponseStatus/OK) c (.alloc ch))]
        (. ^ChannelHandlerContext ctx writeAndFlush r)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- bbuf "" ^ByteBuf
  [^Channel ch ^String s]
  (Unpooled/wrappedBuffer (bytesify s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testPipelining "" []
  (let [ec (EmbeddedChannel.
             #^"[Lio.netty.channel.ChannelHandler;"
             (vargs* ChannelHandler
                     (h1reqAggregator<>)
                     (h1reqHandler<>)
                     (serverHandler<>)))
        dfac (H1DataFactory. 1000000)
        r3 (httpPost<+> "/r3" (bbuf ec "r3"))
        r2 (httpPost<+> "/r2" (bbuf ec "r2"))
        r1 (httpPost<+> "/r1" (bbuf ec "r1"))]
    ;;(.set (.headers r2) "expect" "100-continue")
    (.set (.attr ec dfac-key) dfac)
    (.writeOneInbound ec r1)
    (.writeOneInbound ec r2)
    (.writeOneInbound ec r3)
    (.flushInbound ec)
    (pause 1000)
    (.flushOutbound ec)
    (let [q (.outboundMessages ec)
          ^ByteBufHolder r1 (.poll q)
          ^ByteBufHolder r2 (.poll q)
          ^ByteBufHolder r3 (.poll q)
          r4 (.poll q)
          rc
          (str
            (stringify (toByteArray (.content r1)))
            (stringify (toByteArray (.content r2)))
            (stringify (toByteArray (.content r3))))]
      (.close ec)
      (and (nil? r4)
           (= "r1r2r3" rc)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testPipe "" [pipe?]
  (let [ec (EmbeddedChannel.
             #^"[Lio.netty.channel.ChannelHandler;"
             (vargs* ChannelHandler
                     (h1reqAggregator<> pipe?)
                     (h1reqHandler<>)
                     (serverHandler<>)))
        dfac (H1DataFactory. 1000000)
        outq (.outboundMessages ec)
        r2 (httpPost<+> "/r2" (bbuf ec "r2"))
        r1 (httpPost<+> "/r1" (bbuf ec "r1"))]
    (.set (.attr ec dfac-key) dfac)
    (doseq [p [r1 r2]]
      (.writeOneInbound ec p)
      (.flushInbound ec)
      (pause 1000)
      (.flushOutbound ec))
    (let
      [^ByteBufHolder b1 (.poll outq)
       ^ByteBufHolder b2 (.poll outq)
       rc
       (str
         (stringify (toByteArray (.content b1)))
         (stringify (toByteArray (.content b2))))]
      (.close ec)
      (= "r1r2" rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testDiscarder "" []
  (let [bs (discardHTTPD<> rand)
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        po (h1get (str "http://"
                       lhost-name
                       ":5555/test/discarder?a=1&b=john%27smith"))
        ^WholeResponse rc (deref po 3000 nil)]
    (stopServer ch)
    (and rc (== 0 (.. rc content size)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testSnooper "" []
  (let [bs (snoopHTTPD<>)
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        po (h1get (str "http://"
                       lhost-name
                       ":5555/test/snooper?a=1&b=john%27smith"))
        ^WholeResponse rc (deref po 3000 nil)]
    (stopServer ch)
    (and rc (.. rc content hasContent))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFileSvrGet "" []
  (let [bs (memFileServer<> *tempfile-repo*)
        s "test content"
        tn "testget.txt"
        port 5555
        _ (spit (io/file *tempfile-repo* tn) s)
        ch (startServer bs
                        {:port port
                         :host lhost-name})
        po (h1get (format "http://%s:%d/%s" lhost-name port tn))
        ^WholeResponse rc (deref po 5000 nil)]
    (stopServer ch)
    (and rc
         (> (.. rc content size) 0)
         (= s (.. rc content stringify)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFileSvrPut "" []
  (let [bs (memFileServer<> *tempfile-repo*)
        src (tempFile)
        s "test content"
        tn "testput.txt"
        port 5555
        _ (deleteQ (io/file *tempfile-repo* tn))
        _ (spit src s)
        ch (startServer bs
                        {:port port
                         :host lhost-name})
        po (h1post (format "http://%s:%d/%s"
                           lhost-name port tn) src)
        ^WholeResponse rc (deref po 5000 nil)
        des (io/file *tempfile-repo* tn)]
    (stopServer ch)
    (and rc
         (== 0 (.. rc content size))
         (.exists des)
         (= s (slurp des)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFormPost "" []
  (let [out (atom nil)
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (let [^ChannelHandlerContext ctx ctx
                       ^WholeRequest msg msg
                       ch (.channel ctx)
                       b (.content msg)
                       g (.msgGist msg)
                       res (httpResult<> (mockRequest ch msg))]
                   (reset! out (.content b))
                   (.setContent res "hello joe")
                   (replyResult res))))}))
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        po (h1post (str "http://" lhost-name ":5555/form")
                   "a=b&c=3%209&name=john%27smith"
                   {:headers {:content-type
                              "application/x-www-form-urlencoded"}})
        ^WholeResponse rc (deref po 5000 nil)
        rmap
        (when @out
          (preduce<map>
            #(let [^ULFileItem i %2]
               (if (.isFormField i)
                 (assoc! %1
                         (keyword (.getFieldName i))
                         (.getString i))
                 %1))
            (.intern ^ULFormItems @out)))]
    (stopServer ch)
    (and rc
         (= "hello joe" (.. rc content stringify))
         (= (:a rmap) "b")
         (= (:c rmap) "3 9")
         (= (:name rmap) "john'smith"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private
  FORM-MULTIPART
  (str "-----1234\r\n"
             "Content-Disposition: form-data; name=\"file1\"; filename=\"foo1.tab\"\r\n"
             "Content-Type: text/plain\r\n"
             "\r\n"
             "file content(1)\n"
             "\r\n"
             "-----1234\r\n"
             "Content-Disposition: form-data; name=\"file2\"; filename=\"foo2.tab\"\r\n"
             "Content-Type: text/plain\r\n"
             "\r\n"
             "file content(2)\n"
             "\r\n"
             "-----1234\r\n"
             "Content-Disposition: form-data; name=\"field\"\r\n"
             "\r\n"
             "fieldValue\r\n"
             "-----1234\r\n"
             "Content-Disposition: form-data; name=\"multi\"\r\n"
             "\r\n"
             "value1\r\n"
             "-----1234\r\n"
             "Content-Disposition: form-data; name=\"multi\"\r\n"
             "\r\n"
             "value2\r\n"
             "-----1234--\r\n"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFormMultipart "" []
  (let [out (atom nil)
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (let [^WholeRequest msg msg
                       b (.content msg)]
                   (reset! out (.content b))
                   (replyStatus ctx 200))))}))
        ctype "multipart/form-data; boundary=---1234"
        cbody FORM-MULTIPART
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        po (h1post (str "http://" lhost-name ":5555/form")
                   cbody
                   {:headers {:content-type ctype }})
        ^WholeResponse rc (deref po 5000 nil)
        rmap
        (when @out
          (preduce<map>
            #(let [^ULFileItem i %2]
               (if (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getString i)))
                         (.getString i))
                 %1))
            (.intern ^ULFormItems @out)))
        fmap
        (when @out
          (preduce<map>
            #(let [^ULFileItem i %2]
               (if-not (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getName i)))
                         (stringify (.get i)))
                 %1))
            (.intern ^ULFormItems @out)))]
    (stopServer ch)
    (and rc
         (== 0 (.. rc content size))
         (= (:field+fieldValue rmap) "fieldValue")
         (= (:multi+value1 rmap) "value1")
         (= (:multi+value2 rmap) "value2")
         (= (:file1+foo1.tab fmap) "file content(1)\n")
         (= (:file2+foo2.tab fmap) "file content(2)\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFormUpload "" []
  (let [cbody (bytesify FORM-MULTIPART)
        gist {:ctype "multipart/form-data; boundary=---1234"
              :clen (alength cbody)}
        out (parseFormPost gist (xdata<> cbody))
        rmap
        (when out
          (preduce<map>
            #(let [^ULFileItem i %2]
               (if (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getString i)))
                         (.getString i))
                 %1))
            (.intern out)))
        fmap
        (when out
          (preduce<map>
            #(let [^ULFileItem i %2]
               (if-not (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getName i)))
                         (stringify (.get i)))
                 %1))
            (.intern out)))]
    (and (= (:field+fieldValue rmap) "fieldValue")
         (= (:multi+value1 rmap) "value1")
         (= (:multi+value2 rmap) "value2")
         (= (:file1+foo1.tab fmap) "file content(1)\n")
         (= (:file2+foo2.tab fmap) "file content(2)\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private ROUTES
  [{:handler "p1"
    :uri "/([^/]+)/(.*)"
    :verb :post
    :template  "t1.html"}
   {:mount "m1"
    :uri "/(favicon\\..+)"}
   {:handler "p2"
    :uri "/:a/([^/]+)/:b/c/:d"
    :verb :get
    :template  "t2.html"}
   {:mount "m2"
    :uri "/4"}])

(def ^:private SORTED-ROUTES (loadRoutes ROUTES))
(def ^:private ^RouteCracker RC (routeCracker<> SORTED-ROUTES))
;;(println "routes = " SORTED-ROUTES)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes1 "" []
  (let [rc (.crack RC {:method "post" :uri "/hello/world"})
        ^Matcher m (:matcher rc)
        ^RouteInfo r (:routeInfo rc)
        {:keys [groups places]}
        (.collect  r m)]
    (and (= "hello" (first groups))
         (= "world" (last groups))
         (empty? places))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes2 "" []
  (let [rc (.crack RC {:method "get" :uri "/favicon.hello"})
        ^Matcher m (:matcher rc)
        ^RouteInfo r (:routeInfo rc)
        {:keys [groups places]}
        (.collect  r m)]
    (and (= "favicon.hello" (first groups))
         (== 1 (count groups))
         (empty? places))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes3 "" []
  (let [rc (.crack RC {:method "get" :uri "/A/zzz/B/c/D"})
        ^Matcher m (:matcher rc)
        ^RouteInfo r (:routeInfo rc)
        {:keys [groups places]}
        (.collect  r m)]
    (and (= "A" (nth groups 0))
         (= "zzz" (nth groups 1))
         (= "B" (nth groups 2))
         (= "D" (nth groups 3))
         (= "A" (get places "a"))
         (= "B" (get places "b"))
         (= "D" (get places "d"))
         (== 3 (count places)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes4 "" []
  (let [rc (.crack RC {:method "get" :uri "/4"})
        ^Matcher m (:matcher rc)
        ^RouteInfo r (:routeInfo rc)
        {:keys [groups places]}
        (.collect  r m)]
    (and (empty? groups)
         (empty? places))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes5 "" []
  (let [rc (.crack RC {:method "get" :uri "/1/1/1/1/1/1/14"})
        s (:status? rc)
        ^Matcher m (:matcher rc)
        ^RouteInfo r (:routeInfo rc)]
    (and (false? s)
         (nil? m)
         (nil? r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testPreflightNotAllowed "" []
  (let [o (str "http://" lhost-name)
        port 5555
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (let [^WholeRequest msg msg
                       b (.content msg)]
                   (replyStatus ctx 200))))}))
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        ^WholeResponse p (deref rc 3000 nil)]
    (stopServer ch)
    (and p (== 405 (:code (:status (.msgGist p)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testPreflightStar "" []
  (let [o (str "http://" lhost-name)
        port 5555
        args
        {:corsCfg {:enabled? true
                   :anyOrigin? true}}
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (let [^WholeRequest msg msg
                       b (.content msg)]
                   (replyStatus ctx 200))))}) args)
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        ^WholeResponse p (deref rc 3000 nil)]
    (stopServer ch)
    (and p (= "*" (getHeader p "access-control-allow-origin")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testPreflight "" []
  (let [o (str "http://" lhost-name)
        port 5555
        args
        {:corsCfg {:enabled? true
                   :anyOrigin? true
                   :nullable? false
                   :credentials? true}}
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (let [^WholeRequest msg msg
                       b (.content msg)]
                      (replyStatus ctx 200))))}) args)
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        ^WholeResponse p (deref rc 3000 nil)]
    (stopServer ch)
    (and p (= o (getHeader p "access-control-allow-origin")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockClose "" []
  (let [args {:wsockPath "/web/sock"}
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (println "msg = " msg)))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [_ _]))
        cc (deref rcp 5000 nil)]
    (when-some [c (cast? ClientConnect cc)] (.dispose c))
    (pause 1000)
    (stopServer ch)
    (and (inst? ClientConnect cc)
         (not (.isOpen (.channel ^ClientConnect cc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockBad "" []
  (let [args {:wsockPath #{"/web/sock"}}
        host lhost-name
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (println "msg = " msg)))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host host})
        rcp (wsconnect<> host
                         port
                         "/websock"
                         (fn [_ _]))
        cc (deref rcp 3000 nil)]
    (stopServer ch)
    (inst? Throwable cc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsock "" []
  (let [args {:wsockPath #{"/web/sock"}}
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (println "msg = " msg)))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [_ _]))
        cc (deref rcp 5000 nil)]
    (stopServer ch)
    (and cc (== 5556 (.port ^ClientConnect cc)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockText "" []
  (let [args {:wsockPath "/web/sock"}
        out (atom nil)
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (let [^ChannelHandlerContext ctx ctx
                       ^TextWebSocketFrame msg msg]
                   (.writeAndFlush ctx (.retain msg)))))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [^Channel ch msg]
                           (when (inst? TextWebSocketFrame msg)
                             (reset! out
                                     (.text ^TextWebSocketFrame msg))
                             (.writeAndFlush ch (CloseWebSocketFrame.)))))
        cc (deref rcp 5000 nil)]
    (when-some [c (cast? ClientConnect cc)]
      (.writeAndFlush (.channel c) (TextWebSocketFrame. "hello")))
    (pause 1000)
    (stopServer ch)
    (= "hello" @out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockBlob "" []
  (let [args {:wsockPath "/web/sock"}
        out (atom nil)
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (let [^ChannelHandlerContext ctx ctx
                       ^BinaryWebSocketFrame msg msg]
                   (.writeAndFlush ctx (.retain msg)))))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [^Channel ch msg]
                           (when-some
                             [b (cast? BinaryWebSocketFrame msg)]
                             (reset! out
                                     (stringify
                                       (toByteArray (.content b))))
                             (.writeAndFlush ch
                                             (CloseWebSocketFrame.)))))
        buf (Unpooled/copiedBuffer "hello" (Charset/forName "utf-8"))
        cc (deref rcp 5000 nil)]
    (when-some [c (cast? ClientConnect cc)]
      (.writeAndFlush (.channel c)
                      (BinaryWebSocketFrame. buf)))
    (pause 1000)
    (stopServer ch)
    (= "hello" @out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockPing "" []
  (let [args {:wsockPath #{"/web/sock"}}
        pong (atom false)
        out (atom nil)
        bs
        (createServer<>
          :netty/http
          (fn [_]
            {:h1
             (proxy [InboundHandler][]
               (channelRead0 [ctx msg]
                 (reset! out "bad")))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [ch msg]
                           (when (inst? PongWebSocketFrame msg)
                             (reset! pong true)
                             (.writeAndFlush ^Channel
                                             ch
                                             (CloseWebSocketFrame.)))))
        cc (deref rcp 5000 nil)]
    (when-some [c (cast? ClientConnect cc)]
      (.writeAndFlush (.channel c)
                      (PingWebSocketFrame.)))
    (pause 1000)
    (stopServer ch)
    (and (nil? @out)
         (true? @pong))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(deftest czlabtestconvoynettio-test

  (testing
    "related to: routes"
    (is (> (count SORTED-ROUTES) 0))
    (is (.hasRoutes RC))
    (is (testRoutes1))
    (is (testRoutes2))
    (is (testRoutes3))
    (is (testRoutes4))
    (is (testRoutes5)))

  (testing
    "related to: web sockets"
    (is (testWebsockBad))
    (is (testWebsock))
    (is (testWebsockClose))
    (is (testWebsockText))
    (is (testWebsockBlob))
    (is (testWebsockPing)))

  (testing
    "related to: http1.1 pipelining"
    (is (testPipelining))
    (is (testPipe false))
    (is (testPipe true)))

  (is (testDiscarder))
  (is (testSnooper))

  (is (testFileSvrGet))
  (is (testFileSvrPut))

  (testing
    "related to: form post"
    (is (testFormPost))
    (is (testFormMultipart))
    (is (testFormUpload)))

  (testing
    "related to: CORS pre-flight"
    (is (testPreflightNotAllowed))
    (is (testPreflight)))

  (is (string? "That's all folks!")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


