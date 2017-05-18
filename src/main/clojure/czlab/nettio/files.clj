;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty file server."
      :author "Kenneth Leung"}

  czlab.nettio.files

  (:gen-class)

  (:require [czlab.basal.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.nettio.server :as sv]
            [czlab.basal.process :as p]
            [czlab.basal.core :as c]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.nettio.core :as nc])

  (:import [io.netty.handler.stream ChunkedFile ChunkedStream]
           [io.netty.bootstrap ServerBootstrap]
           [czlab.nettio.server NettyWebServer]
           [czlab.nettio InboundHandler]
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
        res (nc/httpReply<>)
        ch (.channel ctx)
        clen (.size xdata)]
    (doto res
      (nc/setHeader "Content-Type" "application/octet-stream")
      (nc/setHeader "Connection" (if keep? "keep-alive" "close"))
      (nc/setHeader "Transfer-Encoding" "chunked")
      (HttpHeaders/setContentLength clen))
    (log/debug "Flushing file of %s bytes to client" clen)
    (.write ctx res)
    (->
      (->> (.fileRef xdata)
           ChunkedFile.
           HttpChunkedInput.
           ;;test non chunk
           ;;(.javaBytes xdata)
           ;;(Unpooled/wrappedBuffer )
           (.writeAndFlush ctx ))
      (nc/closeCF keep?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- fPutter "" [ctx req ^String fname args]

  (log/debug "fPutter file= %s" (io/file (:vdir args) fname))
  (let [vdir (io/file (:vdir args))
        ^XData body (:body req)]
    (if (.isFile body)
      (log/debug "fPutter orig= %s" (.fileRef body)))
    (->> (c/try!!
           (nc/scode HttpResponseStatus/INTERNAL_SERVER_ERROR)
           (do
             (i/saveFile vdir fname body)
             (nc/scode HttpResponseStatus/OK)))
         (nc/replyStatus ctx ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- fGetter "" [ctx req ^String fname args]

  (log/debug "fGetter: file= %s" (io/file (:vdir args) fname))
  (let [vdir (io/file (:vdir args))
        xdata (i/getFile vdir fname)]
    (if (.hasContent xdata)
      (replyGetVFile ctx req xdata)
      (nc/replyStatus ctx (nc/scode HttpResponseStatus/NO_CONTENT)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- h1proxy "" [args]
  (proxy [InboundHandler][true]
    (readMsg [ctx msg]
      (let
        [^String uri (:uri2 msg)
         mtd (:method msg)
         pos (.lastIndexOf uri (int \/))
         p (if (< pos 0)
             uri
             (.substring uri (inc pos)))
         nm (s/stror p (str (c/jid<>) ".dat"))]
        (log/debug "%s: uri= %s, file= %s" mtd uri nm)
        (log/debug "args= %s" args)
        (cond
          (= mtd "POST")
          (fPutter ctx msg nm args)
          (= mtd "GET")
          (fGetter ctx msg nm args)
          :else
          (nc/replyStatus ctx
                          (nc/scode HttpResponseStatus/METHOD_NOT_ALLOWED)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; make a In memory File Server
(defn memFileServer<>
  "A file server which can get/put files"

  ([vdir] (memFileServer<> vdir nil))
  ([vdir args]
   (c/do-with [^LifeCycle
               w (c/mutable<> NettyWebServer)]
              (let [args (assoc args :vdir vdir)]
                (.init w
                       (assoc args
                              :hh1 (h1proxy args)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn finzServer "" [] (.stop ^LifeCycle @svr) (reset! svr nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn -main "" [& args]

  (cond
    (< (count args) 3)
    (println "usage: filesvr host port <rootdir>")
    :else
    (let [^LifeCycle
          w (memFileServer<> (nth args 2))]
      (.start w {:host (nth args 0)
                 :port (c/convInt (nth args 1) 8080)})
      (p/exitHook #(.stop w))
      (reset! svr w)
      (CU/block))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


