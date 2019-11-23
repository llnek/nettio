;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "Functions to handle form uploads."
    :author "Kenneth Leung"}

  czlab.niou.upload

  (:require [clojure.java.io :as io]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.niou.mime :as mm])

  (:import [org.apache.commons.io.output DeferredFileOutputStream]
           [org.apache.commons.fileupload.util Streams]
           [org.apache.commons.fileupload
            FileUploadException
            FileItemHeaders
            FileItemStream
            FileItem
            FileUpload
            UploadContext
            FileItemIterator]
           [czlab.niou Headers]
           [czlab.basal XStream XData]
           [java.io File FileInputStream]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def
  TEST-FORM-MULTIPART
  (str "-----1234\r\n"
       "Content-Disposition: form-data; name=\"file1\"; filename=\"foo1.tab\"\r\n"
       "Content-Type: text/plain\r\n"
       "\r\n"
       "file content(1)\n"
       "\r\n"
       "-----1234\r\n"
       "Content-Disposition: form-data; name=\"file2\"; filename=\"foo2.tab\"\r\n"
       "Content-Type: text/plain\r\n"
       "\r\n"
       "file content(2)\n"
       "\r\n"
       "-----1234\r\n"
       "Content-Disposition: form-data; name=\"field\"\r\n"
       "\r\n"
       "fieldValue\r\n"
       "-----1234\r\n"
       "Content-Disposition: form-data; name=\"multi\"\r\n"
       "\r\n"
       "value1\r\n"
       "-----1234\r\n"
       "Content-Disposition: form-data; name=\"multi\"\r\n"
       "\r\n"
       "value2\r\n"
       "-----1234--\r\n"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ULFormItems
  (get-all-fields [_] "")
  (get-all-files [_] "")
  (get-all-items [_] "")
  (items-as-map [_] "")
  (add-item [_ x] "")
  (count-items [_] "")
  (clear-items [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ULFileItem
  (get-field-file [_] "")
  (get-field-name-lc [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ul-items-finz

  "Clean up all items."
  [ul]

  (doseq [n (get-all-items ul)]
    (.delete ^FileItem n))
  (clear-items ul))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord ULFormItemsObj []
  Object
  (toString [me]
    (c/sreduce<>
      (fn [b ^FileItem n]
        (if (pos? (c/sbfz b)) (c/sbf+ b "\n"))
        (c/sbf+ b
                "name="
                (.getFieldName n)
                " ,data="
                (if (.isFormField n)
                  (.toString n) (.getName n))))
      (get-all-items me)))
  ULFormItems
  (get-all-items [me] (:items me))
  (get-all-files [me]
    (filter #(not (.isFormField ^FileItem %)) (:items me)))
  (get-all-fields [me]
    (filter #(.isFormField ^FileItem %) (:items me)))
  (items-as-map [me]
    (c/preduce<map>
      #(assoc! %1
               (.getFieldName ^FileItem %2) %2)
      (get-all-items me)))
  (clear-items [me] (assoc me :items []))
  (count-items [me] (count (get-all-items me)))
  (add-item [me x] (update-in me [:items] conj x)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn form-items<>

  "Create a new URLFormItemsObj."

  ([] (form-items<> nil))
  ([items]
   (c/object<> ULFormItemsObj :items (vec items))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord ULFileItemObj []
  ULFileItem
  (get-field-file [me] (.fileRef ^XData (:body me)))
  (get-field-name-lc [me] (c/lcase (:field me)))
  FileItem
  (delete [me] (.dispose ^XData (:body me)))
  (get [me] (.getBytes ^XData (:body me)))
  (getContentType [me] (:ctype me))
  (getFieldName [me] (str (:field me)))
  (getInputStream [me] (.stream ^XData (:body me)))
  (getName [me] (str (:fname me)))
  (getSize [me] (.size ^XData (:body me)))
  (getString [me] (.getString me (:enc me)))
  (getString [me enc]
    (if (.isInMemory me)
      (i/x->str (.get me) (:enc me))))
  (isFormField [me] (:is-field? me))
  (isInMemory [me] (not (.isFile ^XData (:body me))))
  (getOutputStream [_] (u/throw-IOE "not supported"))
  (setFieldName [_ n] (u/throw-IOE "not supported"))
  (setFormField [_ b] (u/throw-IOE "not supported"))
  (write [_ f] (u/throw-IOE "not suported"))
  (getHeaders [me] (:headers me))
  (setHeaders [_ h] (u/throw-IOE "not supported")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn file-item<>

  "Create a wrapper for this file-item."
  ^FileItem [isField? ctype headers field fname data]

  (assert (or (nil? data)
              (c/is? XData data)) "Bad file-item.")
  (c/object<> ULFileItemObj
              :is-field? isField?
              :headers headers
              :body (or (c/cast? XData data)
                        (XData. data))
              :ctype ctype
              :field field
              :fname fname
              :enc (mm/charset?? ctype)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- hconv
  [^FileItemHeaders hds]

  (let [h (Headers.)
        it (some-> hds .getHeaderNames)]
    (while (and it (.hasNext it))
      (let [n (.next it)
            vs (.getHeaders hds n)]
        (while (.hasNext vs)
          (.add h n (.next vs))))) h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- eval-field

  "Process a file-item."
  ^FileItem [^FileItemStream item]

  (let [res (XData.)
        ffld? (.isFormField item)]
    (file-item<> ffld?
                 (.getContentType item)
                 (hconv (.getHeaders item))
                 (.getFieldName item)
                 (.getName item)
                 (if ffld?
                   (c/wo* [out (i/baos<>)
                           inp (.openStream item)]
                     (Streams/copy inp out false)
                     (.reset res out))
                   (c/wo* [inp (.openStream item)
                           out (DeferredFileOutputStream. i/*membuf-limit*
                                                          "czlab" "tmp" i/*file-repo*)]
                     (Streams/copy inp out false)
                     (.flush out)
                     (.reset res (or (.getFile out) (.getData out))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- process-form

  ^FileItemIterator
  [gist body]

  (->> (reify UploadContext
         (getContentLength [_] (:clen gist))
         (getContentType [_] (:ctype gist))
         (getCharacterEncoding [_]
           (mm/charset?? (:ctype gist)))
         (contentLength [_] (:clen gist))
         (getInputStream [_] (.stream ^XData body)))
       (.getItemIterator
         (proxy [FileUpload][]
           (parseRequest [_]
             (c/trap! FileUploadException "Not supported"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-form-post

  "Parses a http form post, returning the list of items in
  the post.  An item can be a simple form-field or a file-upload."
  [gist body]

  (let [iter (->> (if-not (c/is? XData body)
                    (XData. body false) body)
                  (process-form gist))]
    (loop [bag (form-items<>)]
      (if-not (and iter
                   (.hasNext iter))
        bag
        (recur (add-item bag
                         (eval-field (.next iter))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF
