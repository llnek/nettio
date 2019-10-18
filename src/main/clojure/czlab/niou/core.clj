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

  (:import [java.util
            Map]
           [java.net
            URI]
           [clojure.lang
            IDeref
            APersistentMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ClientConnectPromise
  ""
  (cc-sync-get-connect [_]
                       [_ millis] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ClientConnect
  ""
  (cc-ws-write [_ msg] "")
  (cc-channel [_] "")
  (cc-module [_] "")
  (cc-finz [_] "")
  (cc-remote-host [_] "")
  (cc-remote-port [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpClientModule
  ""
  (hc-h1-conn [_ host port args] "")
  (hc-h2-conn [_ host port args] "")
  (hc-ws-conn [_ host port cb args] "")
  (hc-ws-send [_ conn msg] "")
  (hc-send-http [_ conn method uri data args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Http1xMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord WsockMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Http2xMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord HttpResultMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpMsgGist
  ""
  (msg-header? [_ h] "Does header exist?")
  (msg-header [_ h] "First value for this header")
  (msg-header-keys [_] "List header names")
  (msg-header-vals [_ h] "List values for this header"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol WsockMsgReplyer
  ""
  (ws-send-string [_ s] "Send websock text")
  (ws-send-bytes [_ b] "Send websock binary"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpResultMsgReplyer
  ""
  (reply-result [res]
                [res arg] "Reply result back to client"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpResultMsgCreator
  ""
  (http-result [req]
               [req status] "Create a http result object"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol SocketAttrProvider
  ""
  (socket-attr-set [_ k a] "Set channel attribute")
  (socket-attr-get [_ k] "Get channel attribute")
  (socket-attr-del [_ k] "Delete channel attribute"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpResultMsgModifier
  ""
  (res-header-del [_ name] "Remove a header")
  (res-header-add [_ name value] "Add a header")
  (res-header-set [_ name value] "Set a header"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpHeaderGist
  ""
  (gist-header? [_ hd] "")
  (gist-header [_ hd] "")
  (gist-header-keys [_] "")
  (gist-header-vals [_ hd] "")
  (gist-param? [_ pm] "")
  (gist-param [_ pm] "")
  (gist-param-keys [_]  "")
  (gist-param-vals [_ pm]  ""))

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
  ([module host port uri cb]
   (ws-connect<> module host port uri cb nil))
  ([module host port uri cb args]
   (let [pfx (if (c/hgl? (:server-cert args)) "wss" "ws")
         uristr (format "%s://%s:%d%s" pfx host port uri)]
     (hc-ws-conn module
                 host
                 port
                 cb
                 (assoc args
                        :websock? true :uri (URI. uristr))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-connect<>
  "Connect to server via http/2."
  ([module host port]
   (h2-connect<> module host port nil))
  ([module host port args]
   (hc-h2-conn module host port args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-connect<>
  "Connect to server via http."
  ([module host port]
   (h1-connect<> module host port nil))
  ([module host port args]
   (hc-h1-conn module host port args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2send*
  "Send stuff to server via http/2."
  ([conn method uri data]
   (h2send* conn method uri data nil))
  ([conn method uri data args]
   (let [args (assoc args
                     :version "2"
                     :host (cc-remote-host conn))]
     (hc-send-http (cc-module conn) conn method uri data args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1send*
  "Send stuff to server via http."
  ([conn method uri data]
   (h1send* conn method uri data nil))
  ([conn method uri data args]
   (let [args (assoc args
                     :is-keep-alive? true
                     :host (cc-remote-host conn))]
     (hc-send-http (cc-module conn) conn method uri data args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- hxsend
  "1-off send." {:tag IDeref}
  ([module target method data]
   (hxsend module target method data nil))
  ([module target method data args]
   (let [url (io/as-url target)
         h (.getHost url)
         p (.getPort url)
         {:keys [version] :as cargs}
         (assoc args
                :is-keep-alive? false
                :host (.getHost url)
                :scheme (.getProtocol url))
         cp (if (= "2" version)
              (h2-connect<> module h p cargs)
              (h1-connect<> module h p cargs))
         cc (->> (c/num?? (:sync-wait args) 5000)
                 (cc-sync-get-connect cp))]
     (l/debug "sending request via ch: %s." (cc-channel cc))
     (hc-send-http module cc method (.toURI url) data cargs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2send
  "Does a generic web operation,
  result/error delivered in the returned promise."
  ([module target method data]
   (h2send module target method data nil))
  ([module target method data args]
   (hxsend module target method data (assoc args :version "2"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1send
  "Does a generic web operation,
  result/error delivered in the returned promise."
  ([module target method data]
   (h1send module target method data nil))
  ([module target method data args]
   (hxsend module target method data args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1post
  "Does a web/post, result/error delivered in the returned promise."
  ([module target data] (h1post module target data nil))
  ([module target data args] (hxsend module target :post data args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1get
  "Does a web/get, result/error delivered in the returned promise."
  ([module target] (h1get module target nil))
  ([module target args] (hxsend module target :get nil args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2post
  "Does a web/post, result/error delivered in the returned promise."
  ([module target data]
   (h2post module target data nil))
  ([module target data args]
   (hxsend module target :post data (assoc args :version "2"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2get
  "Does a web/get, result/error delivered in the returned promise."
  ([module target] (h2get module target nil))
  ([module target args]
   (hxsend module target :get nil (assoc args :version "2"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

