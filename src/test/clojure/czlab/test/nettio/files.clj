;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "Sample netty file server."
    :author "Kenneth Leung"}

  czlab.test.nettio.files

  (:gen-class)

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal
             [proc :as p]
             [log :as l]
             [core :as c]
             [util :as u]
             [io :as i]
             [xpis :as po]]
            [czlab.nettio
             [core :as nc]
             [server :as sv]])

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
(c/defonce- svr (atom nil))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- reply-get-vfile
  [^ChannelHandlerContext ctx req ^XData xdata]
  (let [{:keys [is-keep-alive?]} req
        ^Channel ch (nc/ch?? ctx)
        clen (.size xdata)
        res (nc/http-reply<>)]
    (l/debug "flushing file of %s bytes to client." clen)
    (.write ctx
            (doto res
              (HttpHeaders/setContentLength clen)
              (nc/set-header "Content-Type" "application/octet-stream")
              (nc/set-header "Transfer-Encoding" "chunked")
              (nc/set-header "Connection" (if is-keep-alive? "keep-alive" "close"))))
    (nc/close-cf (->> xdata
                      .fileRef
                      ChunkedFile.
                      HttpChunkedInput.
                      ;;test non chunk
                      ;;(.javaBytes xdata)
                      ;;(Unpooled/wrappedBuffer )
                      (.writeAndFlush ctx )) is-keep-alive?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fputter
  [ctx req ^String fname udir]
  (l/debug "fPutter file= %s." (io/file udir fname))
  (let [vdir (io/file udir)
        ^XData body (:body req)]
    (if (.isFile body)
      (l/debug "fPutter orig= %s." (.fileRef body)))
    (nc/reply-status ctx
                     (u/try!!
                       (nc/scode* INTERNAL_SERVER_ERROR)
                       (do (i/save-file vdir fname body) (nc/scode* OK))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fgetter
  [ctx req ^String fname udir]
  (l/debug "fGetter: file= %s." (io/file udir fname))
  (let [vdir (io/file udir)
        ^XData f (i/get-file vdir fname)]
    (if (.hasContent f)
      (reply-get-vfile ctx req f)
      (nc/reply-status ctx (nc/scode* NO_CONTENT)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h1proxy
  [udir]
  (proxy [InboundHandler][true]
    (readMsg [ctx msg]
      (let [{:keys [^String uri2 method]} msg
            pos (cs/last-index-of uri2 \/)
            p (if (nil? pos)
                uri2 (subs uri2 (+ 1 pos)))
            nm (c/stror p (str (u/jid<>) ".dat"))]
        (l/debug "udir= %s." udir)
        (l/debug "%s: uri= %s, file= %s." method uri2 nm)
        (condp = method
          "GET" (fgetter ctx msg nm udir)
          "POST" (fputter ctx msg nm udir)
          (nc/reply-status ctx (nc/scode* METHOD_NOT_ALLOWED)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; make a In memory File Server
(defn mem-file-server<>
  "A file server which can get/put files."
  [udir & args]
  (sv/tcp-server<> (merge {:udir udir
                           :hh1 (h1proxy udir)} (c/kvs->map args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn finz-server
  [] (when @svr (po/stop @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn -main
  [& args]
  (cond
    (< (count args) 3)
    (println "usage: filesvr host port <rootdir>")
    :else
    (let [w (mem-file-server<> (nth args 2))]
      (p/exit-hook #(po/stop w))
      (reset! svr w)
      (po/start {:host (nth args 0)
                 :block? true
                 :port (c/s->int (nth args 1) 8080)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


