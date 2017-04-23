;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty file server."
      :author "Kenneth Leung"}

  czlab.nettio.filesvr

  (:gen-class)

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs])

  (:use [czlab.nettio.server]
        [czlab.basal.process]
        [czlab.basal.core]
        [czlab.basal.str]
        [czlab.basal.io]
        [czlab.nettio.core])

  (:import [io.netty.handler.stream ChunkedFile ChunkedStream]
           [io.netty.bootstrap ServerBootstrap]
           [czlab.nettio.server NettyWebServer]
           [czlab.nettio
            WholeMessage
            WholeRequest
            InboundHandler]
           [io.netty.channel
            Channel
            ChannelPipeline
            ChannelHandler
            ChannelHandlerContext]
           [io.netty.handler.codec.http
            HttpResponseStatus
            HttpChunkedInput
            HttpResponse
            HttpHeaderNames
            HttpHeaderValues
            HttpHeaders
            HttpUtil
            LastHttpContent]
           [io.netty.buffer Unpooled]
           [java.io IOException File]
           [czlab.jasal LifeCycle CU XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

(defonce ^:private svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- replyGetVFile ""
  [^ChannelHandlerContext ctx req ^XData xdata]

  (let [keep? (:isKeepAlive? req)
        res (httpReply<>)
        ch (.channel ctx)
        clen (.size xdata)]
    (doto res
      (setHeader "Content-Type" "application/octet-stream")
      (setHeader "Connection" (if keep? "keep-alive" "close"))
      (setHeader "Transfer-Encoding" "chunked")
      (HttpHeaders/setContentLength clen))
    (log/debug "Flushing file of %s bytes to client" clen)
    (.write ctx res)
    (->
      (->> (. xdata fileRef)
           ChunkedFile.
           HttpChunkedInput.
           ;;test non chunk
           ;;(.javaBytes xdata)
           ;;(Unpooled/wrappedBuffer )
           (.writeAndFlush ctx ))
      (closeCF keep?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- fPutter "" [ctx req ^String fname args]

  (log/debug "fPutter file= %s" (io/file (:vdir args) fname))
  (let [vdir (io/file (:vdir args))
        ^XData body (:body req)]
    (if (.isFile body)
      (log/debug "fPutter orig= %s" (.fileRef body)))
    (->> (try!!
           (.code HttpResponseStatus/INTERNAL_SERVER_ERROR)
           (do
             (saveFile vdir fname body)
             (.code HttpResponseStatus/OK)))
         (replyStatus ctx ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- fGetter "" [ctx req ^String fname args]

  (log/debug "fGetter: file= %s" (io/file (:vdir args) fname))
  (let [vdir (io/file (:vdir args))
        xdata (getFile vdir fname)]
    (if (.hasContent xdata)
      (replyGetVFile ctx req xdata)
      (replyStatus ctx (.code HttpResponseStatus/NO_CONTENT)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h1proxy "" [args]
  (proxy [InboundHandler][]
    (channelRead0 [ctx msg]
      (let
        [^String uri (:uri2 msg)
         mtd (:method msg)
         pos (.lastIndexOf uri (int \/))
         p (if (< pos 0)
             uri
             (.substring uri (inc pos)))
         nm (stror p (str (jid<>) ".dat"))]
        (log/debug "%s: uri= %s, file= %s" mtd uri nm)
        (log/debug "args= %s" args)
        (cond
          (= mtd "POST")
          (fPutter ctx msg nm args)
          (= mtd "GET")
          (fGetter ctx msg nm args)
          :else
          (replyStatus ctx
                       (.code HttpResponseStatus/METHOD_NOT_ALLOWED)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; make a In memory File Server
(defn memFileServer<>
  "A file server which can get/put files"

  ([vdir] (memFileServer<> vdir nil))
  ([vdir args]
   (let [^LifeCycle w (mutable<> NettyWebServer)]
     (.init w
            (merge args
                   {:vdir vdir
                    :ifunc #(do {:h1 (h1proxy %)}) }))
     w)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn finzServer "" []
  (.stop ^LifeCycle @svr) (reset! svr nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn -main "" [& args]

  (cond
    (< (count args) 3)
    (println "usage: filesvr host port <rootdir>")
    :else
    (let [^LifeCycle w (memFileServer<> (nth args 2))]
      (.start w {:host (nth args 0)
                 :port (convInt (nth args 1) 8080)})
      (exitHook #(.stop w))
      (reset! svr w)
      (CU/block))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


