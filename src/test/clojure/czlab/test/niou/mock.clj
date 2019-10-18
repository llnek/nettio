;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.niou.mock

  (:require [czlab.basal
             [io :as i]
             [core :as c]]
            [czlab.niou
             [core :as cc]
             [webss :as cs]])

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
(defn mock-http-request [pkey mac?]
  (let [w (cs/wsession<> pkey
                         {:macit? mac?
                          :max-age-secs 11111
                          :max-idle-secs 3333})]
    (assoc (HttpMessageObj.) :session w)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mock-http-result "" [req]
  (assoc (HttpMessageObj.) :request req))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


