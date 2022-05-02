;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; Copyright © 2013-2022, Kenneth Leung. All rights reserved.

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
(defrecord Http1xMsg [])
(defrecord WsockMsg [])
(defrecord Http2xMsg [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HClient
  "Http client object."
  (is-open? [_ ] "Is client connected?")
  (is-ssl? [_] "Is a SSL connection?")
  (channel [_] "The connection.")
  (module [_] "The implementation.")
  (remote-host [_] "The remote host.")
  (remote-port [_] "The remote port.")
  (write-msg [_ msg]
             [_ msg args] "Write message to remote."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HClientModule
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ChannelAttrs
  ""
  (setattr [_ a v] "Tag an attribute")
  (delattr [_ a] "Remove an attribute")
  (getattr [_ a] "Get an attribute"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-ajax?

  "Is this request from AJAX?"
  {:arglists '([msg])}
  [msg]

  (if msg (c/eq? "xmlhttprequest"
                 (c/lcase (msg-header msg "x-requested-with")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param?

  "If message has this parameter?"
  {:arglists '([gist pm])}
  [gist pm]

  (let [{:keys [parameters]} gist]
    (if (map? parameters)
      (contains? parameters pm)
      (some-> ^Map parameters
              (.containsKey pm)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param

  "Get parameter from message."
  {:arglists '([gist pm])}
  [gist pm]

  (let [{:keys [parameters]} gist]
    (c/_1 (if (map? parameters)
            (get parameters pm)
            (some-> ^Map parameters (.get pm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param-keys

  "Get the names of the parameters."
  {:arglists '([gist])}
  [gist]

  (let [{:keys [parameters]} gist]
    (if (map? parameters)
      (keys parameters)
      (c/set-> (some-> ^Map parameters .keySet)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gist-param-vals

  "Get the values for this parameter."
  {:arglists '([gist pm])}
  [gist pm]

  (let [{:keys [parameters]} gist]
    (if (map? parameters)
      (get parameters pm)
      (c/vec-> (some-> ^Map parameters (.get pm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pre-headers??

  {:arglists '([h])}
  [h]

  (or (nil? h)
      (c/is? Headers h)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn toURI

  "Convert input to a URI."
  {:tag URI
   :arglists '([in])}
  [in]

  (cond
    (c/is? URL in) (.toURI ^URL in)
    (c/is? URI in) in
    (string? in) (URI. ^String in)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn toXData

  "Convert input to a XData."
  {:arglists '([in]
               [in del?])}

  ([in]
   (toXData in false))

  ([in del?]
   (or (c/cast? XData in) (XData. in del?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn encoded-path

  "Get the url-encoded value of this path.
  Returns a tuple: [path-uri full-path]"
  {:arglists '([u])}
  [u]

  (let [u (toURI u)
        p (.getRawPath u)
        q (.getRawQuery u)]
    [p (if-not (c/hgl? q) p (str p "?" q))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn decoded-path

  "Get the url-decoded value of this path.
  Returns a tuple: [path-uri full-path]"
  {:arglists '([u])}
  [u]

  (let [u (toURI u)
        p (.getPath u)
        q (.getQuery u)]
    [p (if-not (c/hgl? q) p (str p "?" q))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn encode-uri

  "Urlencode the uri."
  {:tag String
   :arglists '([uri])}
  [uri]

  (cs/join ""
           (map #(if (c/eq? "/" %)
                   % (u/url-encode %))
                (c/split-str uri "/" true))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h2-msg<>

  "Create a http-2 data-map."
  {:arglists '([status headers body]
               [method uri headers body])}

  ([status headers body]
   {:pre [(number? status)
          (pre-headers?? headers)]}
   (c/object<> czlab.niou.core.Http2xMsg
               :status status
               :body (toXData body)
               :headers (or headers (Headers.))))

  ([method uri headers body]
   {:pre [(keyword? method)
          (pre-headers?? headers)]}
   (if-some [uriObj (toURI uri)]
     (c/object<> czlab.niou.core.Http2xMsg
                 :request-method method
                 :body (toXData body)
                 :query-string (.getRawQuery uriObj)
                 :uri (.getRawPath uriObj)
                 :uri2 uriObj
                 :headers (or headers (Headers.))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-msg<>

  "Create a http-1.x data-map."
  {:arglists '([method uri headers body])}
  [method uri headers body]
  {:pre [(keyword? method)
         (pre-headers?? headers)]}

  (if-some [uriObj (toURI uri)]
    (c/object<> czlab.niou.core.Http1xMsg
                :request-method method
                :body (toXData body)
                :query-string (.getRawQuery uriObj)
                :uri (.getRawPath uriObj)
                :uri2 uriObj
                :headers (or headers (Headers.)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-get<>

  "Simple http GET."
  {:arglists '([uri]
               [uri headers])}

  ([uri]
   (h1-get<> uri nil))

  ([uri headers]
   (h1-msg<> :get uri headers nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn h1-post<>

  "Simple http POST."
  {:arglists '([uri body]
               [uri headers body])}

  ([uri body]
   (h1-post<> uri nil body))

  ([uri headers body]
   (h1-msg<> :post uri headers body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-msg<>

  "Create a Websocket data-map."
  {:arglists '([m])}
  [m]
  {:pre [(or (nil? m)(map? m))]}

  (c/object<> czlab.niou.core.WsockMsg m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-bytes<>

  "Create a Websocket binary data-map."
  {:arglists '([b])}
  [b]
  {:pre [(bytes? b)]}

  (c/object<> czlab.niou.core.WsockMsg
              :body (XData. b) :is-text? false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ws-text<>

  "Create a Websocket text data-map."
  {:arglists '([s])}
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

