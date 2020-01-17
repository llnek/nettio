(ns czlab.nettio.cors

  "Lifted from netty: package io.netty.handler.codec.http.cors.CorsHandler."

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.nettio.core :as n]
            [czlab.niou.core :as cc]
            [czlab.niou.upload :as cu]
            [czlab.niou.routes :as cr])

  (:import [io.netty.channel
            ChannelFuture
            ChannelFutureListener
            ChannelHandlerContext
            ChannelPromise
            ChannelDuplexHandler]
           [io.netty.handler.codec.http
            HttpHeaderNames
            HttpHeaderValues
            HttpHeaders
            HttpRequest
            HttpResponse
            HttpVersion
            HttpUtil
            DefaultFullHttpResponse]
           [io.netty.util.internal.logging
            InternalLogger;
            InternalLoggerFactory]
           [java.util List Collections]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^String NULL-ORIGIN "null")
(c/def- ^String ANY-ORIGIN "*")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-origin

  [^HttpResponse rsp ^String origin]

  (.set (.headers rsp)
        (n/h1hdr* ACCESS_CONTROL_ALLOW_ORIGIN) origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-preflight-headers

  "This is a non CORS specification feature which
  enables the setting of preflight
  response headers that might be required by intermediaries."
  [ctx ^HttpResponse rsp]

  (c/when-some+ [hds (:preflight-response-headers
                       (n/akey?? ctx n/corscfg-key))]
    (doseq [[k v] hds]
      (if (string? v)
        (.set (.headers rsp) ^String k ^String v)
        (.add (.headers rsp) ^String k ^Iterable v)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- echo-request-origin

  [rsp origin]

  (set-origin rsp origin))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-vary-header

  [^HttpResponse rsp]

  (.set (.headers rsp) (n/h1hdr* VARY) (n/h1hdr* ORIGIN)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-any-origin

  [rsp]

  (set-origin rsp ANY-ORIGIN))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-null-origin
  [rsp]
  (set-origin rsp NULL-ORIGIN))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-credentials

  [ctx ^HttpResponse rsp]

  (if (and (:allow-creds?
             (n/akey?? ctx n/corscfg-key))
           (not (.equals ANY-ORIGIN
                         (.get (.headers rsp)
                               (n/h1hdr* ACCESS_CONTROL_ALLOW_ORIGIN)))))
    (.set (.headers rsp)
          (n/h1hdr* ACCESS_CONTROL_ALLOW_CREDENTIALS) "true")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-expose-headers

  [ctx ^HttpResponse rsp]

  (c/when-some+ [v (:exposed-headers
                     (n/akey?? ctx n/corscfg-key))]
    (.set (.headers rsp)
          (n/h1hdr* ACCESS_CONTROL_EXPOSE_HEADERS) ^Iterable v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-methods

  [ctx ^HttpResponse rsp]

  (c/when-some+ [v (:allowed-methods
                     (n/akey?? ctx n/corscfg-key))]
    (.set (.headers rsp)
          (n/h1hdr* ACCESS_CONTROL_ALLOW_METHODS) ^Iterable v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-allow-headers

  [ctx ^HttpResponse rsp]

  (c/when-some+ [v (:allowed-headers
                     (n/akey?? ctx n/corscfg-key))]
    (.set (.headers rsp)
          (n/h1hdr* ACCESS_CONTROL_ALLOW_HEADERS) ^Iterable v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-max-age

  [ctx ^HttpResponse rsp]

  (c/if-number [x (:max-age (n/akey?? ctx n/corscfg-key))]
    (.set (.headers rsp)
          (n/h1hdr* ACCESS_CONTROL_MAX_AGE) x)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- set-origin?

  [ctx rsp]

  (when-some [config (n/akey?? ctx n/corscfg-key)]
    (c/when-some+ [origin (n/akey?? ctx n/origin-key)]
      (cond (and (.equals NULL-ORIGIN origin)
                 (:null-origin? config))
            (doto rsp set-null-origin)

            (:any-origin? config)
            (if (:allow-creds? config)
              (doto rsp
                (set-vary-header)
                (echo-request-origin origin))
              (doto rsp set-any-origin))

            (contains? (:origins config) origin)
            (doto rsp
              (set-origin origin) (set-vary-header))

            :else
            (c/do->nil
              (c/debug "request origin %s not configured." origin))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- respond

  [^ChannelHandlerContext ctx req ^HttpResponse rsp]

  (HttpUtil/setKeepAlive rsp (boolean (:keep-alive? req)))
  (let [cf (.writeAndFlush ctx rsp)]
    (if-not (:keep-alive? req)
      (.addListener cf ChannelFutureListener/CLOSE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- forbidden

  [^ChannelHandlerContext ctx req]

  (let [rsp (DefaultFullHttpResponse.
              (HttpVersion/valueOf ^String (:protocol req))
              (n/num->status 403))]
    (HttpUtil/setContentLength rsp 0)
    (respond ctx req rsp)
    (u/throw-FFE "forbidden request intercepted by CORS.")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- do-preflight

  [^ChannelHandlerContext ctx req]

  (let [rsp (DefaultFullHttpResponse.
              (HttpVersion/valueOf
                ^String (:protocol req))
              (n/num->status 200) true true)]

    (when (set-origin? ctx rsp)
      (set-allow-methods ctx rsp)
      (set-allow-headers ctx rsp)
      (set-allow-credentials ctx rsp)
      (set-max-age ctx rsp)
      (set-preflight-headers ctx rsp))

    (if-not
      (HttpUtil/isContentLengthSet rsp)
      (HttpUtil/setContentLength rsp 0))

    (respond ctx req rsp)
    (u/throw-FFE "handled CORS preflight request.")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- is-preflight?

  [req]
  (and (= :options (:request-method req))
       (cc/msg-header? req (str (n/h1hdr* ORIGIN)))
       (cc/msg-header? req (str (n/h1hdr* ACCESS_CONTROL_REQUEST_METHOD)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cors-read

  "Read a request, applying CORS."
  {:arglists '([ctx req])}
  [^ChannelHandlerContext ctx req]

  (let [{:keys [short-circuit?] :as C}
        (n/akey?? ctx n/corscfg-key)
        origin (cc/msg-header req
                              (str (n/h1hdr* ORIGIN)))
        config? (or (:any-origin? C)
                    (contains? (:origins C) origin)
                    (:null-origin? C)
                    (.equals NULL-ORIGIN origin))]
    (cond (is-preflight? req)
          (do-preflight ctx req)
          (and short-circuit?
               (not (or (c/nichts? origin)
                        config?))) (forbidden ctx req))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn cors-write

  "Write a message, applying CORS."
  {:arglists '([ctx msg])}
  [^ChannelHandlerContext ctx msg]

  (when-some [cfg (n/akey?? ctx n/corscfg-key)]
    (when (and (:enabled? cfg)
               (c/is? HttpResponse msg))
      (when (set-origin? ctx msg)
        (set-expose-headers ctx msg)
        (set-allow-credentials ctx msg))))
  msg)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

