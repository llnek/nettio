;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty app - snoops on the request."
      :author "Kenneth Leung"}

  czlab.nettio.snoop

  (:gen-class)

  (:require [czlab.basal.process :as p :refer [exitHook]]
            [czlab.basal.log :as log]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.str :as s]
            [czlab.convoy.core :as cc]
            [czlab.nettio.core :as nc]
            [czlab.nettio.server :as sv])

  (:import [io.netty.util Attribute AttributeKey CharsetUtil]
           [czlab.nettio.server NettyWebServer]
           [czlab.nettio InboundHandler]
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
           [czlab.jasal LifeCycle CU XData]
           [io.netty.bootstrap ServerBootstrap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(def ^:private keep-alive (nc/akey<> "keepalive"))
(def ^:private cookie-buf (nc/akey<> "cookies"))
(def ^:private msg-buf (nc/akey<> "msg"))
(defonce ^:private svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- writeReply
  "Reply back a string"
  [^ChannelHandlerContext ctx curObj]

  (let [cookies (:cookies curObj)
        buf (nc/getAKey ctx msg-buf)
        res (nc/httpFullReply<>
              (nc/scode HttpResponseStatus/OK) (str buf) (.alloc ctx))
        hds (.headers res)
        ce ServerCookieEncoder/STRICT
        clen (-> (.content res) .readableBytes)]
    (.set hds "Content-Length" (str clen))
    (.set hds "Content-Type"
              "text/plain; charset=UTF-8")
    (.set hds
          "Connection"
          (if (nc/getAKey ctx keep-alive) "keep-alive" "close"))
    (if (empty? cookies)
      (doto hds
        (.add "Set-Cookie"
              (.encode ce "key1" "value1"))
        (.add "Set-Cookie"
              (.encode ce "key2" "value2")))
      (doseq [v cookies]
        (.add hds
              "Set-Cookie"
              (.encode ce (nc/nettyCookie<> v)))))
    (.writeAndFlush ctx res)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- handleReq
  "Introspect the inbound request"
  [^ChannelHandlerContext ctx req]
  (let [^HttpHeaders headers (:headers req)
        ka? (:isKeepAlive? req)
        buf (s/strbf<>)]
    (nc/setAKey ctx keep-alive ka?)
    (nc/setAKey ctx msg-buf buf)
    (doto buf
      (.append "WELCOME TO THE TEST WEB SERVER\r\n")
      (.append "==============================\r\n")
      (.append "VERSION: ")
      (.append (:version req))
      (.append "\r\n")
      (.append "HOSTNAME: ")
      (.append (str (cc/msgHeader req "host")))
      (.append "\r\n")
      (.append "REQUEST_URI: ")
      (.append (:uri2 req))
      (.append "\r\n\r\n"))
    (->>
      (c/sreduce<>
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
      (c/sreduce<>
        (fn [memo ^Map$Entry en]
          (-> ^StringBuilder
              memo
              (.append "PARAM: ")
              (.append (.getKey en))
              (.append " = ")
              (.append (cs/join "," (.getValue en)))
              (.append "\r\n")))
        (:parameters req))
      (.append buf))
    (.append buf "\r\n")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- handleCnt
  "Handle the request content"
  [^ChannelHandlerContext ctx msg]

  (let [^StringBuilder buf (nc/getAKey ctx msg-buf)
        ^XData ct (:body msg)]
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
  "Sample Snooper HTTPD"

  ([] (snoopHTTPD<> nil))
  ([args]
   (c/do-with [^LifeCycle w (c/mutable<> NettyWebServer)]
     (.init w
            (merge
              args
              {:hh1
               (proxy [InboundHandler][true]
                 (readMsg [ctx msg]
                   (handleReq ctx msg)
                   (handleCnt ctx msg)))})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn finzServer "" []
  (.stop ^LifeCycle @svr) (reset! svr nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -main "" [& args]

  (cond
    (< (count args) 2)
    (println "usage: snoop host port")
    :else
    (let [^LifeCycle w (snoopHTTPD<>)]
      (.start w {:host (nth args 0)
                 :port (c/convInt (nth args 1) 8080)})
      (p/exitHook #(.stop w))
      (reset! svr w)
      (CU/block))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

