;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.niou.core

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
            [czlab.basal.core :as c])

  (:import [java.util Map]
           [java.net URL URI]
           [czlab.basal XData]
           [czlab.niou Headers]))

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
  (is-open? [_ ] "")
  (is-ssl? [_] "")
  (write-msg [_ msg]
             [_ msg args] "")
  (channel [_] "")
  (module [_] "")
  (remote-host [_] "")
  (remote-port [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpClientModule
  (h1-conn [_ host port args] "Connect to server http 1.x style.")
  (h2-conn [_ host port args] "Connect to server http 2 style.")
  (ws-conn [_ host port args] "Connect to server websocket style.")
  (ws-send [_ conn msg] [_ conn msg args] "Send a websocket message.")
  (h2-send [_ conn msg] [_ conn msg args] "Send a http 2 message.")
  (h1-send [_ conn msg] [_ conn msg args] "Send a http 1.x message."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpMsgGist
  (msg-header? [_ h] "Does header exist?")
  (msg-header [_ h] "First value for this header")
  (msg-header-keys [_] "List header names")
  (msg-header-vals [_ h] "List values for this header"))

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
  (res-status-set [_ s] "Set status.")
  (res-header-del [_ name] "Remove a header")
  (res-header-add [_ name value] "Add a header")
  (res-header-set [_ name value] "Set a header"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param?

  [gist pm]

  (let [{:keys [parameters]} gist]
    (if (map? parameters)
      (contains? parameters pm)
      (.containsKey ^Map parameters pm))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param

  [gist pm]

  (let [{:keys [parameters]} gist]
    (c/_1 (if (map? parameters)
            (get parameters pm)
            (.get ^Map parameters pm)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param-keys

  [gist]

  (let [{:keys [parameters]} gist]
    (if (map? parameters)
      (keys parameters)
      (c/set-> (.keySet ^Map parameters)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param-vals

  [gist pm]

  (let [{:keys [parameters]} gist]
    (if (map? parameters)
      (get parameters pm)
      (c/vec-> (.get ^Map parameters pm)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn encoded-path

  [u] (cond
        (c/is? URL u)
        (encoded-path (.toURI ^URL u))
        (c/is? URI u)
        (let [p (.getRawPath ^URI u)
              q (.getRawQuery ^URI u)]
          [p (if-not (c/hgl? q) p (str p "?" q))])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoded-path

  [u] (cond
        (c/is? URL u)
        (decoded-path (.toURI ^URL u))
        (c/is? URI u)
        (let [p (.getPath ^URI u)
              q (.getQuery ^URI u)]
          [p (if-not (c/hgl? q) p (str p "?" q))])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn encode-uri

  ^String [uri]
  (cs/join "/" (map #(u/url-encode %) (cs/split uri #"/"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-msg<>

  "Create a http-2 data-map."

  ([status headers body]
   {:pre [(number? status)
          (or (nil? headers)
              (c/is? Headers headers))]}
   (c/object<> czlab.niou.core.Http2xMsg
               :status status
               :body (or (c/cast? XData body)
                         (XData. body false))
               :headers (or headers (Headers.))))

  ([method uri headers body]
   {:pre [(keyword? method)
          (or (string? uri)
              (c/is? URI uri))
          (or (nil? headers)
              (c/is? Headers headers))]}
   (let [uriObj (or (c/cast? URI uri)
                    (URI. uri))]
     (c/object<> czlab.niou.core.Http2xMsg
                 :request-method method
                 :body (or (c/cast? XData body)
                           (XData. body false))
                 :query-string (.getRawQuery uriObj)
                 :uri (.getRawPath uriObj)
                 :uri2 uriObj
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

  (let [uriObj (or (c/cast? URI uri)
                   (URI. uri))]
    (c/object<> czlab.niou.core.Http1xMsg
                :request-method method
                :body (or (c/cast? XData body)
                          (XData. body false))
                :query-string (.getRawQuery uriObj)
                :uri (.getRawPath uriObj)
                :uri2 uriObj
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

  (c/object<> czlab.niou.core.WsockMsg m))

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

