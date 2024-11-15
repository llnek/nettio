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
;; Copyright © 2013-2024, Kenneth Leung. All rights reserved.

(ns czlab.niou.webss

  "A Http Web Session."

  (:require [czlab.twisty.core :as t]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.niou.core :as v])

  (:import [java.security GeneralSecurityException]
           [java.net HttpCookie]
           [java.util Date]
           [java.io File]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^String session-cookie "__xs117")
(def ^String csrf-cookie "__xc117")
(def ^String capc-cookie "__xp117")
(def ^String nv-sep "&")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ssid-flag :__xf01es)
(c/def- user-flag :__u982i)
(c/def- ct-flag :__xf184n ) ;; creation time
(c/def- is-flag :__xf284n ) ;; max idle secs
(c/def- lt-flag :__xf384n ) ;; last access time
(c/def- et-flag :__xf484n ) ;; expiry time

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- reset-flags

  "A negative value means that the cookie
  is not stored persistently and will be deleted
  when the Web browser exits.
  A zero value causes the cookie to be deleted."
  [mvs {:strs [max-age-secs max-idle-secs] :as cfg}]

  (let [now (u/system-time)]
    (c/assoc!! mvs
               :impls cfg
               :attrs
               {ssid-flag (u/uid<>)
                ct-flag now
                lt-flag now
                is-flag max-idle-secs
                et-flag (if-not (c/spos? max-age-secs)
                          -1 (+ now (* max-age-secs 1000)))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- test-cookie

  [pkey {:keys [crypt? $cright $cleft]}]

  (if crypt?
    (when (or (c/nichts? $cright)
              (c/nichts? $cleft)
              (.equals ^String $cleft
                       (t/gen-mac pkey $cright)))
      (c/error "session cookie - broken.")
      (c/trap! GeneralSecurityException "Bad Session Cookie."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- crack-cookie!

  [wss ^HttpCookie ck encrypt?]

  (let [cookie (str (some-> ck .getValue))
        pos (cs/index-of cookie \|)
        [p1 p2] (if (nil? pos)
                  ["" cookie]
                  [(subs cookie 0 pos)
                   (subs cookie (+ 1 pos))])]
    (c/debug "session left=%s right=%s." p1 p2)
    (swap! wss
           #(update-in %
                       [:impls]
                       assoc
                       :crypt? encrypt?
                       :$cright p2
                       :$cleft p1
                       :domain-path (some-> ck .getPath)
                       :domain (some-> ck .getDomain)
                       :secure? (some-> ck .getSecure)
                       :hidden? (some-> ck .isHttpOnly)
                       :max-age-secs (some-> ck .getMaxAge)))
    (doseq [nv (c/split p2 nv-sep)
            :let [ss (c/split nv "=" 2)]
            :when (c/two? ss)]
      (let [s1 (u/url-decode (c/_1 ss))
            s2 (u/url-decode (c/_2 ss))]
        (c/debug "s-attr n=%s, v=%s." s1 s2)
        (swap! wss
               #(update-in %
                           [:attrs]
                           assoc
                           (keyword s1)
                           (if (c/wrapped? s1 "__xf" "n") (c/s->long s2 0) s2)))))
    wss))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-session-new?

  "If session is brand new?"
  {:arglists '([wss])}
  [wss]

  (boolean (get-in @wss [:impls :$new?])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-session-null?

  "If session is a null session?"
  {:arglists '([wss])}
  [wss]

  (empty? (:impls @wss)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-max-idle-secs

  "Set the max idle seconds."
  {:arglists '([wss idleSecs])}
  [wss idleSecs]

  (swap! wss
         #(update-in %
                     [:attrs]
                     assoc is-flag idleSecs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn last-accessed-time

  "Get the last accessed time."
  {:arglists '([wss])}
  [wss]

  (c/num?? (lt-flag (:attrs @wss)) -1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn max-idle-secs

  "Get the max idle seconds."
  {:arglists '([wss])}
  [wss]

  (c/num?? (is-flag (:attrs @wss)) -1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn creation-time

  "Get the creation time."
  {:arglists '([wss])}
  [wss]

  (c/num?? (ct-flag (:attrs @wss)) -1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn expiry-time

  "Get the expiry time."
  {:arglists '([wss])}
  [wss]

  (c/num?? (et-flag (:attrs @wss)) -1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn session-signer

  "Get the session's signing key."
  {:arglists '([wss])}
  [wss]

  (:$pkey @wss))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn validate??

  "Validate this session."
  {:arglists '([wss])}
  [wss]

  (let [ts (last-accessed-time wss)
        mi (max-idle-secs wss)
        es (expiry-time wss)
        now (u/system-time)]
    (test-cookie (session-signer wss) @wss)
    (if (or (and (c/spos? es) (< es now))
            (and (c/spos? mi)
                 (< (+ ts (* 1000 mi)) now)))
      (c/trap! GeneralSecurityException "Session has expired"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn remove-session-attr

  "Remove an attribute from session."
  {:arglists '([wss k])}
  [wss k]

  (swap! wss
         #(update-in % [:attrs] dissoc k)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-session-attr

  "Set a session's attribute value."
  {:arglists '([wss k v])}
  [wss k v]

  (swap! wss
         #(update-in % [:attrs] assoc k v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn session-attr

  "Get a session attribute."
  {:arglists '([wss k])}
  [wss k]

  (get-in @wss [:attrs k]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn remove-session-attrs

  "Clear all session attibutes."
  {:arglists '([wss])}
  [wss]

  (c/assoc!! wss :attrs {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn session-attrs

  "Get the session attibutes."
  {:arglists '([wss])}
  [wss]

  (:attrs @wss))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn invalidate!

  "Invalidate this session."
  {:arglists '([wss])}
  [wss]

  (c/assoc!! wss :impls {} :attrs {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-principal

  "Set the user id."
  {:arglists '([wss p])}
  [wss p]

  (swap! wss
         #(update-in % [:impls] assoc user-flag p)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn principal

  "Get the user id."
  {:arglists '([wss])}
  [wss]

  (get-in @wss [:impls user-flag]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-session-new

  "Set a new session with data."
  {:arglists '([wss flag? arg])}
  [wss flag? arg]

  (when flag?
    (invalidate! wss)
    (reset-flags wss arg))
  (swap! wss
         #(update-in % [:impls] assoc :$new? flag?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn session-error

  "Get the last session error."
  {:arglists '([wss])}
  [wss]

  (get-in @wss [:impls :$error]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-session-error

  "Set the last session error."
  {:arglists '([wss t])}
  [wss t]

  (swap! wss
         #(update-in % [:impls] assoc :$error t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn encode-attrs

  "URL encode the session attributes."
  {:arglists '([wss])}
  [wss]

  (c/sreduce<>
    #(let [[k v] %2]
       (c/sbf-join %1
                   nv-sep
                   (str (-> (name k)
                            (u/url-encode))
                        "="
                        (u/url-encode v)))) (session-attrs wss)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn session-id

  "Get the session id."
  {:arglists '([wss])}
  [wss]

  (get-in @wss [:impls ssid-flag]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wsession<>

  "Create a Web Session."
  {:arglists '([pkey]
               [pkey arg]
               [pkey cookie secure?])}

  ([^bytes pkey cookie secure?]
   (doto
     (wsession<> pkey)
     (set-session-new false nil)
     (crack-cookie! cookie secure?)))

  ([^bytes pkey arg]
   (doto
     (wsession<> pkey)
     (set-session-new true arg)))

  ([^bytes pkey]
   (atom {:$pkey pkey
          :attrs {}
          :impls {:$new? true}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- macit??

  [pkey data secure?]

  (if secure? (str (t/gen-mac pkey data) "|" data) data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn downstream

  "Set session-cookie for outbound message#response."
  {:arglists '([res][res session])}

  ([res]
   (downstream res nil))

  ([res sessionObj]
   (let [req (:request res)
         mvs (or sessionObj
                 (:session req))]
     (if (or (nil? mvs)
             (is-session-null? mvs))
       res
       (let [_ (c/debug "session ok, about to set-cookie!")
             pkey (session-signer mvs)
             data (encode-attrs mvs)
             {{:keys [max-age-secs
                      domain-path
                      domain
                      crypt? hidden? secure?]} :impls} @mvs
             ck (->> (macit?? pkey data crypt?)
                     (HttpCookie. session-cookie))]
         ;;session cookie should always be -1 -> maxAge
         ;;and really should be httpOnly=true
         (doto ck
           (.setHttpOnly (boolean hidden?))
           (.setSecure (boolean secure?))
           (.setMaxAge (if (c/spos? max-age-secs) max-age-secs -1)))
         (if (c/hgl? domain-path) (.setPath ck domain-path))
         (if (c/hgl? domain) (.setDomain ck domain))
         (update-in res
                    [:cookies] assoc (.getName ck) ck))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn upstream

  "Create session from session-cookie."
  {:arglists '([pkey cookies encrypt?])}
  [pkey cookies encrypt?]

  (wsession<> pkey (get cookies session-cookie) encrypt?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

