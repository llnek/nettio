;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "MIME helpers."
    :author "Kenneth Leung"}

  czlab.niou.mime

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal
             [io :as i]
             [log :as l]
             [core :as c]
             [util :as u]])

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
(defprotocol ContentTypeChecker
  ""
  (is-compressed? [_] "")
  (is-signed? [_] "")
  (is-mdn? [_] "")
  (is-encrypted? [_] ""))

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
  "Cache of common MIME/types." ^APersistentMap [] @_mime-cache)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- is-pkcs7-mime?
  [s] `(c/embeds? ~s "application/x-pkcs7-mime"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn charset??
  "charset from content-type."
  {:tag String}
  ([cType] (charset?? cType nil))
  ([cType dft]
   (let [p (doto (ParameterParser.)
             (.setLowerCaseNames true))
         pms (.parse p ^String cType \;)]
     (c/stror* (.get pms "charset") dft iso-8859-1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ContentTypeChecker
  String
  (is-signed? [me]
    (let [ct (c/lcase me)]
      (or (c/embeds? ct "multipart/signed")
          (and (is-pkcs7-mime? ct) (c/embeds? ct "signed-data")))))
  (is-encrypted? [me]
    (let [ct (c/lcase me)]
      (and (is-pkcs7-mime? ct) (c/embeds? ct "enveloped-data"))))
  (is-compressed? [me]
    (let [ct (c/lcase me)]
      (and (c/embeds? ct "compressed-data")
           (c/embeds? ct "application/pkcs7-mime"))))
  (is-mdn? [me]
    (let [ct (c/lcase me)]
      (and (c/embeds? ct "multipart/report")
           (c/embeds? ct "disposition-notification")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn guess-mime-type
  "Guess the MIME/type of file."
  {:tag String}
  ([file] (guess-mime-type file nil))
  ([file dft]
   (let [mc (.matcher _ext-regex
                      (c/lcase (i/fname file)))
         ex (if (.matches mc) (.group mc 1))]
     (c/stror (if (c/hgl? ex)
                ((mime-cache<>) (keyword ex))) (str dft)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn guess-content-type
  "Guess the content-type of file."
  {:tag String}
  ([file enc] (guess-content-type file enc nil))
  ([file] (guess-content-type file "utf-8" nil))
  ([file enc dft]
   (let [enc (c/stror enc "utf-8")
         ct (c/stror* (guess-mime-type file) dft _ctype-dft)]
     (if-not
       (cs/starts-with? ct "text/") ct (str ct "; charset=" enc)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- setup
  [file]
  (let [p (u/load-java-props file)]
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
      (try (reset! _mime-types (MimetypesFileTypeMap. inp))
           (catch UnsupportedEncodingException _
             (u/throw-IOE "Failed to parse mime.properties."))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn setup-cache
  "Load file mime-types as a map."
  [file]
  (or (try (c/do#true (setup file))
           (catch Throwable _ false))
      (setup (i/res->url "czlab/niou/etc/mime.properties"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn normalize-email
  "Check email address."
  ^String [email]
  (let [email (str email)]
    (cond (empty? email)
          email
          (or (nil? (cs/index-of email \@))
              (not= (cs/last-index-of email \@)
                    (cs/index-of email \@)))
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
