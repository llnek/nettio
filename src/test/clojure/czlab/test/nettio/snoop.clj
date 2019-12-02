;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.nettio.snoop

  "Sample netty app - snoops on the request."

  (:gen-class)

  (:require [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.proc :as p]
            [czlab.basal.xpis :as po]
            [czlab.niou.core :as cc]
            [czlab.nettio.server :as sv])

  (:import [czlab.basal XData]
           [czlab.niou Headers]
           [java.net URI HttpCookie]
           [java.util Map$Entry]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(c/defonce- svr (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- write-reply
  "Reply back a string."
  [req buf]
  (let [cookies (:cookies req)
        body (i/x->bytes buf)
        res (-> (cc/http-result req)
                (cc/res-body-set body))
        ^Headers hds (:headers res)]
    (.set hds "Content-Length" (str (alength body)))
    (.set hds "Content-Type"
              "text/plain; charset=UTF-8")
    (.set hds
          "Connection"
          (if (:keep-alive? req) "keep-alive" "close"))
    (->> (if (not-empty cookies)
           cookies
           {"key1" (HttpCookie. "key1" "value1")
            "key2" (HttpCookie. "key2" "value2")})
         vals
         (reduce #(cc/res-cookie-add %1 %2) res)
         (cc/reply-result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- handle-req
  "Introspect the inbound request"
  [req]
  (let [headers (:headers req)
        ka? (:keep-alive? req)]
    (c/sbf+ (c/sbf<>)
            "WELCOME TO THE TEST WEB SERVER\r\n"
            "==============================\r\n"
            "VERSION: "
            (:protocol req)
            "\r\n"
            "HOSTNAME: "
            (str (cc/msg-header req "host"))
            "\r\n"
            "REQUEST_URI: "
            (.getPath ^URI (:uri2 req))
            "\r\n\r\n"
            (c/sreduce<>
              #(c/sbf+ %1
                       "HEADER: "
                       %2
                       " = "
                       (cs/join "," (cc/msg-header-vals req %2))
                       "\r\n")
              (cc/msg-header-keys req))
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
  [msg buf]
  (let [^XData ct (:body msg)]
    (if (.hasContent ct)
      (c/sbf+ buf "CONTENT: " (.strit ct) "\r\n"))
    (write-reply msg
                 (c/sbf+ buf "END OF CONTENT\r\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn snoop-httpd<>
  "Sample Snooper HTTPD."
  [& args]
  (sv/web-server-module<>
    (merge {:user-cb
            #(->> (handle-req %1)
                  (handle-cnt %1))} (c/kvs->map args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn finz-server
  [] (when @svr (po/stop @svr) (reset! svr nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main
  [& args]
  (cond
    (< (count args) 2)
    (println "usage: snoop host port")
    :else
    (let [{:keys [host port] :as w}
          (-> (snoop-httpd<>)
              (po/start {:host (nth args 0)
                         :port (c/s->int (nth args 1) 8080)}))]
      (p/exit-hook #(po/stop w))
      (reset! svr w)
      (u/block!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

