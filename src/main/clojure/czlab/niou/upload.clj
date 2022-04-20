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

(ns czlab.niou.upload

  "Functions to handle form uploads."

  (:require [clojure.java.io :as io]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.niou.core :as nc]
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
(declare get-all-items)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord ULFormItems []
  Object
  (toString [me]
    (c/sreduce<>
      (fn [b ^FileItem n]
        (if (pos? (c/sbfz b)) (c/sbf+ b "\n"))
        (c/sbf+ b
                "name="
                (.getFieldName n)
                ",data="
                (if (.isFormField n)
                  (.toString n) (.getName n))))
      (get-all-items me))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-all-items

  "Get all the form items."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFormItems f)]}

  (:items f))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-all-files

  "Get all the file items in the form."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFormItems f)]}

  (filter #(not (.isFormField ^FileItem %)) (:items f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-all-fields

  "Get all the field items in the form."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFormItems f)]}

  (filter #(.isFormField ^FileItem %) (:items f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn items-as-map

  "Get all form items as a map."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFormItems f)]}

  (c/preduce<map>
    #(assoc! %1
             (.getFieldName ^FileItem %2) %2)
    (get-all-items f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn clear-items

  "Remove all items."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFormItems f)]}

  (assoc f :items []))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn count-items

  "Count items in the form."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFormItems f)]}

  (count (get-all-items f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-item

  "Add a new item to the form."
  {:arglists '([f])}
  [f x]
  {:pre [(c/is? ULFormItems f)]}

  (update-in f [:items] conj x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ul-items-finz

  "Clean up all items."
  {:arglists '([ul])}
  [ul]

  (doseq [n (get-all-items ul)]
    (.delete ^FileItem n))
  (clear-items ul))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn form-items<>

  "Create a new ULFormItems."
  {:arglists '([][items])}

  ([]
   (form-items<> nil))

  ([items]
   (c/object<> ULFormItems :items (vec items))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord ULFileItem []
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
(defn get-field-file

  "Get the item's file reference, if any."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFileItem f)]}

  (.fileRef ^XData (:body f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-field-name-lc

  "Get the item's field name in lowercase."
  {:arglists '([f])}
  [f]
  {:pre [(c/is? ULFileItem f)]}

  (c/lcase (:field f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn file-item<>

  "Create a wrapper for this file-item."
  {:tag FileItem
   :arglists '([isField? ctype headers field fname data])}
  [isField? ctype headers field fname data]

  (assert (or (nil? data)
              (c/is? XData data)) "Bad file-item.")
  (c/object<> ULFileItem
              :is-field? isField?
              :headers headers
              :ctype ctype
              :field field
              :fname fname
              :enc (mm/charset?? ctype)
              :body (nc/toXData data true)))

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
  ^FileItem
  [^FileItemStream item]

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
                                                          "czlab" "tmp" (i/file-repo))]
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
  {:arglists '([gist body])}
  [gist body]

  (let [iter (->> (nc/toXData body)
                  (process-form gist))]
    (loop [bag (form-items<>)]
      (if-not (and iter
                   (.hasNext iter))
        bag
        (recur (add-item bag
                         (eval-field (.next iter))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

