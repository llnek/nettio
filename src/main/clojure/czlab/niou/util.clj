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

(ns czlab.niou.util

  "Net helpers."

  (:require [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

  (:import [java.util Base64 Base64$Decoder]
           [org.apache.commons.fileupload FileItem]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^String auth "Authorization")
(c/def- ^String basic "Basic")
;;in millisecs
(def ^:dynamic *socket-timeout* 5000)
(def ^String loopback-addr "127.0.0.1")
(def ^String local-host "localhost")

(def mtd-options :options)
(def mtd-connect :connect)
(def mtd-delete :delete)
(def mtd-get :get)
(def mtd-put :put)
(def mtd-post :post)
(def mtd-trace :trace)
(def mtd-head :head)
(def mtd-patch :patch)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-basic-auth

  "Parse line looking for
  basic authentication info."
  {:arglists '([line])}
  [line]

  (let [[a b :as s]
        (c/split (c/strim line) "\\s+")]
    (when (and (c/two? s)
               (c/eq? basic a) (c/hgl? b))
      (let [[x y :as rc]
            (-> (Base64/getDecoder)
                (.decode (str b))
                i/x->str
                (.split ":" 2))]
        (if (c/two? rc)
          {:principal x :credential y})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- clean-str

  [s] `(czlab.basal.core/strim-any ~s ";,"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn generate-nonce

  "Generate a nonce token."
  {:tag String
   :arglists '([])}
  []

  (u/uid<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn generate-csrf

  "Generate a csrf token."
  {:tag String
   :arglists '([])}
  []

  (u/uid<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-ie

  "Looking for MSIE in UserAgent."
  {:arglists '([line])}
  [line]

  (let
    [p1 #".*(MSIE\s*(\S+)\s*).*"
     m1 (re-matches p1 line)
     p2 #".*(Windows\s*Phone\s*(\S+)\s*).*"
     m2 (re-matches p2 line)
     bw "IE"
     dt (if (c/has-no-case? line "iemobile") :mobile :pc)
     bv (if (and (not-empty m1)
                 (> (count m1) 2))
          (clean-str (nth m1 2)))
     dev (if (and (not-empty m2)
                  (> (count m2) 2))
           {:device-version (clean-str (nth m1 2))
            :device-moniker "windows phone"
            :device-type :phone })]
    (if (or (c/hgl? bv) dev)
      (merge {:browser :ie
              :browser-version bv
              :device-type dt} dev))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-chrome

  "Looking for chrome in UserAgent."
  {:arglists '([line])}
  [line]

  (let [p1 #".*(Chrome/(\S+)).*"
        m1 (re-matches p1 line)
        bv (if (and (not-empty m1)
                    (> (count m1) 2))
             (clean-str (nth m1 2))) ]
    (if (c/hgl? bv)
      {:browser :chrome
       :browser-version bv
       :device-type :pc })))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-kindle

  "Looking for Kindle in UserAgent."
  {:arglists '([line])}
  [line]

  (let [p1 #".*(Silk/(\S+)).*"
        m1 (re-matches p1 line)
        bv (if (and (not-empty m1)
                    (> (count m1) 2))
             (clean-str (nth m1 2))) ]
    (if (c/hgl? bv)
      {:browser :silk
       :browser-version bv
       :device-type :mobile
       :device-moniker "kindle"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-android

  "Looking for Android in UserAgent."
  {:arglists '([line])}
  [line]

  (let [p1 #".*(Android\s*(\S+)\s*).*"
        m1 (re-matches p1 line)
        bv (if (and (not-empty m1)
                    (> (count m1) 2))
             (clean-str (nth m1 2))) ]
    (if (c/hgl? bv)
     {:browser :chrome
      :browser-version bv
      :device-type :mobile
      :device-moniker "android" })))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-ffox

  "Looking for Firefox in UserAgent."
  {:arglists '([line])}
  [line]

  (let [p1 #".*(Firefox/(\S+)\s*).*"
        m1 (re-matches p1 line)
        bv (if (and (not-empty m1)
                    (> (count m1) 2))
             (clean-str (nth m1 2))) ]
    (if (c/hgl? bv)
      {:browser :firefox
       :browser-version bv
       :device-type :pc})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-safari

  "Looking for Safari in UserAgent."
  {:arglists '([line])}
  [line]

  (let [p1 #".*(Version/(\S+)\s*).*"
        m1 (re-matches p1 line)
        bv (if (and (not-empty m1)
                    (> (count m1) 2))
             (clean-str (nth m1 2)))
        rc {:browser :safari
            :browser-version bv
            :device-type :pc}]
    (if (c/hgl? bv)
      (cond
        (c/has-no-case? line "mobile/")
        (merge rc {:device-type :mobile})
        (c/has-no-case? line "iphone")
        (merge rc {:device-type :phone
                   :device-moniker "iphone"})
        (c/has-no-case? line "ipad")
        (merge rc {:device-type :mobile
                   :device-moniker "ipad"})
        (c/has-no-case? line "ipod")
        (merge rc {:device-type :mobile
                   :device-moniker "ipod"})
        :else rc ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-user-agent-line

  "Returns browser/device attributes."
  {:arglists '([agentLine])}
  [agentLine]

  (let [line (c/strim agentLine)]
    (cond
      (and (c/embeds? line "Windows")
           (c/embeds? line "Trident/"))
      (parse-ie line)

      (and (c/embeds? line "AppleWebKit/")
           (c/embeds? line "Safari/")
           (c/embeds? line "Chrome/"))
      (parse-chrome line)

      (and (c/embeds? line "AppleWebKit/")
           (c/embeds? line "Safari/")
           (c/embeds? line "Android"))
      (parse-android line)

      (and (c/embeds? line "AppleWebKit/")
           (c/embeds? line "Safari/")
           (c/embeds? line "Silk/"))
      (parse-kindle line)

      (and (c/embeds? line "Safari/")
           (c/embeds? line "Mac OS X"))
      (parse-safari line)

      (and (c/embeds? line "Gecko/")
           (c/embeds? line "Firefox/"))
      (parse-ffox line)

      :else {} )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

