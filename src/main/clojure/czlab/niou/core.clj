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
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

  (:import [czlab.niou Headers]
           [java.util
            Map]
           [java.net
            URI]
           [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord HttpResultMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Http1xMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord WsockMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Http2xMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ClientConnection
  (cc-is-open? [_ ] "")
  (cc-is-ssl? [_] "")
  (cc-write [_ msg]
            [_ msg args] "")
  (cc-channel [_] "")
  (cc-module [_] "")
  (cc-finz [_] "")
  (cc-remote-host [_] "")
  (cc-remote-port [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpClientModule
  (hc-h1-conn [_ host port args] "")
  (hc-h2-conn [_ host port args] "")
  (hc-ws-conn [_ host port args] "")
  (hc-ws-send [_ conn msg] [_ conn msg args] "")
  (hc-h2-send [_ conn msg] [_ conn msg args] "")
  (hc-h1-send [_ conn msg] [_ conn msg args] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpMsgGist
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
(defn h2-msg<>

  "Create a http-2 data-map."

  ([status headers body]
   {:pre [(number? status)
          (or (nil? headers)
              (c/is? Headers headers))]}
   (c/object<> czlab.niou.core.Http2xMsg
               :status status
               :body (XData. body)
               :headers (or headers (Headers.))))

  ([method uri headers body]
   {:pre [(keyword? method)
         (or (string? uri)
             (c/is? URI uri))
         (or (nil? headers)
             (c/is? Headers headers))]}

  (let [[uri uri2]
        (-> (or (c/cast? URI uri)
                (URI. uri))
            (i/encoded-paths))]
    (c/object<> czlab.niou.core.Http2xMsg
                :request-method method
                :body (XData. body)
                :uri uri
                :uri2 uri2
                :headers (or headers (Headers.))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-msg<>

  "Create a http-1.x data-map."
  [method uri headers body]
  {:pre [(keyword? method)
         (or (string? uri)
             (c/is? URI uri))
         (or (nil? headers)
             (c/is? Headers headers))]}

  (let [[uri uri2]
        (-> (or (c/cast? URI uri)
                (URI. uri))
            (i/encoded-paths))]
    (c/object<> czlab.niou.core.Http1xMsg
                :request-method method
                :body (XData. body)
                :uri uri
                :uri2 uri2
                :headers (or headers (Headers.)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-get<>

  "Simple http GET."

  ([uri] (h1-get<> uri nil))
  ([uri headers] (h1-msg<> :get uri headers nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-post<>

  "Simple http POST."

  ([uri body] (h1-post<> uri nil body))
  ([uri headers body] (h1-msg<> :post uri headers body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-msg<>

  "Create a Websocket data-map."
  [m]
  {:pre [(or (nil? m)(map? m))]}

  (c/object<> czlab.niou.core.WsockMsg
              (assoc m :route {:status? true})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-bytes<>

  "Create a Websocket binary data-map."
  [b]
  {:pre [(bytes? b)]}

  (c/object<> czlab.niou.core.WsockMsg
              :body (XData. b) :is-text? false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-text<>

  "Create a Websocket text data-map."
  [s]
  {:pre [(string? s)]}

  (c/object<> czlab.niou.core.WsockMsg
              :body (XData. s) :is-text? true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;Websocket CLOSE message.
(defonce ws-close<> (ws-msg<> {:is-close? true}))
;Websocket PING message.
(defonce ws-ping<> (ws-msg<> {:is-ping? true}))
;Websocket PONG message.
(defonce ws-pong<> (ws-msg<> {:is-pong? true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

