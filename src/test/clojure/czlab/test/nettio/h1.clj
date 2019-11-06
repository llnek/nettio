;; Copyright ©  2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.test.nettio.h1

  (:require [clojure.java.io :as io]
            [clojure
             [test :as ct]
             [string :as cs]]
            [czlab.test.nettio
             [snoop :as sn]
             [files :as fs]
             [discard :as dc]]
            [czlab.nettio
             [http11 :as h1]
             [ranges :as nr]
             [core :as nc]
             [msgs :as mg]
             [resp :as rs]
             [server :as sv]
             [client :as cl]]
            [czlab.niou
             [core :as cc]
             [upload :as cu]
             [routes :as cr]]
            [czlab.basal
             [proc :as p]
             [util :as u]
             [log :as l]
             [io :as i]
             [xpis :as po]
             [core :as c :refer [ensure?? ensure-thrown??]]])

  (:import [io.netty.buffer Unpooled ByteBuf ByteBufHolder]
           [org.apache.commons.fileupload FileItem]
           [czlab.basal XData]
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
(c/def-
  _file-content_ (str "hello how are you, "
                      "are you doing ok? " "very cool!"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- bbuf
  ^ByteBuf [_ s] (Unpooled/wrappedBuffer (i/x->bytes s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- server-handler<>
  []
  (proxy [InboundHandler][true]
    (readMsg [ctx msg]
      (let [c (.getBytes ^XData (:body msg))
            ^Channel ch (nc/ch?? ctx)
            r (nc/http-reply<+>
                (.code HttpResponseStatus/OK) c (.alloc ch))]
        (.writeAndFlush ^ChannelHandlerContext ctx r)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-h1

  (ensure?? "ssl/h1"
            (let [w (sv/tcp-server<>
                      {:server-key "*"
                       :passwd  ""
                       :hh1 (fn [ctx msg]
                              (let [ch (nc/ch?? ctx)
                                    b (:body msg)
                                    res (cc/http-result msg)]
                                (cc/reply-result
                                  (assoc res :body "hello joe"))))})
                  _ (po/start w {:port 8443 :host nc/lhost-name})
                  po (cc/h1get (cl/netty-module<>)
                               (str "https://" nc/lhost-name ":8443/form")
                               {:server-cert "*"})
                  rc (deref po 5000 nil)]
              (po/stop w)
              (u/pause 1000)
              (and rc (= "hello joe" (.strit ^XData (:body rc))))))

  (ensure?? "pipeline"
            (let [ec (EmbeddedChannel.
                       #^"[Lio.netty.channel.ChannelHandler;"
                       (c/vargs* ChannelHandler
                                 (mg/h1req-aggregator<>)
                                 (h1/h1req-handler<>)
                                 (server-handler<>)))
                  dfac (H1DataFactory. 1000000)
                  r3 (cl/http-post<+> "/r3" (bbuf ec "r3"))
                  r2 (cl/http-post<+> "/r2" (bbuf ec "r2"))
                  r1 (cl/http-post<+> "/r1" (bbuf ec "r1"))]
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
                    rc (str (i/x->str (nc/bbuf->bytes (.content r1)))
                            (i/x->str (nc/bbuf->bytes (.content r2)))
                            (i/x->str (nc/bbuf->bytes (.content r3))))]
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
                      r2 (cl/http-post<+> "/r2" (bbuf ec "r2"))
                      r1 (cl/http-post<+> "/r1" (bbuf ec "r1"))]
                  (.set (.attr ec nc/dfac-key) dfac)
                  (doseq [p [r1 r2]]
                    (.writeOneInbound ec p)
                    (.flushInbound ec)
                    (u/pause 1000)
                    (.flushOutbound ec))
                  (let [^ByteBufHolder b1 (.poll outq)
                        ^ByteBufHolder b2 (.poll outq)
                        rc (str (i/x->str (nc/bbuf->bytes (.content b1)))
                                (i/x->str (nc/bbuf->bytes (.content b2))))]
                    (.close ec)
                    (if (= "r1r2" rc) (swap! sum inc)))))
              (u/pause 1000)
              (= 2 @sum)))

  (ensure?? "form-post"
            (let [out (atom nil)
                  w (sv/tcp-server<>
                      {:hh1 (fn [ctx msg]
                              (let [ch (nc/ch?? ctx)
                                    b (:body msg)
                                    res (cc/http-result msg)]
                                (reset! out (.content ^XData b))
                                (cc/reply-result
                                  (assoc res :body "hello joe"))))})
                  _ (po/start w {:port 5555 :host nc/lhost-name})
                  po (cc/h1post (cl/netty-module<>)
                                (str "http://" nc/lhost-name ":5555/form")
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
              (po/stop w)
              (u/pause 1000)
              (and body
                   (= "hello joe" (.strit ^XData body))
                   (= (:a rmap) "b")
                   (= (:c rmap) "3 9")
                   (= (:name rmap) "john'smith"))))

  (ensure?? "form-port/multipart"
            (let [out (atom nil)
                  w (sv/tcp-server<>
                      {:hh1 (fn [ctx msg]
                              (let [b (:body msg)]
                                (reset! out (.content ^XData b))
                                (nc/reply-status ctx 200)))})
                  ctype "multipart/form-data; boundary=---1234"
                  cbody cu/TEST-FORM-MULTIPART
                  _ (po/start
                      w {:port 5555 :host nc/lhost-name})
                  po (cc/h1post (cl/netty-module<>)
                                (str "http://" nc/lhost-name ":5555/form")
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
              (po/stop w)
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
                  w (sv/tcp-server<>
                      {:hh1 (fn [ctx msg]
                              (let [b (:body msg)]
                                (nc/reply-status ctx 200)))})
                  _ (po/start w {:port 5555 :host nc/lhost-name})
                  args {:headers {:origin o
                                  :Access-Control-Request-Method "PUT"
                                  :Access-Control-Request-Headers "X-Custom-Header"}}
                  rc (cc/h1send (cl/netty-module<>)
                                (format "http://%s:%d/cors" nc/lhost-name port)
                                "OPTIONS" nil args)
                  p (deref rc 3000 nil)]
              (po/stop w)
              (u/pause 1000)
              (and p (= 405 (:code (:status p))))))

  (ensure?? "preflight"
            (let [o (str "http://" nc/lhost-name)
                  port 5555
                  w (sv/tcp-server<>
                      {:cors-cfg {:enabled? true
                                  :any-origin? true
                                  :nullable? false
                                  :credentials? true}
                       :hh1 (fn [ctx msg]
                              (let [b (:body msg)]
                                (nc/reply-status ctx 200)))})
                  _ (po/start w {:port 5555 :host nc/lhost-name})
                  args {:headers {:origin o
                                  :Access-Control-Request-Method "PUT"
                                  :Access-Control-Request-Headers "X-Custom-Header"}}
                  rc (cc/h1send (cl/netty-module<>)
                                (format "http://%s:%d/cors" nc/lhost-name port)
                                "OPTIONS" nil args)
                  p (deref rc 3000 nil)]
              (po/stop w)
              (u/pause 1000)
              (and p (= o (cc/msg-header p "access-control-allow-origin")))))

  (ensure?? "test-end" (= 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-h1 nettio-test-h1
  (ct/is (c/clj-test?? test-h1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


