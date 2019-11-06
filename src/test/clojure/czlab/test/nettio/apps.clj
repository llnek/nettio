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

  czlab.test.nettio.apps

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
(c/deftest test-apps

  (ensure?? "snoop-httpd<>"
            (let [w (-> (sn/snoop-httpd<>)
                        (po/start {:port 5555 :host n/lhost-name}))
                  po (cc/h1get (cl/netty-module<>)
                               (str "http://"
                                    n/lhost-name
                                    ":5555/test/snooper?a=1&b=john%27smith"))
                  {:keys [body]} (deref po 3000 nil)
                  rc (if body (.strit ^XData body))]
              (po/stop w)
              (u/pause 1000)
              (try (c/hgl? rc)
                   (finally (po/finz w)))))

  (ensure?? "discard-httpd<>"
            (let [w (dc/discard-httpd<> rand)
                  _ (po/start
                      w {:port 5555 :host nc/lhost-name})
                  po (cc/h1get (cl/netty-module<>)
                               (str "http://" nc/lhost-name ":5555/test/discarder?a=1&b=john%27smith"))
                  {:keys [body] :as rc} (deref po 3000 nil)]
              (po/stop w)
              (u/pause 1000)
              (zero? (if body (.size ^XData body) -1))))

  (ensure?? "mem-file-server/get"
            (let [w (fs/mem-file-server<> i/*tempfile-repo*)
                  s "test content"
                  tn (u/jid<>)
                  port 5555
                  _ (spit (i/tmpfile tn) s)
                  _ (po/start
                      w {:port port :host nc/lhost-name})
                  po (cc/h1get (cl/netty-module<>)
                               (format "http://%s:%d/%s" nc/lhost-name port tn))
                  {:keys [body]} (deref po 5000 nil)]
              (po/stop w)
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
                  _ (po/start
                      w {:port port :host nc/lhost-name})
                  po (cc/h1post (cl/netty-module<>)
                                (format "http://%s:%d/%s"
                                        nc/lhost-name port tn) src)
                  {:keys [body]} (deref po 5000 nil)
                  des (i/tmpfile tn)]
              (po/stop w)
              (u/pause 1000)
              (and body
                   (zero? (.size ^XData body))
                   (.exists des)
                   (= s (slurp des)))))

  (ensure?? "test-end" (= 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-apps nettio-test-apps
  (ct/is (c/clj-test?? test-apps)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


