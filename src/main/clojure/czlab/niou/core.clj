;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.niou.core

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal
             [log :as l]
             [core :as c]])

  (:import [com.sun.net.httpserver Headers]
           [java.util
            Map]
           [czlab.basal XData]
           [java.net
            URI]
           [clojure.lang
            IDeref
            APersistentMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ClientConnectPromise
  (cc-sync-get-connect [_]
                       [_ millis] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ClientConnect
  (cc-is-open? [_ ] "")
  (cc-write [_ msg] "")
  (cc-channel [_] "")
  (cc-module [_] "")
  (cc-finz [_] "")
  (cc-remote-host [_] "")
  (cc-remote-port [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpClientModule
  (hc-ws-send [_ conn msg] "")
  (hc-h2-send [_ conn msg] "")
  (hc-h1-send [_ conn msg] "")
  (hc-h1-conn [_ host port args] "")
  (hc-h2-conn [_ host port args] "")
  (hc-ws-conn [_ host port args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord HttpResultMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Http1xMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord WsockMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Http2xMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpHeaderGist
  (gist-header? [_ hd] "")
  (gist-header [_ hd] "")
  (gist-header-keys [_] "")
  (gist-header-vals [_ hd] "")
  (gist-param? [_ pm] "")
  (gist-param [_ pm] "")
  (gist-param-keys [_]  "")
  (gist-param-vals [_ pm]  ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpMsgGist
  ""
  (msg-header? [_ h] "Does header exist?")
  (msg-header [_ h] "First value for this header")
  (msg-header-keys [_] "List header names")
  (msg-header-vals [_ h] "List values for this header"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol WsockMsgReplyer
  (ws-send-string [_ s] "Send websock text")
  (ws-send-bytes [_ b] "Send websock binary"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpResultMsgReplyer
  (reply-result [res]
                [res arg] "Reply result back to client"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpResultMsgCreator
  (http-result [req]
               [req status] "Create a http result object"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpResultMsgModifier
  (res-cookie-add [_ cookie] "Add a cookie.")
  (res-body-set [_ body] "Add content.")
  (res-header-del [_ name] "Remove a header")
  (res-header-add [_ name value] "Add a header")
  (res-header-set [_ name value] "Set a header"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-msg<>
  [method uri headers body]
  {:pre [(keyword? method)
         (or (string? uri)
             (c/is? URI uri))
         (or (nil? headers)
             (c/is? Headers headers))]}
  (c/object<> czlab.niou.core.Http1xMsg
              :request-method method
              :body body
              :headers (or headers (Headers.))
              :uri (if-some [uri' (c/cast? URI uri)]
                     (let [p (.getPath uri')
                           q (.getQuery uri')]
                       (if (c/hgl? q) (str p "?" q) p)) uri)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-get<>
  ([uri] (h1-get<> uri nil))
  ([uri headers] (h1-msg<> :get uri headers nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-post<>
  ([uri body] (h1-post<> uri body nil))
  ([uri body headers] (h1-msg<> :post uri headers body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-msg<>
  [m] (c/object<> czlab.niou.core.WsockMsg
                  (assoc m :route {:status? true})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-bytes<>
  [b]
  (c/object<> czlab.niou.core.WsockMsg :body (XData. b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-text<>
  [s]
  (c/object<> czlab.niou.core.WsockMsg
              :body (XData. s) :is-text? true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce ws-close<> (ws-msg<> {:is-close? true}))
(defonce ws-ping<> (ws-msg<> {:is-ping? true}))
(defonce ws-pong<> (ws-msg<> {:is-pong? true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol HttpHeaderGist
  APersistentMap
  (gist-header? [gist hd]
    (let [{:keys [headers]} gist]
      (assert (map? headers))
      (contains? headers (c/lcase hd))))
  (gist-header [gist hd]
    (let [{:keys [headers]} gist]
      (assert (map? headers))
      (c/_1 (get headers (c/lcase hd)))))
  (gist-header-keys [gist]
    (let [{:keys [headers]} gist]
      (assert (map? headers)) (keys headers)))
  (gist-header-vals [gist hd]
    (let [{:keys [headers]} gist]
      (assert (map? headers)) (get headers (c/lcase hd))))
  (gist-param? [gist pm]
    (let [{:keys [parameters]} gist]
      (if (map? parameters)
        (contains? parameters pm)
        (.containsKey ^Map parameters pm))))
  (gist-param [gist pm]
    (let [{:keys [parameters]} gist]
      (c/_1 (if (map? parameters)
              (get parameters pm)
              (.get ^Map parameters pm)))))
  (gist-param-keys [gist]
    (let [{:keys [parameters]} gist]
      (if (map? parameters)
        (keys parameters)
        (c/set-> (.keySet ^Map parameters)))))
  (gist-param-vals [gist pm]
    (let [{:keys [parameters]} gist]
      (if (map? parameters)
        (get parameters pm)
        (c/vec-> (.get ^Map parameters pm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-connect<>
  "Connect to server via websocket."
  ([module host port uri]
   (ws-connect<> module host port uri nil))
  ([module host port uri arg]
   (let [{:keys [user-cb] :as args}
         (if (fn? arg) {:user-cb arg} arg)
         pfx (if (c/hgl? (:server-cert args)) "wss" "ws")
         uristr (format "%s://%s:%d%s" pfx host port uri)]
     (hc-ws-conn module
                 host
                 port
                 (-> (if (fn? user-cb)
                       args
                       (assoc args :user-cb (c/fn_2 0)))
                     (assoc :websock? true :uri (URI. uristr)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-connect<>
  "Connect to server via http/2."
  [module host port args]
  (hc-h2-conn module host port args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-connect<>
  "Connect to server via http."
  [module host port args]
  (hc-h1-conn module host port args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2send*
  "Send stuff to server via http/2."
  ([conn method uri data]
   (h2send* conn method uri data nil))
  ([conn method uri data args]
   (let [args (assoc args
                     :method method
                     :uri uri
                     :body data
                     :version "2"
                     :host (cc-remote-host conn))]
     (hc-h2-send (cc-module conn) conn args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1send*
  "Send stuff to server via http."
  [conn method uri data args]
  (let [args (assoc args
                    :keep-alive? true
                    :method method
                    :uri uri
                    :body data
                    :host (cc-remote-host conn))]
    (hc-h1-send (cc-module conn) conn args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- hxsend
  "1-off send." {:tag IDeref}
  [module target method data args]
  (let [url (io/as-url target)
        h (.getHost url)
        p (.getPort url)
        {:keys [version
                sync-wait] :as cargs}
        (assoc args
               :keep-alive? false
               :host (.getHost url)
               :scheme (.getProtocol url))
        v2? (.equals "2" version)
        cp (if v2?
             (h2-connect<> module h p cargs)
             (h1-connect<> module h p cargs))
        ARGS (assoc cargs
                    :body data
                    :method method
                    :uri (.toURI url))
        cc (->> (c/num?? sync-wait 5000)
                (cc-sync-get-connect cp))]
    (l/debug "sending request via ch: %s." (cc-channel cc))
    (if v2?
      (hc-h2-send module cc ARGS)
      (hc-h1-send module cc ARGS))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2send
  "Does a generic web operation,
  result/error delivered in the returned promise."
  [module target method data args]
  (hxsend module target method data (assoc args :version "2")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1send
  "Does a generic web operation,
  result/error delivered in the returned promise."
  [module target method data args]
  (hxsend module target method data args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1post
  "Does a web/post, result/error delivered in the returned promise."
  [module target data args]
  (hxsend module target :post data args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1get
  "Does a web/get, result/error delivered in the returned promise."
  [module target args]
  (hxsend module target :get nil args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2post
  "Does a web/post, result/error delivered in the returned promise."
  [module target data args]
  (hxsend module target :post data (assoc args :version "2")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2get
  "Does a web/get, result/error delivered in the returned promise."
  [module target args]
  (hxsend module target :get nil (assoc args :version "2")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

