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

  czlab.test.nettio.wsock

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
(c/deftest test-wsock

  (ensure?? "websock/bad"
            (let [w (-> (sv/web-server<>
                          {:wsock-path #{"/web/sock"}
                           :user-cb (fn [_ msg] (println "Oh no! msg = " msg))})
                        (po/start {:port 5556 :host n/lhost-name}))
                  rcp (cc/ws-connect<> (cl/netty-module<>)
                                       n/lhost-name port "/websock" (fn [_ _]))
                  cc (cc/cc-sync-get-connect rcp)]
              (po/stop w)
              (u/pause 1000)
              (c/is? Throwable cc)))

  (ensure?? "websock/remote-port"
            (let [w (-> (sv/web-server<>
                          {:wsock-path #{"/web/sock"}
                           :user-cb (fn [_ msg] (println "Why? msg = " msg))})
                        (po/start {:port 5556 :host n/lhost-name}))
                  rcp (cc/ws-connect<> (cl/netty-module<>)
                                       n/lhost-name port "/web/sock" (fn [_ _]))
                  cc (cc/cc-sync-get-connect rcp)]
              (po/stop w)
              (some-> cc cc/cc-finz)
              (u/pause 1000)
              (and cc (== 5556 (cc/cc-remote-port cc)))))

  (ensure?? "websock/stop"
            (let [w (-> (sv/web-server<>
                          {:wsock-path "/web/sock"
                           :hh1 (fn [_ msg] (println "msg = " msg))})
                        (po/start {:port 5556 :host n/lhost-name}))
                  rcp (cc/ws-connect<> (cl/netty-module<>)
                                       n/lhost-name
                                       port "/web/sock" (fn [_ _]))
                  cc (cc/cc-sync-get-connect rcp)]
              (po/stop w)
              (u/pause 1000)
              (try (and (c/is? czlab.niou.core.ClientConnect cc)
                        (not (.isOpen ^Channel (cc/cc-channel cc))))
                   (finally
                     (some-> cc cc/cc-finz)))))

  (ensure?? "websock/text"
            (let [w (-> (sv/web-server<>
                          {:wsock-path "/web/sock"
                           :server-key "*"
                           :user-cb (fn [ctx msg]
                                      (let [^XData x (:body msg)]
                                        (n/write-msg ctx
                                                     (TextWebSocketFrame. (.strit x)))))})
                        (po/start {:port 8443 :host n/lhost-name}))
                  out (atom nil)
                  rcp (cc/ws-connect<> (cl/netty-module<>)
                                       (:host w)
                                       (:port w)
                                       "/web/sock"
                                       (fn [cc msg]
                                         (when-some [s (:body msg)]
                                           (reset! out (.strit ^XData s))
                                           (cc/cc-ws-write cc (CloseWebSocketFrame.))))
                                       {:server-cert "*"})
                  cc (cc/cc-sync-get-connect rcp)]
              (some-> cc (cc/cc-ws-write  "hello"))
              (u/pause 1000)
              (some-> cc cc/cc-finz)
              (po/stop w)
              (u/pause 1000)
              (= "hello" @out)))

  (ensure?? "websock/blob"
            (let [w (-> (sv/web-server<>
                          {:wsock-path "/web/sock"
                           :user-cb (fn [ctx msg]
                                      (n/write-msg ctx
                                                   (BinaryWebSocketFrame.
                                                     (-> (n/bbuf?? (:body msg)
                                                                   (nc/ch?? ctx))))))})
                        (po/start {:port 5556 :host n/lhost-name}))
                  out (atom nil)
                  rcp (cc/ws-connect<> (cl/netty-module<>)
                                       (:host w)
                                       (:port w)
                                       "/web/sock"
                                       (fn [cc msg]
                                         (when-some [b (:body msg)]
                                           (reset! out (.strit ^XData b))
                                           (cc/cc-ws-write cc (CloseWebSocketFrame.)))))
                  cc (cc/cc-sync-get-connect rcp)]
              (some-> cc (cc/cc-ws-write (i/x->bytes "hello")))
              (u/pause 1000)
              (some-> cc cc/cc-finz)
              (po/stop w)
              (u/pause 1000)
              (= "hello" @out)))

  (ensure?? "websock/ping"
            (let [pong (atom false)
                  out (atom nil)
                  w (-> (sv/web-server<>
                          {:wsock-path #{"/web/sock"}
                           :user-cb (fn [ctx msg] (reset! out "bad"))})
                        (po/start {:port 5556 :host n/lhost-name}))
                  rcp (cc/ws-connect<> (cl/netty-module<>)
                                       (:host w)
                                       (:port w)
                                       "/web/sock"
                                       (fn [cc msg]
                                         (when (:pong? msg)
                                           (reset! pong true)
                                           (cc/cc-ws-write cc (CloseWebSocketFrame.)))))
                  cc (cc/cc-sync-get-connect rcp)]
              (some-> cc (cc/cc-ws-write (PingWebSocketFrame.)))
              (u/pause 1000)
              (some-> cc cc/cc-finz)
              (po/stop w)
              (u/pause 1000)
              (and (nil? @out) (true? @pong))))

  (ensure?? "test-end" (= 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-wsock nettio-test-wsock
  (ct/is (c/clj-test?? test-wsock)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


