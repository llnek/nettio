;; Copyright ©  2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.test.nettio.core

  (:require [czlab.nettio.apps.discard :as dc]
            [czlab.nettio.apps.snoop :as sn]
            [czlab.nettio.apps.files :as fs]
            [clojure.string :as cs]
            [clojure.java.io :as io]
            [czlab.nettio.msgs :as mg]
            [czlab.convoy.routes :as cr]
            [czlab.nettio.http11 :as h1]
            [czlab.nettio.ranges :as nr]
            [czlab.nettio.core :as nc]
            [czlab.nettio.resp :as rs]
            [czlab.nettio.server :as sv]
            [czlab.nettio.client :as cl]
            [czlab.convoy.core :as cc]
            [czlab.convoy.upload :as cu]
            [czlab.basal.proc :as p]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.str :as s]
            [clojure.test :as ct]
            [czlab.basal.core
             :refer [ensure?? ensure-thrown??] :as c])

  (:import [io.netty.buffer Unpooled ByteBuf ByteBufHolder]
           [org.apache.commons.fileupload FileItem]
           [czlab.basal XData]
           [czlab.nettio.client NettyClientModule]
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
           [jregex Matcher]
           [czlab.nettio
            H1DataFactory
            InboundHandler]
           [io.netty.channel
            ChannelHandler
            Channel
            ChannelHandlerContext]
           [io.netty.channel.embedded EmbeddedChannel]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private
  _file-content_ (str "hello how are you, "
                      "are you doing ok? " "very cool!"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- bbuf ^ByteBuf [_ s] (Unpooled/wrappedBuffer (i/x->bytes s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- server-handler<> []
  (proxy [InboundHandler][true]
    (readMsg [ctx msg]
      (let [c (.getBytes ^XData (:body msg))
            ch (nc/ch?? ctx)
            r (nc/http-reply<+>
                (nc/scode HttpResponseStatus/OK) c (.alloc ch))]
        (.writeAndFlush ^ChannelHandlerContext ctx r)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-core

  (ensure?? "file-range"
            (let [des (io/file i/*tempfile-repo* (u/jid<>))
                  _ (spit des _file-content_)
                  w
                  (sv/netty-web-server<>
                    {:hh1
                     (fn [ctx msg]
                       (let [ch (nc/ch?? ctx)
                             res (cc/http-result msg)]
                         (cc/res-header-set res
                                            "content-type" "text/plain")
                         (cc/reply-result (assoc res :body des))))})
                  w (sv/start-web-server! w {:port 5555 :host nc/lhost-name})
                  po (cl/h1get (str "http://" nc/lhost-name ":5555/range")
                               {:headers {:range "bytes=0-"}})
                  rc (deref po 5000 nil)
                  b (if rc (:body rc))
                  s (if b (.strit ^XData b))]
              (sv/stop-server! w)
              (u/pause 1000)
              (and (s/hgl? s) (= 0 (s/count-str s nr/DEF-BD)))))

  (ensure?? "file-range"
            (let [des (io/file i/*tempfile-repo* (u/jid<>))
                  _ (spit des _file-content_)
                  w (sv/netty-web-server<>
                      {:hh1
                       (fn [ctx msg]
                         (let [ch (nc/ch?? ctx)
                               res (cc/http-result msg)]
                           (cc/res-header-set res "content-type" "text/plain")
                           (cc/reply-result (assoc res :body des))))})
                  _ (sv/start-web-server! w {:port 5555 :host nc/lhost-name})
                  po (cl/h1get (str "http://" nc/lhost-name ":5555/range")
                               {:headers {:range "bytes=0-18,8-20,21-"}})
                  rc (deref po 5000 nil)
                  b (if rc (:body rc))
                  s (if b (.strit ^XData b))]
              (sv/stop-server! w)
              (u/pause 1000)
              (and (s/hgl? s)
                   (= 2 (s/count-str s nr/DEF-BD)))))

  (ensure?? "ssl/h2"
            (let [out (atom nil)
                  w (sv/netty-web-server<>
                      {:server-key "*"
                       :passwd ""
                       :hh2
                       (fn [^ChannelHandlerContext ctx msg]
                         (let [ch (nc/ch?? ctx)
                               rsp (nc/http-reply<+> 200
                                                     (i/x->bytes "hello")
                                                     (.alloc ch))]
                           (.writeAndFlush ctx rsp)))})
                  _ (sv/start-web-server!
                      w {:port 8443 :host nc/lhost-name})
                  po (cl/h2get (str "https://"
                                    nc/lhost-name
                                    ":8443/form")
                               {:server-cert "*"})
                  rc (deref po 5000 nil)
                  s (and rc
                         (c/is? XData (:body rc))
                         (.strit ^XData (:body rc)))]
              (sv/stop-server! w)
              (u/pause 1000)
              (= "hello" s)))

  (ensure?? "ssl/h1"
            (let [w (sv/netty-web-server<>
                      {:server-key "*"
                       :passwd  ""
                       :hh1
                       (fn [ctx msg]
                         (let [ch (nc/ch?? ctx)
                               b (:body msg)
                               res (cc/http-result msg)]
                           (cc/reply-result
                             (assoc res :body "hello joe"))))})
                  _ (sv/start-web-server!
                      w {:port 8443 :host nc/lhost-name})
                  po (cl/h1get (str "https://"
                                    nc/lhost-name ":8443/form")
                               {:server-cert "*"})
                  rc (deref po 5000 nil)]
              (sv/stop-server! w)
              (u/pause 1000)
              (and rc (= "hello joe" (.strit ^XData (:body rc))))))

  (ensure?? "websock/bad"
            (let [args {:wsock-path #{"/web/sock"}
                        :hh1 (fn [_ msg] (println "Oh no! msg = " msg))}
                  w (sv/netty-web-server<> args)
                  host nc/lhost-name
                  port 5556
                  _ (sv/start-web-server! w {:port port :host host})
                  rcp (cc/ws-connect<> (NettyClientModule.)
                                       host port "/websock" (fn [_ _]))
                  cc (cc/cc-sync-get-connect rcp)]
              (sv/stop-server! w)
              (u/pause 1000)
              (c/is? Throwable cc)))

  (ensure?? "websock/remote-port"
            (let [args {:wsock-path #{"/web/sock"}
                        :hh1 (fn [_ msg] (println "Why? msg = " msg))}
                  w (sv/netty-web-server<> args)
                  port 5556
                  _ (sv/start-web-server!
                      w {:port port :host nc/lhost-name})
                  rcp (cc/ws-connect<> (NettyClientModule.)
                                       nc/lhost-name port "/web/sock" (fn [_ _]))
                  cc (cc/cc-sync-get-connect rcp)]
              (sv/stop-server! w)
              (if cc (cc/cc-finz cc))
              (u/pause 1000)
              (and cc (= 5556 (cc/cc-remote-port cc)))))

  (ensure?? "websock/stop"
            (let [args {:wsock-path "/web/sock"
                        :hh1 (fn [_ msg] (println "msg = " msg))}
                  w (sv/netty-web-server<> args)
                  port 5556
                  _ (sv/start-web-server!
                      w {:port port :host nc/lhost-name})
                  rcp (cc/ws-connect<> (NettyClientModule.)
                                       nc/lhost-name
                                       port "/web/sock" (fn [_ _]))
                  cc (cc/cc-sync-get-connect rcp)]
              (if cc (cc/cc-finz cc))
              (sv/stop-server! w)
              (u/pause 1000)
              (and (c/is? czlab.convoy.core.ClientConnect cc)
                   (not (.isOpen ^Channel (cc/cc-channel cc))))))

  (ensure?? "websock/text"
            (let [args {:server-key "*"
                        :wsock-path "/web/sock"
                        :hh1
                        (fn [ctx msg]
                          (let [^XData x (:body msg)
                                m (TextWebSocketFrame. (.strit x))]
                            (.writeAndFlush ^ChannelHandlerContext ctx m)))}
                  w (sv/netty-web-server<> args)
                  port 8443
                  out (atom nil)
                  _ (sv/start-web-server! w {:port port :host nc/lhost-name})
                  rcp (cc/ws-connect<> (NettyClientModule.)
                                       nc/lhost-name
                                       port
                                       "/web/sock"
                                       (fn [cc msg]
                                         (when-some [s (:body msg)]
                                           (reset! out (.strit ^XData s))
                                           (cc/ws-write-msg cc (CloseWebSocketFrame.))))
                                       {:server-cert "*"})
                  cc (cc/cc-sync-get-connect rcp)]
              (some-> cc (cc/ws-write-msg  "hello"))
              (u/pause 1000)
              (if cc (cc/cc-finz cc))
              (sv/stop-server! w)
              (u/pause 1000)
              (= "hello" @out)))

  (ensure?? "websock/blob"
            (let [args {:wsock-path "/web/sock"
                        :hh1
                        (fn [ctx msg]
                          (let [m (-> (nc/bbuf?? (:body msg)
                                                    (nc/ch?? ctx))
                                      BinaryWebSocketFrame. )]
                            (.writeAndFlush ^ChannelHandlerContext ctx m)))}
                  w (sv/netty-web-server<> args)
                  out (atom nil)
                  port 5556
                  _ (sv/start-web-server!
                      w {:port port :host nc/lhost-name})
                  rcp (cc/ws-connect<> (NettyClientModule.)
                                       nc/lhost-name
                                       port
                                       "/web/sock"
                                       (fn [cc msg]
                                         (when-some [b (:body msg)]
                                           (reset! out (.strit ^XData b))
                                           (cc/ws-write-msg cc (CloseWebSocketFrame.)))))
                  cc (cc/cc-sync-get-connect rcp)]
              (some-> cc (cc/ws-write-msg  (i/x->bytes "hello")))
              (u/pause 1000)
              (if cc (cc/cc-finz cc))
              (sv/stop-server! w)
              (u/pause 1000)
              (= "hello" @out)))

  (ensure?? "websock/ping"
            (let [pong (atom false)
                  out (atom nil)
                  args {:wsock-path #{"/web/sock"}
                        :hh1
                        (fn [ctx msg] (reset! out "bad"))}
                  w (sv/netty-web-server<> args)
                  port 5556
                  _ (sv/start-web-server!
                      w {:port port :host nc/lhost-name})
                  rcp (cc/ws-connect<> (NettyClientModule.)
                                       nc/lhost-name port "/web/sock"
                                       (fn [cc msg]
                                         (when (:pong? msg)
                                           (reset! pong true)
                                           (cc/ws-write-msg cc (CloseWebSocketFrame.)))))
                  cc (cc/cc-sync-get-connect rcp)]
              (some-> cc (cc/ws-write-msg (PingWebSocketFrame.)))
              (u/pause 1000)
              (if cc (cc/cc-finz cc))
              (sv/stop-server! w)
              (u/pause 1000)
              (and (nil? @out) (true? @pong))))

  (ensure?? "pipeline"
            (let [ec (EmbeddedChannel.
                       #^"[Lio.netty.channel.ChannelHandler;"
                       (c/vargs* ChannelHandler
                                 (mg/h1req-aggregator<>)
                                 (h1/h1req-handler<>)
                                 (server-handler<>)))
                  dfac (H1DataFactory. 1000000)
                  r3 (nc/http-post<+> "/r3" (bbuf ec "r3"))
                  r2 (nc/http-post<+> "/r2" (bbuf ec "r2"))
                  r1 (nc/http-post<+> "/r1" (bbuf ec "r1"))]
              ;;(.set (.headers r2) "expect" "100-continue")
              (.set (.attr ec nc/dfac-key) dfac)
              (.writeOneInbound ec r1)
              (.writeOneInbound ec r2)
              (.writeOneInbound ec r3)
              (.flushInbound ec)
              (u/pause 1000)
              (.flushOutbound ec)
              (let [q (.outboundMessages ec)
                    ^ByteBufHolder r1 (.poll q)
                    ^ByteBufHolder r2 (.poll q)
                    ^ByteBufHolder r3 (.poll q)
                    r4 (.poll q)
                    rc (str (i/x->str (nc/to-byte-array (.content r1)))
                            (i/x->str (nc/to-byte-array (.content r2)))
                            (i/x->str (nc/to-byte-array (.content r3))))]
                (.close ec)
                (u/pause 1000)
                (and (nil? r4)
                     (= "r1r2r3" rc)))))

  (ensure?? "pipe"
            (let [sum (atom 0)]
              (dotimes [i 2]
                (let [ec (EmbeddedChannel.
                           #^"[Lio.netty.channel.ChannelHandler;"
                           (c/vargs* ChannelHandler
                                     (mg/h1req-aggregator<> (= i 0))
                                     (h1/h1req-handler<>)
                                     (server-handler<>)))
                      dfac (H1DataFactory. 1000000)
                      outq (.outboundMessages ec)
                      r2 (nc/http-post<+> "/r2" (bbuf ec "r2"))
                      r1 (nc/http-post<+> "/r1" (bbuf ec "r1"))]
                  (.set (.attr ec nc/dfac-key) dfac)
                  (doseq [p [r1 r2]]
                    (.writeOneInbound ec p)
                    (.flushInbound ec)
                    (u/pause 1000)
                    (.flushOutbound ec))
                  (let [^ByteBufHolder b1 (.poll outq)
                        ^ByteBufHolder b2 (.poll outq)
                        rc (str (i/x->str (nc/to-byte-array (.content b1)))
                                (i/x->str (nc/to-byte-array (.content b2))))]
                    (.close ec)
                    (if (= "r1r2" rc) (swap! sum inc)))))
              (u/pause 1000)
              (= 2 @sum)))

  (ensure?? "snoop-httpd<>"
            (let [w (sn/snoop-httpd<>)
                  _ (sv/start-web-server!
                      w {:port 5555 :host nc/lhost-name})
                  po (cl/h1get (str "http://"
                                    nc/lhost-name
                                    ":5555/test/snooper?a=1&b=john%27smith"))
                  {:keys [body]} (deref po 3000 nil)
                  rc (if body (.strit ^XData body))]
              (sv/stop-server! w)
              (u/pause 1000)
              (s/hgl? rc)))

  (ensure?? "discard-httpd<>"
            (let [w (dc/discard-httpd<> rand)
                  _ (sv/start-web-server!
                      w {:port 5555 :host nc/lhost-name})
                  po (cl/h1get (str "http://"
                                    nc/lhost-name
                                    ":5555/test/discarder?a=1&b=john%27smith"))
                  {:keys [body] :as rc} (deref po 3000 nil)]
              (sv/stop-server! w)
              (u/pause 1000)
              (zero? (if body (.size ^XData body) -1))))

  (ensure?? "mem-file-server/get"
            (let [w (fs/mem-file-server<> i/*tempfile-repo*)
                  s "test content"
                  tn (u/jid<>)
                  port 5555
                  _ (spit (io/file i/*tempfile-repo* tn) s)
                  _ (sv/start-web-server!
                      w {:port port :host nc/lhost-name})
                  po (cl/h1get (format "http://%s:%d/%s" nc/lhost-name port tn))
                  {:keys [body]} (deref po 5000 nil)]
              (sv/stop-server! w)
              (u/pause 1000)
              (and body
                   (pos? (.size ^XData body))
                   (= s (.strit ^XData body)))))

  (ensure?? "mem-file-server/put"
            (let [w (fs/mem-file-server<> i/*tempfile-repo*)
                  src (i/temp-file)
                  s "test content"
                  tn (u/jid<>)
                  port 5555
                  _ (spit src s)
                  _ (sv/start-web-server!
                      w {:port port :host nc/lhost-name})
                  po (cl/h1post (format "http://%s:%d/%s"
                                        nc/lhost-name port tn) src)
                  {:keys [body]} (deref po 5000 nil)
                  des (io/file i/*tempfile-repo* tn)]
              (sv/stop-server! w)
              (u/pause 1000)
              (and body
                   (zero? (.size ^XData body))
                   (.exists des)
                   (= s (slurp des)))))

  (ensure?? "form-post"
            (let [out (atom nil)
                  w (sv/netty-web-server<>
                      {:hh1 (fn [ctx msg]
                              (let [ch (nc/ch?? ctx)
                                    b (:body msg)
                                    res (cc/http-result msg)]
                                (reset! out (.content ^XData b))
                                (cc/reply-result
                                  (assoc res :body "hello joe"))))})
                  _ (sv/start-web-server!
                      w {:port 5555 :host nc/lhost-name})
                  po (cl/h1post (str "http://" nc/lhost-name ":5555/form")
                                "a=b&c=3%209&name=john%27smith"
                                {:headers {:content-type
                                           "application/x-www-form-urlencoded"}})
                  {:keys [body]} (deref po 5000 nil)
                  rmap (when @out
                         (c/preduce<map>
                           #(let [^FileItem i %2]
                              (if (.isFormField i)
                                (assoc! %1
                                        (keyword (.getFieldName i))
                                        (.getString i))
                                %1))
                           (cu/get-all-items @out)))]
              (sv/stop-server! w)
              (u/pause 1000)
              (and body
                   (= "hello joe" (.strit ^XData body))
                   (= (:a rmap) "b")
                   (= (:c rmap) "3 9")
                   (= (:name rmap) "john'smith"))))

  (ensure?? "form-port/multipart"
            (let [out (atom nil)
                  w (sv/netty-web-server<>
                      {:hh1 (fn [ctx msg]
                              (let [b (:body msg)]
                                (reset! out (.content ^XData b))
                                (nc/reply-status ctx 200)))})
                  ctype "multipart/form-data; boundary=---1234"
                  cbody cu/TEST-FORM-MULTIPART
                  _ (sv/start-web-server!
                      w {:port 5555 :host nc/lhost-name})
                  po (cl/h1post (str "http://" nc/lhost-name ":5555/form")
                                cbody {:headers {:content-type ctype }})
                  {:keys [body]} (deref po 5000 nil)
                  rmap (when @out
                         (c/preduce<map>
                           #(let [^FileItem i %2]
                              (if (.isFormField i)
                                (assoc! %1
                                        (keyword (str (.getFieldName i)
                                                      "+" (.getString i)))
                                        (.getString i))
                                %1))
                           (cu/get-all-items @out)))
                  fmap (when @out
                         (c/preduce<map>
                           #(let [^FileItem i %2]
                              (if-not (.isFormField i)
                                (assoc! %1
                                        (keyword (str (.getFieldName i)
                                                      "+" (.getName i)))
                                        (i/x->str (.get i)))
                                %1))
                           (cu/get-all-items @out)))]
              (sv/stop-server! w)
              (u/pause 1000)
              (and body
                   (zero? (.size ^XData body))
                   (= (:field+fieldValue rmap) "fieldValue")
                   (= (:multi+value1 rmap) "value1")
                   (= (:multi+value2 rmap) "value2")
                   (= (:file1+foo1.tab fmap) "file content(1)\n")
                   (= (:file2+foo2.tab fmap) "file content(2)\n"))))

  (ensure?? "preflight-not-allowed"
            (let [o (str "http://" nc/lhost-name)
                  port 5555
                  w (sv/netty-web-server<>
                      {:hh1 (fn [ctx msg]
                              (let [b (:body msg)]
                                (nc/reply-status ctx 200)))})
                  _ (sv/start-web-server!
                      w {:port 5555 :host nc/lhost-name})
                  args {:headers {:origin o
                                  :Access-Control-Request-Method "PUT"
                                  :Access-Control-Request-Headers "X-Custom-Header"}}
                  rc (cl/h1send (format "http://%s:%d/cors" nc/lhost-name port)
                                "OPTIONS" nil args)
                  p (deref rc 3000 nil)]
              (sv/stop-server! w)
              (u/pause 1000)
              (and p (= 405 (:code (:status p))))))

  (ensure?? "preflight"
            (let [o (str "http://" nc/lhost-name)
                  port 5555
                  args {:cors-cfg {:enabled? true
                                   :any-origin? true
                                   :nullable? false
                                   :credentials? true}
                        :hh1 (fn [ctx msg]
                               (let [b (:body msg)]
                                 (nc/reply-status ctx 200)))}
                  w (sv/netty-web-server<> args)
                  _ (sv/start-web-server!
                      w {:port 5555 :host nc/lhost-name})
                  args {:headers {:origin o
                                  :Access-Control-Request-Method "PUT"
                                  :Access-Control-Request-Headers "X-Custom-Header"}}
                  rc (cl/h1send (format "http://%s:%d/cors" nc/lhost-name port)
                                "OPTIONS" nil args)
                  p (deref rc 3000 nil)]
              (sv/stop-server! w)
              (u/pause 1000)
              (and p (= o (cc/msg-header p "access-control-allow-origin")))))

  (ensure?? "test-end" (= 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest ^:test-core basal-test-core
  (ct/is (let [[ok? r]
               (c/runtest test-core "test-core")] (println r) ok?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


