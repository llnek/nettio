;; Copyright © 2013-2020, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.niou.mime

  "MIME helpers."

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u])

  (:import [java.io
            File
            IOException
            InputStream
            UnsupportedEncodingException]
           [javax.activation
            MimetypesFileTypeMap]
           [java.net URL URLEncoder URLDecoder]
           [clojure.lang APersistentMap]
           [java.util.regex Pattern Matcher]
           [java.util Map Map$Entry Properties]
           [org.apache.commons.fileupload ParameterParser]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- is-pkcs7-mime?

  [s] `(c/embeds? ~s "application/x-pkcs7-mime"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^String iso-8859-1 "iso-8859-1")
(def ^String us-ascii "us-ascii")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^Pattern _ext-regex (Pattern/compile "^.*\\.([^.]+)$"))
(c/def- _ctype-dft "application/octet-stream")
(c/def- _mime-cache (atom {}))
(c/def- _mime-types (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn mime-cache<>

  "Common MIME/types."
  {:arglists '([])}
  []
  @_mime-cache)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn charset??

  "Charset from content-type."
  {:tag String
   :arglists '([cType]
               [cType dft])}

  ([cType]
   (charset?? cType nil))

  ([cType dft]
   {:pre [(or (nil? cType)
              (string? cType))]}
   (let [p (doto (ParameterParser.)
             (.setLowerCaseNames true))
         pms (.parse p (str cType) \;)]
     (c/stror* (.get pms "charset") dft iso-8859-1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-signed?

  "Does the content-type value indicate *signed*?"
  {:arglists '([ct])}
  [ct]
  {:pre [(string? ct)]}

  (let [ct (c/lcase ct)]
    (or (c/embeds? ct "multipart/signed")
        (and (is-pkcs7-mime? ct)
             (c/embeds? ct "signed-data")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-encrypted?

  "Does the content-type value indicate *encrypted*?"
  {:arglists '([ct])}
  [ct]
  {:pre [(string? ct)]}

  (let [ct (c/lcase ct)]
    (and (is-pkcs7-mime? ct)
         (c/embeds? ct "enveloped-data"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-compressed?

  "Does the content-type value indicate *compressed*?"
  {:arglists '([ct])}
  [ct]
  {:pre [(string? ct)]}

  (let [ct (c/lcase ct)]
    (and (c/embeds? ct "compressed-data")
         (c/embeds? ct "application/pkcs7-mime"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-mdn?

  "Does the content-type value indicate *MDN*?"
  {:arglists '([ct])}
  [ct]
  {:pre [(string? ct)]}

  (let [ct (c/lcase ct)]
    (and (c/embeds? ct "multipart/report")
         (c/embeds? ct "disposition-notification"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn guess-mime-type

  "Guess the MIME/type of file."
  {:tag String
   :arglists '([file]
               [file dft])}

  ([file]
   (guess-mime-type file nil))

  ([file dft]
   (let [mc (.matcher _ext-regex
                      (c/lcase (i/fname file)))
         ex (if (.matches mc) (.group mc 1))]
     (c/stror (if (c/hgl? ex)
                ((mime-cache<>) (keyword ex))) (str dft)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn guess-content-type

  "Guess the content-type of file."
  {:tag String
   :arglists '([file]
               [file enc]
               [file enc dft])}

  ([file enc]
   (guess-content-type file enc nil))

  ([file]
   (guess-content-type file nil nil))

  ([file enc dft]
   (let [enc (c/stror enc "utf-8")
         ct (-> (guess-mime-type file)
                (c/stror* dft _ctype-dft))]
     (if-not
       (cs/starts-with? ct "text/") ct (str ct "; charset=" enc)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- setup

  [file]

  (try
    (c/let->true [p (u/load-java-props file)]
      (reset! _mime-cache (u/pmap<> p))
      (c/wo* [inp (->> (.entrySet p)
                       (c/sreduce<>
                         (fn [b ^Map$Entry en]
                           (c/sbf+ b
                                   (c/strim (str (.getValue en)))
                                   "  "
                                   (c/strim (str (.getKey en))) "\n")))
                       i/x->bytes
                       io/input-stream)]
        (reset! _mime-types (MimetypesFileTypeMap. inp))))
    (catch Throwable _
      (reset! _mime-cache nil)
      (reset! _mime-types nil)
      ;(c/exception _)
      (u/throw-IOE "Failed to parse mime.properties."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn setup-cache

  "Load file mime-types as a map."
  {:arglists '([file])}
  [file]

  (or (try (doto file setup)
           (catch Throwable _ nil))
      (setup (i/res->url "czlab/niou/etc/mime.properties"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn normalize-email

  "Check email address."
  {:tag String
   :arglists '([email])}
  [email]

  (let [email (str email)]
    (cond (c/nichts? email)
          email
          (or (nil? (cs/index-of email \@))
              (c/!== (cs/index-of email \@)
                     (cs/last-index-of email \@)))
          (u/throw-BadData "Bad email address %s." email)
          :else
          (let [ss (cs/split email #"@")]
            ;#^"[Ljava.lang.String;"
            (if (c/two? ss)
              (str (c/_1 ss)
                   "@" (cs/lower-case (c/_2 ss)))
              (u/throw-BadData "Bad email address %s." email))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

