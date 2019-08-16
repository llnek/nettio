;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty app - snoops on the request."
      :author "Kenneth Leung"}

  czlab.nettio.apps.snoop

  (:gen-class)

  (:require [czlab.basal.proc :as p]
            [czlab.basal.log :as l]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.str :as s]
            [czlab.convoy.core :as cc]
            [czlab.nettio.core :as nc]
            [czlab.nettio.server :as sv])

  (:import [io.netty.util Attribute AttributeKey CharsetUtil]
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
           [czlab.basal XData]
           [io.netty.bootstrap ServerBootstrap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(def ^:private keep-alive (nc/akey<> "keepalive"))
(def ^:private cookie-buf (nc/akey<> "cookies"))
(def ^:private msg-buf (nc/akey<> "msg"))
(defonce ^:private svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- write-reply
  "Reply back a string"
  [^ChannelHandlerContext ctx curObj]

  (let [cookies (:cookies curObj)
        buf (nc/get-akey ctx msg-buf)
        res (nc/http-reply<+>
              (nc/scode HttpResponseStatus/OK) (str buf) (.alloc ctx))
        hds (.headers res)
        ce ServerCookieEncoder/STRICT
        clen (-> (.content res) .readableBytes)]
    (.set hds "Content-Length" (str clen))
    (.set hds "Content-Type"
              "text/plain; charset=UTF-8")
    (.set hds
          "Connection"
          (if (nc/get-akey ctx keep-alive) "keep-alive" "close"))
    (if (empty? cookies)
      (doto hds
        (.add "Set-Cookie"
              (.encode ce "key1" "value1"))
        (.add "Set-Cookie"
              (.encode ce "key2" "value2")))
      (doseq [v cookies]
        (.add hds
              "Set-Cookie"
              (.encode ce (nc/netty-cookie<> v)))))
    (.writeAndFlush ctx res)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- handle-req
  "Introspect the inbound request"
  [^ChannelHandlerContext ctx req]
  (let [^HttpHeaders headers (:headers req)
        ka? (:is-keep-alive? req)
        buf (s/sbf<>)]
    (nc/set-akey ctx keep-alive ka?)
    (nc/set-akey ctx msg-buf buf)
    (s/sbf+ buf
            "WELCOME TO THE TEST WEB SERVER\r\n"
            "==============================\r\n"
            "VERSION: "
            (:version req)
            "\r\n"
            "HOSTNAME: "
            (str (cc/msg-header req "host"))
            "\r\n"
            "REQUEST_URI: "
            (:uri2 req)
            "\r\n\r\n"
            (s/sreduce<>
              #(s/sbf+ %1
                       "HEADER: "
                       %2
                       " = "
                       (cs/join "," (.getAll headers ^String %2))
                       "\r\n")
              (.names headers))
            "\r\n"
            (s/sreduce<>
              (fn [b ^Map$Entry en]
                (s/sbf+ b
                        "PARAM: "
                        (.getKey en)
                        " = "
                        (cs/join ","
                                 (.getValue en)) "\r\n"))
              (:parameters req))
            "\r\n")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- handle-cnt
  "Handle the request content"
  [^ChannelHandlerContext ctx msg]
  (let [^XData ct (:body msg)
        buf (nc/get-akey ctx msg-buf)]
    (if (.hasContent ct)
      (s/sbf+ buf "CONTENT: " (.strit ct) "\r\n"))
    (s/sbf+ buf "END OF CONTENT\r\n")
    (write-reply ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn snoop-httpd<>
  "Sample Snooper HTTPD." [& args]
   (apply sv/netty-web-server<> :hh1
                                 (proxy [InboundHandler][true]
                                   (readMsg [ctx msg]
                                     (handle-req ctx msg)
                                     (handle-cnt ctx msg))) args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn finz-server "" []
  (when @svr
    (sv/stop-server! @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main "" [& args]
  (cond
    (< (count args) 2)
    (println "usage: snoop host port")
    :else
    (let [w (sv/start-web-server!
              (snoop-httpd<>)
              {:host (nth args 0)
               :port (c/s->int (nth args 1) 8080)})]
      (p/exit-hook #(sv/stop-server! w))
      (reset! svr w)
      (u/block!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

