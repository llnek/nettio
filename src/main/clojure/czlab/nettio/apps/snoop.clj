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
            [czlab.niou.core :as cc]
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
(c/def- keep-alive (nc/akey<> :keepalive))
(c/def- cookie-buf (nc/akey<> :cookies))
(c/def- msg-buf (nc/akey<> :msg))
(c/defonce- svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- write-reply
  "Reply back a string"
  [^ChannelHandlerContext ctx curObj]
  (let [cookies (:cookies curObj)
        buf (nc/get-akey msg-buf ctx)
        res (nc/http-reply<+>
              (nc/scode* OK) (str buf) (.alloc ctx))
        hds (.headers res)
        ce ServerCookieEncoder/STRICT
        clen (-> (.content res) .readableBytes)]
    (.set hds "Content-Length" (str clen))
    (.set hds "Content-Type"
              "text/plain; charset=UTF-8")
    (.set hds
          "Connection"
          (if (nc/get-akey keep-alive ctx) "keep-alive" "close"))
    (if (empty? cookies)
      (doto hds
        (.add "Set-Cookie"
              (.encode ce "key1" "value1"))
        (.add "Set-Cookie"
              (.encode ce "key2" "value2")))
      (doseq [v cookies]
        (.add hds
              "Set-Cookie"
              (.encode ce ^Cookie (nc/netty-cookie<> v)))))
    (.writeAndFlush ctx res)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- handle-req
  "Introspect the inbound request"
  [^ChannelHandlerContext ctx req]
  (let [^HttpHeaders headers (:headers req)
        ka? (:is-keep-alive? req)
        buf (c/sbf<>)]
    (nc/set-akey keep-alive ctx ka?)
    (nc/set-akey msg-buf ctx buf)
    (c/sbf+ buf
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
            (c/sreduce<>
              #(c/sbf+ %1
                       "HEADER: "
                       %2
                       " = "
                       (cs/join "," (.getAll headers ^String %2))
                       "\r\n")
              (.names headers))
            "\r\n"
            (c/sreduce<>
              (fn [b ^Map$Entry en]
                (c/sbf+ b
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
        buf (nc/get-akey msg-buf ctx)]
    (if (.hasContent ct)
      (c/sbf+ buf "CONTENT: " (.strit ct) "\r\n"))
    (c/sbf+ buf "END OF CONTENT\r\n")
    (write-reply ctx msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn snoop-httpd<>
  "Sample Snooper HTTPD." [& args]
   (sv/tcp-server<> (merge {:hh1
                            (proxy [InboundHandler][true]
                              (readMsg [ctx msg]
                                (handle-req ctx msg)
                                (handle-cnt ctx msg)))}
                           (c/kvs->map args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn finz-server
   [] (when @svr (sv/ns-stop! @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main
  [& args]
  (cond
    (< (count args) 2)
    (println "usage: snoop host port")
    :else
    (let [w (snoop-httpd<>)]
      (p/exit-hook #(sv/ns-stop! w))
      (reset! svr w)
      (sv/ns-start! w {:host (nth args 0)
                       :block? true
                       :port (c/s->int (nth args 1) 8080)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

