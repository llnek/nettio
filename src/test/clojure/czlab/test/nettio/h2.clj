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

  czlab.test.nettio.h2

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
(c/deftest test-h2

  (ensure?? "ssl/h2"
            (let [w (sv/tcp-server<>
                      {:server-key "*"
                       :passwd ""
                       :hh2 (fn [ctx msg]
                              (let [^Channel ch (nc/ch?? ctx)
                                    rsp (nc/http-reply<+> 200
                                                          (i/x->bytes "hello")
                                                          (.alloc ch))]
                                (.writeAndFlush ^ChannelHandlerContext ctx rsp)))})
                  _ (po/start w {:port 8443 :host nc/lhost-name})
                  po (cc/h2get (cl/netty-module<>)
                               (str "https://" nc/lhost-name ":8443/form")
                               {:server-cert "*"})
                  rc (deref po 5000 nil)
                  s (and rc
                         (c/is? XData (:body rc))
                         (.strit ^XData (:body rc)))]
              (po/stop w)
              (u/pause 1000)
              (= "hello" s)))

  (ensure?? "test-end" (= 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-h2 nettio-test-h2
  (ct/is (c/clj-test?? test-h2)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


