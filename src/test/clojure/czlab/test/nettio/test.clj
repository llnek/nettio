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
           [czlab.jasal LifeCycle XData]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- serverHandler<> "" []
  (proxy [InboundHandler][]
    (channelRead0 [ctx msg]
      (let [c (.getBytes ^XData (:body msg))
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
  (let [^LifeCycle w (discardHTTPD<> rand)
        _ (.start w {:port 5555 :host lhost-name})
        po (h1get (str "http://"
                       lhost-name
                       ":5555/test/discarder?a=1&b=john%27smith"))
        rc (deref po 3000 nil)
        _ (.stop w)]
    (and rc (== 0 (.size ^XData (:body rc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testSnooper "" []
  (let [^LifeCycle w (snoopHTTPD<>)
        _ (.start w {:port 5555 :host lhost-name})
        po (h1get (str "http://"
                       lhost-name
                       ":5555/test/snooper?a=1&b=john%27smith"))
        rc (deref po 3000 nil)
        _ (.stop w)]
    (and rc (.hasContent ^XData (:body rc)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFileSvrGet "" []
  (let [^LifeCycle w (memFileServer<> *tempfile-repo*)
        s "test content"
        tn "testget.txt"
        port 5555
        _ (spit (io/file *tempfile-repo* tn) s)
        _ (.start w {:port port :host lhost-name})
        po (h1get (format "http://%s:%d/%s" lhost-name port tn))
        rc (deref po 5000 nil)
        _ (.stop w)]
    (and rc
         (> (.size ^XData (:body rc)) 0)
         (= s (.strit ^XData (:body rc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFileSvrPut "" []
  (let [^LifeCycle w (memFileServer<> *tempfile-repo*)
        src (tempFile)
        s "test content"
        tn "testput.txt"
        port 5555
        _ (deleteQ (io/file *tempfile-repo* tn))
        _ (spit src s)
        _ (.start w {:port port :host lhost-name})
        po (h1post (format "http://%s:%d/%s"
                           lhost-name port tn) src)
        rc (deref po 5000 nil)
        des (io/file *tempfile-repo* tn)
        _ (.stop w)]
    (and rc
         (== 0 (.size ^XData (:body rc)))
         (.exists des)
         (= s (slurp des)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFormPost "" []
  (let [out (atom nil)
        w
        (-> {:hh1
             (fn [ctx msg]
               (let [ch (ch?? ctx)
                     ^XData b (:body msg)
                     res (http-result msg)]
                 (reset! out (.content b))
                 (->> (assoc res :body "hello joe")
                      (reply-result ))))}
            (nettyWebServer<>))
        _ (.start w {:port 5555 :host lhost-name})
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
            (get-all-items @out)))
        _ (.stop w)]
    (and rc
         (= "hello joe" (.strit ^XData (:body rc)))
         (= (:a rmap) "b")
         (= (:c rmap) "3 9")
         (= (:name rmap) "john'smith"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testFormMultipart "" []
  (let [out (atom nil)
        w
        (-> {:hh1
             (fn [ctx msg]
               (let [^XData b (:body msg)]
                 (reset! out (.content b))
                 (replyStatus ctx 200)))}
            (nettyWebServer<>))
        ctype "multipart/form-data; boundary=---1234"
        cbody TEST-FORM-MULTIPART
        _ (.start w {:port 5555 :host lhost-name})
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
            (get-all-items @out)))
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
            (get-all-items @out)))
        _ (.stop w)]
    (and rc
         (== 0 (.size ^XData (:body rc)))
         (= (:field+fieldValue rmap) "fieldValue")
         (= (:multi+value1 rmap) "value1")
         (= (:multi+value2 rmap) "value2")
         (= (:file1+foo1.tab fmap) "file content(1)\n")
         (= (:file2+foo2.tab fmap) "file content(2)\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testPreflightNotAllowed "" []
  (let [o (str "http://" lhost-name)
        port 5555
        w
        (-> {:hh1
             (fn [ctx msg]
               (let [^XData b (:body msg)]
                 (replyStatus ctx 200)))}
            (nettyWebServer<>))
        _ (.start w {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        p (deref rc 3000 nil)
        _ (.stop w)]
    (and p (== 405 (:code (:status p))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testPreflightStar "" []
  (let [o (str "http://" lhost-name)
        port 5555
        args
        {:corsCfg {:enabled? true
                   :anyOrigin? true}
         :hh1
         (fn [ctx msg]
           (let [^XData b (:body msg)]
             (replyStatus ctx 200)))}
        w (nettyWebServer<> args)
        _ (.start w {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        p (deref rc 3000 nil)
        _ (.stop w)]
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
                   :credentials? true}
         :hh1
         (fn [ctx msg]
           (let [^XData b (:body msg)]
             (replyStatus ctx 200)))}
        w (nettyWebServer<> args)
        _ (.start w {:port 5555 :host lhost-name})
        args {:headers
              {:origin o
               :Access-Control-Request-Method "PUT"
               :Access-Control-Request-Headers "X-Custom-Header"}}
        rc (h1send (format "http://%s:%d/cors" lhost-name port)
                   "OPTIONS" nil args)
        p (deref rc 3000 nil)
        _ (.stop w)]
    (and p (= o (msgHeader p "access-control-allow-origin")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockClose "" []
  (let [args
        {:wsockPath "/web/sock"
         :hh1
         (fn [ctx msg]
           (println "msg = " msg))}
        w (nettyWebServer<> args)
        port 5556
        _ (.start w {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [_ _]))
        cc (deref rcp 5000 nil)
        _ (when-some [c (cast? ClientConnect cc)] (.dispose c))
        _ (pause 1000)
        _ (.stop w)]
    (and (ist? ClientConnect cc)
         (not (.isOpen (.channel ^ClientConnect cc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockBad "" []
  (let [args
        {:wsockPath #{"/web/sock"}
         :hh1
         (fn [ctx msg]
           (println "Oh no! msg = " msg))}
        w (nettyWebServer<> args)
        host lhost-name
        port 5556
        _ (.start w {:port port :host host})
        rcp (wsconnect<> host
                         port
                         "/websock"
                         (fn [_ _]))
        cc (deref rcp 3000 nil)
        _ (.stop w)]
    (ist? Throwable cc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsock "" []
  (let [args
        {:wsockPath #{"/web/sock"}
         :hh1
         (fn [ctx msg]
           (println "Why? msg = " msg))}
        w (nettyWebServer<> args)
        port 5556
        _ (.start w {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [_ _]))
        cc (deref rcp 5000 nil)
        _ (.stop w)]
    (and cc (== 5556 (.port ^ClientConnect cc)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockText "" []
  (let [args
        {:serverKey "*"
         :wsockPath "/web/sock"
         :hh1
         (fn [ctx msg]
           (let [^XData x (:body msg)
                 m (TextWebSocketFrame. (.strit x))]
             (.writeAndFlush ^ChannelHandlerContext ctx m)))}
        w (nettyWebServer<> args)
        out (atom nil)
        port 8443
        _ (.start w {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [^WSClientConnect cc msg]
                           (when-some [^XData s (:body msg)]
                             (reset! out (.strit s))
                             (.write cc (CloseWebSocketFrame.))))
                         {:serverCert "*"})
        cc (deref rcp 5000 nil)
        _ (when-some [c (cast? WSClientConnect cc)] (.write c "hello"))
        _ (pause 1000)
        _ (.stop w)]
    (= "hello" @out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockBlob "" []
  (let [args
        {:wsockPath "/web/sock"
         :hh1
         (fn [ctx msg]
           (let [^ChannelHandlerContext ctx ctx
                 m (-> (byteBuf?? (:body msg)
                                  (ch?? ctx))
                       (BinaryWebSocketFrame. ))]
             (.writeAndFlush ctx m)))}
        w (nettyWebServer<> args)
        out (atom nil)
        port 5556
        _ (.start w {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [^WSClientConnect cc msg]
                           (when-some [^XData b (:body msg)]
                             (reset! out (.strit b))
                             (.write cc (CloseWebSocketFrame.)))))
        cc (deref rcp 5000 nil)
        _ (when-some [c (cast? WSClientConnect cc)]
            (.write c (.getBytes "hello")))
        _ (pause 1000)
        _ (.stop w)]
    (= "hello" @out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- testWebsockPing "" []
  (let [pong (atom false)
        out (atom nil)
        args
        {:wsockPath #{"/web/sock"}
         :hh1
         (fn [ctx msg]
           (reset! out "bad"))}
        w (nettyWebServer<> args)
        port 5556
        _ (.start w {:port port :host lhost-name})
        rcp (wsconnect<> lhost-name
                         port
                         "/web/sock"
                         (fn [^WSClientConnect cc msg]
                           (when (:pong? msg)
                             (reset! pong true)
                             (.write cc (CloseWebSocketFrame.)))))
        cc (deref rcp 5000 nil)
        _ (when-some [c (cast? WSClientConnect cc)]
            (.write c (PingWebSocketFrame.)))
        _ (pause 1000)
        _ (.stop w)]
    (and (nil? @out)
         (true? @pong))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- test-h1-SSL "" []
  (let [out (atom nil)
        w
        (nettyWebServer<>
          {:serverKey "*"
           :passwd  ""
           :hh1
           (fn [ctx msg]
             (let [ch (ch?? ctx)
                   ^XData b (:body msg)
                   res (http-result msg)]
               (reset! out (.content b))
               (->> (assoc res :body "hello joe")
                    (reply-result ))))})
        _ (.start w {:port 8443 :host lhost-name})
        po (h1get (str "https://"
                       lhost-name ":8443/form")
                  {:serverCert "*"})
        rc (deref po 5000 nil)
        _ (.stop w)]
    (and rc
         (= "hello joe" (.strit ^XData (:body rc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h2handle "" [ctx msg]
  (let []
    (log/debug "got h2 msg === %s" msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- test-h2-SSL "" []
  (let [out (atom nil)
        ;;w (nettyWebServer<> {:serverKey "*" :passwd  "" :hh2 h2handle})
        ;;_ (.start w {:port 8443 :host lhost-name})
        po (h2get (str "https://"
                       "www.google.com")
                       ;;lhost-name ":8443/form")
                  {:serverCert "*"})
        rc (deref po 5000 nil)
        s (and rc
               (ist? XData (:body rc))
               (.strit ^XData (:body rc)))]
    (hgl? s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(deftest czlabtestconvoynettio-test

  (testing
    "related to: SSL"
    (is (test-h2-SSL))
    (is (test-h1-SSL)))

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
    (is (testFormMultipart)))

  (testing
    "related to: CORS pre-flight"
    (is (testPreflightNotAllowed))
    (is (testPreflight)))

  (is (string? "That's all folks!")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


