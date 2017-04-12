;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty app - snoops on the request."
      :author "Kenneth Leung"}

  czlab.convoy.nettio.snooper

  (:gen-class)

  (:require [czlab.basal.process :refer [exitHook]]
            [czlab.basal.logging :as log]
            [clojure.string :as cs])

  (:use [czlab.convoy.nettio.server]
        [czlab.basal.core]
        [czlab.basal.str]
        [czlab.convoy.net.server]
        [czlab.convoy.net.core]
        [czlab.convoy.nettio.core])

  (:import [io.netty.util Attribute AttributeKey CharsetUtil]
           [czlab.convoy.nettio InboundHandler]
           [java.util Map$Entry]
           [io.netty.channel
            ChannelInitializer
            Channel
            ChannelPipeline
            ChannelHandler
            ChannelHandlerContext]
           [io.netty.handler.codec.http.cookie
            CookieDecoder
            Cookie
            ServerCookieEncoder]
           [io.netty.handler.codec.http
            HttpHeaders
            HttpHeaderNames
            HttpHeaderValues
            HttpServerCodec
            HttpVersion
            FullHttpResponse
            HttpContent
            HttpResponseStatus
            HttpRequest
            QueryStringDecoder
            LastHttpContent]
           [czlab.jasal CU XData]
           [io.netty.bootstrap ServerBootstrap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

(def ^:private keep-alive (akey<> "keepalive"))
(def ^:private cookie-buf (akey<> "cookies"))
(def ^:private msg-buf (akey<> "msg"))
(defonce ^:private svrboot (atom nil))
(defonce ^:private svrchan (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- writeReply
  "Reply back a string"
  [^ChannelHandlerContext ctx curObj]

  (let [cookies (:cookies @curObj)
        buf (getAKey ctx msg-buf)
        res (httpFullReply<>
              (.code HttpResponseStatus/OK) (str buf) (.alloc ctx))
        hds (.headers res)
        ce ServerCookieEncoder/STRICT
        clen (-> (.content res) .readableBytes)]
    (.set hds "Content-Length" (str clen))
    (.set hds "Content-Type"
              "text/plain; charset=UTF-8")
    (.set hds "Connection" (if (getAKey ctx keep-alive) "keep-alive" "close"))
    (if (empty? cookies)
      (doto hds
        (.add "Set-Cookie"
              (.encode ce "key1" "value1"))
        (.add "Set-Cookie"
              (.encode ce "key2" "value2")))
      (doseq [v cookies]
        (.add hds
              "Set-Cookie"
              (.encode ce (nettyCookie<> v)))))
    (.writeAndFlush ctx res)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- handleReq
  "Introspect the inbound request"
  [^ChannelHandlerContext ctx req]

  (let [^HttpHeaders headers (:headers @req)
        ka? (:isKeepAlive? @req)
        buf (strbf<>)]
    (setAKey ctx keep-alive ka?)
    (setAKey ctx msg-buf buf)
    (doto buf
      (.append "WELCOME TO THE TEST WEB SERVER\r\n")
      (.append "==============================\r\n")
      (.append "VERSION: ")
      (.append (:version @req))
      (.append "\r\n")
      (.append "HOSTNAME: ")
      (.append (str (msgHeader req "host")))
      (.append "\r\n")
      (.append "REQUEST_URI: ")
      (.append (:uri2 @req))
      (.append "\r\n\r\n"))
    (->>
      (sreduce<>
        (fn [memo ^String n]
          (-> ^StringBuilder
              memo
              (.append "HEADER: ")
              (.append n)
              (.append " = ")
              (.append (cs/join "," (.getAll headers n)))
              (.append "\r\n")))
        (.names headers))
      (.append buf))
    (.append buf "\r\n")
    (->>
      (sreduce<>
        (fn [memo ^Map$Entry en]
          (-> ^StringBuilder
              memo
              (.append "PARAM: ")
              (.append (.getKey en))
              (.append " = ")
              (.append (cs/join "," (.getValue en)))
              (.append "\r\n")))
        (:parameters @req))
      (.append buf))
    (.append buf "\r\n")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- handleCnt
  "Handle the request content"
  [^ChannelHandlerContext ctx msg]

  (let [^StringBuilder buf (getAKey ctx msg-buf)
        ^XData ct (:body @msg)]
    (when (.hasContent ct)
      (-> buf
        (.append "CONTENT: ")
        (.append (.strit ct))
        (.append "\r\n")))
    (do
      (.append buf "END OF CONTENT\r\n")
      (writeReply ctx msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn snoopHTTPD<>
  "Sample Snooper HTTPD" {:tag ServerBootstrap}

  ([] (snoopHTTPD<> nil))
  ([args]
   (createServer<>
     :netty/http
     (fn [_]
       {:h1
        (proxy [InboundHandler][]
          (channelRead0 [ctx msg]
            (handleReq ctx msg)
            (handleCnt ctx msg)))}) args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn finzServer "" []
  (stopServer @svrchan)
  (reset! svrboot nil)
  (reset! svrchan nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -main "" [& args]

  (cond
    (< (count args) 2)
    (println "usage: snoop host port")
    :else
    (let [bs (snoopHTTPD<>)
          ch (startServer bs {:host (nth args 0)
                              :port (convInt (nth args 1) 8080)})]
      (exitHook #(stopServer ch))
      (reset! svrboot bs)
      (reset! svrchan ch)
      (CU/block))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

