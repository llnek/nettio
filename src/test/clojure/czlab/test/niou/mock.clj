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

(ns czlab.test.niou.mock

  (:require [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.niou.core :as cc]
            [czlab.niou.webss :as cs])

  (:use [clojure.test])

  (:import [java.net HttpCookie]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord HttpMessageObj []
  cc/HttpMsgGist
  (msg-header [_ h] )
  (msg-header? [_ h] )
  (msg-header-keys [_] )
  (msg-header-vals [_ h] ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mock-http-request
  [pkey mac?]
  (let [w (cs/wsession<> pkey
                         {:crypt? mac?
                          :max-age-secs 11111
                          :max-idle-secs 3333})]
    (assoc (HttpMessageObj.) :session w)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mock-http-result
  [req]
  (assoc (HttpMessageObj.) :request req))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

