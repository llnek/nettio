;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.nettio.files

  "Sample netty file server."

  (:gen-class)

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.proc :as p]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.niou.core :as cc]
            [czlab.nettio.server :as sv])

  (:import [java.io IOException File]
           [java.net URI]
           [czlab.niou Headers]
           [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
(c/defonce- svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- reply-get-vfile
  [req ^XData xdata]
  (let [{:keys [keep-alive?]} req
        clen (.size xdata)
        res (-> (cc/http-result req)
                (cc/res-body-set (.fileRef xdata)))
        ^Headers hds (:headers res)]
    (c/debug "flushing file of %s bytes to client." clen)
    (.add hds "content-length" (str clen))
    (.add hds "Content-Type" "application/octet-stream")
    ;(.add hds "Transfer-Encoding" "chunked")
    (.add hds "Connection" (if keep-alive? "keep-alive" "close"))
    (cc/reply-result res)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fputter
  [req fname udir]
  (c/debug "fPutter file= %s." (io/file udir fname))
  (let [vdir (io/file udir)
        ^XData body (:body req)]
    (if (.isFile body)
      (c/debug "fPutter orig= %s." (.fileRef body)))
    (-> (cc/http-result
          req
          (try (i/save-file vdir fname body)
               200
               (catch Throwable _ 500))) cc/reply-result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fgetter
  [req fname udir]
  (c/debug "fGetter: file= %s." (io/file udir fname))
  (let [vdir (io/file udir)
        ^XData f (i/get-file vdir fname)]
    (if (.hasContent f)
      (reply-get-vfile req f)
      (-> (cc/http-result 204) cc/reply-result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- h1proxy
  [udir]
  (fn [req]
    (let [{:keys [uri2 request-method]} req
          path (.getPath ^URI uri2)
          pos (cs/last-index-of path \/)
          p (if (nil? pos)
              path (subs path (+ 1 pos)))
          nm (c/stror p (str (u/jid<>) ".dat"))]
      (c/debug "udir= %s." udir)
      (c/debug "%s: uri= %s, file= %s." request-method path nm)
      (condp = request-method
        :get (fgetter req nm udir)
        :post (fputter req nm udir)
        (-> (cc/http-result req 405) cc/reply-result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; make a In memory File Server
(defn file-server<>
  "A file server which can get/put files."
  [& args]
  (let [{:keys [udir] :as args'}
        (merge {:udir (i/file-repo)} (c/kvs->map args))]
    (sv/web-server-module<> (assoc args'
                                   :user-cb (h1proxy udir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn finz-server
  [] (when @svr (c/stop @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; filesvr host port vdir
(defn -main
  [& args]
  (cond
    (< (count args) 3)
    (println "usage: filesvr host port <rootdir>")
    :else
    (let [{:keys [host port] :as w}
          (-> (file-server<> :udir (nth args 2))
              (c/start {:host (nth args 0)
                         :port (c/s->int (nth args 1) 8080)}))]
      (p/exit-hook #(c/stop w))
      (reset! svr w)
      (u/block!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


