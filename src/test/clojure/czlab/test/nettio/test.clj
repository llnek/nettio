;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.nettio.test

  (:require [czlab.nettio.discarder :refer [discardHTTPD<>]]
            [czlab.nettio.filesvr :refer :all]
            [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [czlab.nettio.snooper :refer [snoopHTTPD<>]])

  (:use [czlab.nettio.aggh11]
        [czlab.convoy.routes]
        [czlab.nettio.http11]
        [czlab.nettio.core]
        [czlab.nettio.resp]
        [czlab.nettio.server]
        [czlab.nettio.client]
        [czlab.convoy.server]
        [czlab.convoy.core]
        [czlab.convoy.upload]
        [czlab.basal.process]
        [czlab.basal.meta]
        [czlab.basal.io]
        [czlab.basal.core]
        [czlab.basal.str]
        [clojure.test])

  (:import [io.netty.buffer Unpooled ByteBuf ByteBufHolder]
           [org.apache.commons.fileupload FileItem]
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
           [czlab.nettio
            WholeResponse
            WholeRequest
            WholeMessage
            H1DataFactory
            WSClientConnect
            ClientConnect
            InboundHandler]
           [io.netty.channel
            ChannelHandler
            Channel
            ChannelHandlerContext]
           [io.netty.channel.embedded EmbeddedChannel]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defobject HttpRequestMsgObj HttpMsgGist)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- mockRequest "" [ch w]
  (object<> HttpRequestMsgObj
            (merge
              (dftReqMsgObj)
              {:body (:body @w)
               :socket ch })))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- serverHandler<> "" []
  (proxy [InboundHandler][]
    (channelRead0 [ctx msg]
      (let [c (.getBytes ^XData (:body @msg))
            ch (ch?? ctx)
            r (httpFullReply<>
                (.code HttpResponseStatus/OK) c (.alloc ch))]
        (.writeAndFlush ^ChannelHandlerContext ctx r)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- bbuf "" ^ByteBuf
  [^Channel ch ^String s]
  (Unpooled/wrappedBuffer (bytesit s)))

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
            (strit (toByteArray (.content r1)))
            (strit (toByteArray (.content r2)))
            (strit (toByteArray (.content r3))))]
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
         (strit (toByteArray (.content b1)))
         (strit (toByteArray (.content b2))))]
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
        rc (deref po 3000 nil)]
    (stopServer ch)
    (and rc (== 0 (.size ^XData (:body @rc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testSnooper "" []
  (let [bs (snoopHTTPD<>)
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        po (h1get (str "http://"
                       lhost-name
                       ":5555/test/snooper?a=1&b=john%27smith"))
        rc (deref po 3000 nil)]
    (stopServer ch)
    (and rc (.hasContent ^XData (:body @rc)))))

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
        rc (deref po 5000 nil)]
    (stopServer ch)
    (and rc
         (> (.size ^XData (:body @rc)) 0)
         (= s (.strit ^XData (:body @rc))))))

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
        rc (deref po 5000 nil)
        des (io/file *tempfile-repo* tn)]
    (stopServer ch)
    (and rc
         (== 0 (.size ^XData (:body @rc)))
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
                       ch (.channel ctx)
                       ^XData b (:body @msg)
                       res (httpResult ch (mockRequest ch msg) nil)]
                   (reset! out (.content b))
                   (alterStateful res assoc :body "hello joe")
                   (replyResult ch res nil))))}))
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        po (h1post (str "http://" lhost-name ":5555/form")
                   "a=b&c=3%209&name=john%27smith"
                   {:headers {:content-type
                              "application/x-www-form-urlencoded"}})
        rc (deref po 5000 nil)
        rmap
        (when @out
          (preduce<map>
            #(let [^FileItem i %2]
               (if (.isFormField i)
                 (assoc! %1
                         (keyword (.getFieldName i))
                         (.getString i))
                 %1))
            (getAllItems @out)))]
    (stopServer ch)
    (and rc
         (= "hello joe" (.strit ^XData (:body @rc)))
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
                 (let [^XData b (:body @msg)]
                   (reset! out (.content b))
                   (replyStatus ctx 200))))}))
        ctype "multipart/form-data; boundary=---1234"
        cbody FORM-MULTIPART
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        po (h1post (str "http://" lhost-name ":5555/form")
                   cbody
                   {:headers {:content-type ctype }})
        rc (deref po 5000 nil)
        rmap
        (when @out
          (preduce<map>
            #(let [^FileItem i %2]
               (if (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getString i)))
                         (.getString i))
                 %1))
            (getAllItems @out)))
        fmap
        (when @out
          (preduce<map>
            #(let [^FileItem i %2]
               (if-not (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getName i)))
                         (strit (.get i)))
                 %1))
            (getAllItems @out)))]
    (stopServer ch)
    (and rc
         (== 0 (.size ^XData (:body @rc)))
         (= (:field+fieldValue rmap) "fieldValue")
         (= (:multi+value1 rmap) "value1")
         (= (:multi+value2 rmap) "value2")
         (= (:file1+foo1.tab fmap) "file content(1)\n")
         (= (:file2+foo2.tab fmap) "file content(2)\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFormUpload "" []
  (let [cbody (bytesit FORM-MULTIPART)
        gist {:ctype "multipart/form-data; boundary=---1234"
              :clen (alength cbody)}
        out (parseFormPost gist (xdata<> cbody))
        rmap
        (when out
          (preduce<map>
            #(let [^FileItem i %2]
               (if (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getString i)))
                         (.getString i))
                 %1))
            (getAllItems out)))
        fmap
        (when out
          (preduce<map>
            #(let [^FileItem i %2]
               (if-not (.isFormField i)
                 (assoc! %1
                         (keyword (str (.getFieldName i)
                                       "+" (.getName i)))
                         (strit (.get i)))
                 %1))
            (getAllItems out)))]
    (and (= (:field+fieldValue rmap) "fieldValue")
         (= (:multi+value1 rmap) "value1")
         (= (:multi+value2 rmap) "value2")
         (= (:file1+foo1.tab fmap) "file content(1)\n")
         (= (:file2+foo2.tab fmap) "file content(2)\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private ROUTES
  [{:XXXhandler "p1"
    :uri "/([^/]+)/(.*)"
    :verb :post
    :template  "t1.html"}
   {:mount "m1"
    :uri "/(favicon\\..+)"}
   {:XXXhandler "p2"
    :uri "/:a/([^/]+)/:b/c/:d"
    :verb :get
    :template  "t2.html"}
   {:mount "m2"
    :uri "/4"}])

(def ^:private SORTED-ROUTES (loadRoutes ROUTES))
(def ^:private RC (routeCracker<> SORTED-ROUTES))
;;(println "routes = " SORTED-ROUTES)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes1 "" []
  (let [rc (crackRoute RC {:method "post" :uri "/hello/world"})
        ^Matcher m (:matcher rc)
        r (:routeInfo rc)
        {:keys [groups places]}
        (collectInfo  r m)]
    (and (= "hello" (first groups))
         (= "world" (last groups))
         (empty? places))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes2 "" []
  (let [rc (crackRoute RC {:method "get" :uri "/favicon.hello"})
        ^Matcher m (:matcher rc)
        r (:routeInfo rc)
        {:keys [groups places]}
        (collectInfo  r m)]
    (and (= "favicon.hello" (first groups))
         (== 1 (count groups))
         (empty? places))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes3 "" []
  (let [rc (crackRoute RC {:method "get" :uri "/A/zzz/B/c/D"})
        ^Matcher m (:matcher rc)
        r (:routeInfo rc)
        {:keys [groups places] :as ccc}
        (collectInfo  r m)]
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
  (let [rc (crackRoute RC {:method "get" :uri "/4"})
        ^Matcher m (:matcher rc)
        r (:routeInfo rc)
        {:keys [groups places]}
        (collectInfo  r m)]
    (and (empty? groups)
         (empty? places))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testRoutes5 "" []
  (let [rc (crackRoute RC {:method "get" :uri "/1/1/1/1/1/1/14"})
        s (:status? rc)
        ^Matcher m (:matcher rc)
        r (:routeInfo rc)]
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
                 (let [^XData b (:body @msg)]
                   (replyStatus ctx 200))))}))
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        p (deref rc 3000 nil)]
    (stopServer ch)
    (and p (== 405 (:code (:status @p))))))

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
                 (let [^XData b (:body @msg)]
                   (replyStatus ctx 200))))}) args)
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        p (deref rc 3000 nil)]
    (stopServer ch)
    (and p (= "*" (msgHeader p "access-control-allow-origin")))))

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
                 (let [^XData b (:body @msg)]
                      (replyStatus ctx 200))))}) args)
        ch (startServer bs
                        {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        p (deref rc 3000 nil)]
    (stopServer ch)
    (and p (= o (msgHeader p "access-control-allow-origin")))))

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
    (and (ist? ClientConnect cc)
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
                 (println "Oh no! msg = " msg)))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host host})
        rcp (wsconnect<> host
                         port
                         "/websock"
                         (fn [_ _]))
        cc (deref rcp 3000 nil)]
    (stopServer ch)
    (ist? Throwable cc)))

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
                 (println "Why? msg = " msg)))}) args)
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
                       ^XData x (:body @msg)
                       m (TextWebSocketFrame. (.strit x))]
                   (.writeAndFlush ctx m))))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [^WSClientConnect cc msg]
                           (when-some [^XData s (:body msg)]
                             (reset! out (.strit s))
                             (.write cc (CloseWebSocketFrame.)))))
        cc (deref rcp 5000 nil)]
    (when-some [c (cast? WSClientConnect cc)]
      (.write c "hello"))
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
                       m (-> (byteBuf?? (:body @msg)
                                        (ch?? ctx))
                             (BinaryWebSocketFrame. ))]
                   (.writeAndFlush ctx m))))}) args)
        port 5556
        ch (startServer bs
                        {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [^WSClientConnect cc msg]
                           (when-some [^XData b (:body msg)]
                             (reset! out (.strit b))
                             (.write cc (CloseWebSocketFrame.)))))
        cc (deref rcp 5000 nil)]
    (when-some [c (cast? WSClientConnect cc)]
      (.write c (.getBytes "hello")))
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
                         (fn [^WSClientConnect cc msg]
                           (when (:pong? msg)
                             (reset! pong true)
                             (.write cc (CloseWebSocketFrame.)))))
        cc (deref rcp 5000 nil)]
    (when-some [c (cast? WSClientConnect cc)]
      (.write c (PingWebSocketFrame.)))
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
    (is (hasRoutes? RC))
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

  (is (testSnooper))
  (is (testDiscarder))

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


