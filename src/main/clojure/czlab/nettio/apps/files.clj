;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Sample netty file server."
      :author "Kenneth Leung"}

  czlab.nettio.apps.files

  (:gen-class)

  (:require [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.nettio.server :as sv]
            [czlab.basal.proc :as p]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.nettio.core :as nc])

  (:import [io.netty.handler.stream ChunkedFile ChunkedStream]
           [io.netty.bootstrap ServerBootstrap]
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
           [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
(defonce ^:private svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- reply-get-vfile ""
  [^ChannelHandlerContext ctx req ^XData xdata]

  (let [keep? (:is-keep-alive? req)
        res (nc/http-reply<>)
        ch (.channel ctx)
        clen (.size xdata)]
    (doto res
      (nc/set-header "Content-Type" "application/octet-stream")
      (nc/set-header "Connection" (if keep? "keep-alive" "close"))
      (nc/set-header "Transfer-Encoding" "chunked")
      (HttpHeaders/setContentLength clen))
    (l/debug "Flushing file of %s bytes to client" clen)
    (.write ctx res)
    (->
      (->> (.fileRef xdata)
           ChunkedFile.
           HttpChunkedInput.
           ;;test non chunk
           ;;(.javaBytes xdata)
           ;;(Unpooled/wrappedBuffer )
           (.writeAndFlush ctx ))
      (nc/close-cf keep?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fputter "" [ctx req ^String fname udir]

  (l/debug "fPutter file= %s" (io/file udir fname))
  (let [vdir (io/file udir)
        ^XData body (:body req)]
    (if (.isFile body)
      (l/debug "fPutter orig= %s" (.fileRef body)))
    (->> (u/try!!
           (nc/scode HttpResponseStatus/INTERNAL_SERVER_ERROR)
           (do
             (i/save-file vdir fname body)
             (nc/scode HttpResponseStatus/OK)))
         (nc/reply-status ctx ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fgetter "" [ctx req ^String fname udir]

  (l/debug "fGetter: file= %s" (io/file udir fname))
  (let [vdir (io/file udir)
        ^XData f (i/get-file vdir fname)]
    (if (.hasContent f)
      (reply-get-vfile ctx req f)
      (nc/reply-status ctx
                       (nc/scode HttpResponseStatus/NO_CONTENT)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h1proxy "" [udir]
  (proxy [InboundHandler][true]
    (readMsg [ctx msg]
      (let
        [^String uri (:uri2 msg)
         mtd (:method msg)
         pos (cs/last-index-of uri \/)
         p (if (nil? pos)
             uri
             (subs uri (inc pos)))
         nm (s/stror p (str (u/jid<>) ".dat"))]
        (l/debug "%s: uri= %s, file= %s" mtd uri nm)
        (l/debug "udir= %s" udir)
        (cond
          (= mtd "POST")
          (fputter ctx msg nm udir)
          (= mtd "GET")
          (fgetter ctx msg nm udir)
          :else
          (nc/reply-status ctx
                           (nc/scode HttpResponseStatus/METHOD_NOT_ALLOWED)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; make a In memory File Server
(defn mem-file-server<>
  "A file server which can get/put files."
  [udir & args]
  (apply sv/netty-web-server<> :udir udir :hh1 (h1proxy udir) args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn finz-server "" []
  (when @svr
    (sv/stop-server! @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn -main "" [& args]

  (cond
    (< (count args) 3)
    (println "usage: filesvr host port <rootdir>")
    :else
    (let [w (sv/start-web-server!
              (mem-file-server<> (nth args 2))
              {:host (nth args 0)
               :port (c/s->int (nth args 1) 8080)})]
      (p/exit-hook #(sv/stop-server! w))
      (reset! svr w)
      (u/block!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


