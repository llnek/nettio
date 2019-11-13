;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "Sample netty app - accepts and discards the request."
    :author "Kenneth Leung"}

  czlab.test.nettio.discard

  (:gen-class)

  (:require [czlab.basal.proc :as p]
            [czlab.basal.log :as l]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.xpis :as po]
            [czlab.niou.core :as cc]
            [czlab.niou.module :as mo]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)
(c/defonce- svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- mkcb
  [cb]
  (fn [msg]
    (-> (cc/http-result msg) cc/reply-result) (cb)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn discard-httpd<>
  "Drops the req and returns OK"
  [cb & args]
  (mo/web-server-module<>
    (merge {:implements :czlab.nettio.server/netty
            :user-cb (mkcb cb)} (c/kvs->map args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn finz-server
  [] (when @svr (po/stop @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main
  [& args]
  (cond
    (< (count args) 2)
    (println "usage: discard host port")
    :else
    (let [{:keys [host port] :as w}
          (-> (discard-httpd<>
                #(println "hello, poked by discarder!"))
              (po/start {:host (nth args 0)
                         :port (c/s->int (nth args 1) 8080)}))]
      (p/exit-hook #(po/stop w))
      (reset! svr w)
      (u/block!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


