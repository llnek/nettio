;; Copyright © 2013-2020, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.nettio.ranges

  (:require [czlab.nettio.core :as n]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c :refer [n# is?]])

  (:import [io.netty.channel ChannelHandlerContext]
           [io.netty.handler.stream ChunkedInput]
           [io.netty.handler.codec.http
            HttpHeaderNames
            HttpHeaderValues
            HttpHeaders
            HttpResponse
            HttpResponseStatus]
           [java.util
            ArrayList
            Arrays
            List
            Comparator]
           [io.netty.buffer
            Unpooled
            ByteBuf
            ByteBufAllocator]
           [java.io
            InputStream
            IOException
            Closeable
            File
            RandomAccessFile
            ByteArrayInputStream]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^String DEF-BD "21458390-ebd6-11e4-b80c-0800200c9a66")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- ck-size
  [r]
  `(let [{:keys [~'start ~'end]} ~r] (+ (- ~'end ~'start) 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- num-range<>
  [s e] `(array-map :start ~s :end ~e))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- tol
  [obj] `(Long/valueOf (c/strim ~obj)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- xfile?
  [x]
  `(condp instance? ~x File true RandomAccessFile true false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- cmpl
  [^long a ^long b] (if (> a b) 1 (if (< a b) -1 0)))
  ;(.compareTo (Long/valueOf a) b))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- xfiles??
  [src]
  (c/condp?? instance? src
    File [(RandomAccessFile. ^File src "r") (i/fsize src)]
    RandomAccessFile [src (.length ^RandomAccessFile src)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord ByteRangeChunk [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ck-readable-bytes
  [r]
  (- (ck-size r) (c/mu-int (:range-pos r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- has-next?
  [rgObj]
  (let [{:keys [ranges
                current]} rgObj
        cur (c/mu-int current)]
    (and (< cur (n# ranges))
         (pos? (ck-readable-bytes (nth ranges cur))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ck-pack
  [rg out offset]
  (letfn
    [(ck-read [r out pos len]
       (let [{:keys [start range-pos source]} r
             t (+ start (c/mu-int range-pos))]
         (condp instance? source
           InputStream
           (let [s (c/cast? InputStream source)]
             (.reset s)
             (.skip s t)
             (.read s out pos len))
           RandomAccessFile
           (let [f (c/cast? RandomAccessFile source)]
             (.seek f t)
             (.read f out pos len))
           -1)))]
    (let
      [{:keys [preamble preamble-pos]} rg
       pos (c/mu-int* offset)
       plen (n# preamble)
       bfsz (n# out)
       cnt (c/mu-int* 0)
       ppos (c/mu-int* (c/mu-int preamble-pos))]
      (while (and (< (c/mu-int pos) bfsz)
                  (< (c/mu-int ppos) plen))
        (aset ^bytes out
              (c/mu-int pos)
              (aget ^bytes preamble (c/mu-int ppos)))
        (c/mu-int ppos + 1)
        (c/mu-int pos + 1)
        (c/mu-int cnt + 1))
      ;update pos
      (c/mu-int preamble-pos (c/mu-int ppos))
      (when (< (c/mu-int pos) bfsz)
        (let [r (ck-readable-bytes rg)
              d (- bfsz (c/mu-int pos))
              len (if (> r d) d r)
              c (ck-read rg out (c/mu-int pos) (int len))
              {:keys [start range-pos length]} rg]
          (u/assert-IOE (not (neg? c))
                        "error reading file: length=%s, seek=%s"
                        length
                        (+ start (c/mu-int range-pos)))
          (c/mu-int range-pos + c)
          (c/mu-int cnt + c)))
      (c/mu-int cnt))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord HttpRangesObj []
  c/Finzable
  (finz [rgObj]
    (assoc rgObj :finz? true))
  ChunkedInput
  (readChunk [_ ^ChannelHandlerContext ctx]
    (.readChunk _ (.alloc ctx)))
  (readChunk [me ^ByteBufAllocator allocator]
    (let [buff (byte-array (* 2 c/FourK))
          blen (alength buff)
          {:keys [ranges
                  bytes-read current]} me
          rlen (n# ranges)
          cnt (c/mu-int* 0)
          cur (c/mu-int* (c/mu-int current))]
      (while (and (< (c/mu-int cnt) blen)
                  (< (c/mu-int cur) rlen))
        (when-some [rg (nth ranges (c/mu-int cur))]
          (c/debug "reading-chunk: range=%s - %s." (c/mu-int cur) rg)
          (if (pos? (ck-readable-bytes rg))
            (do (c/mu-int cnt
                          + (ck-pack rg
                                     buff (c/mu-int cnt)))
                (c/debug "reading-chunk: count=%s." (c/mu-int cnt)))
            (c/mu-int cur + 1))))
      (c/mu-int current (c/mu-int cur))
      (when (pos? (c/mu-int cnt))
        (c/mu-int bytes-read + (c/mu-int cnt))
        (c/debug "reading-chunk: count=%s.", (c/mu-int cnt))
        (c/debug "reading-chunk: read= %s." (c/mu-int bytes-read))
        (Unpooled/wrappedBuffer buff 0 (int (c/mu-int cnt))))))
  (length [me] (c/mu-int (:total-bytes me)))
  (progress [me] (c/mu-int (:bytes-read me)))
  (isEndOfInput [me] (not (has-next? me)))
  (close [me]
    (if-some [s (c/cast? Closeable (:source me))] (i/klose s)))
  Object
  (finalize [me] (when (:finz? me)
                   (.close me)
                   (c/debug "rangeObject finzed!"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- brange-chunk<>

  [src cType start end]

  (let [[s ln] (cond (xfile? src) (xfiles?? src)
                     (bytes? src) [(doto (i/istream src)
                                     (.mark 0)) (c/n# src)]
                     :else (u/throw-BadArg  "bad source"))]
    (c/object<> ByteRangeChunk
                :length ln
                :source s
                :preamble (byte-array 0)
                :range-pos (c/mu-int* 0)
                :preamble-pos (c/mu-int* 0)
                :start start :end end :ctype cType)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- brange-chunk<+>

  [src ctype start end]

  (let [{:as C
         :keys [ctype start end length]}
        (brange-chunk<> src ctype start end)]
    (assoc C
           :preamble
           (i/x->bytes (c/sbf+ (c/sbf<>)
                               "--"
                               DEF-BD
                               "\r\n"
                               "Content-Type: " ctype
                               "\r\n"
                               "Content-Range: bytes "
                               start "-" end "/" length "\r\n\r\n")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- range-init

  [rgObj rangeStr]

  (c/debug "range= %s\nrg-obj= %s."
           rangeStr (i/fmt->edn rgObj))
  (letfn
    [(overlap? [r1 r2]
       (let [{s1 :start e1 :end} r1
             {s2 :start e2 :end} r2]
         (or (and (>= s1 s2) (<= s1 e2))
             (and (>= e1 s2) (<= s1 e2)))))
     (ck-total-size [r]
       (+ (ck-size r) (n# (:preamble r))))
     (calc-all [rgObj]
       (let [z (c/mu-long* 0)]
         (doseq [r (:ranges rgObj)]
           (c/mu-long z + (ck-total-size r))) (c/mu-long z)))
     (merge' [r1 r2]
       (let [{s1 :start e1 :end} r1
             {s2 :start e2 :end} r2]
         (num-range<> (if (< s1 s2) s1 s2)
                      (if (> e1 e2) e1 e2))))
     (sanitize-ranges [rgObj chunks]
       (let [rc (ArrayList.)
             len (n# chunks)
             chunks (u/sortby :start cmpl chunks)]
         (if (pos? len)
           (.add rc (c/_1 chunks)))
         (loop [n 1]
           (if (>= n len)
             (into [] rc)
             (let [E (c/dec* (.size rc))
                   r1 (.get rc E)
                   c1 (nth chunks n)]
               (if-not (overlap? c1 r1)
                 (.add rc c1)
                 (.set rc E (merge' c1 r1)))
               (recur (c/inc* n)))))))]
    (let
      [{:keys [flen]} rgObj
       last (c/dec* flen)
       chunks
       (c/preduce<vec>
         #(let
            [rs (c/strim %2)
             [start end]
             (let
               [rg (c/split rs "-")]
               (if (cs/starts-with? rs "-")
                 [(- last (tol (subs rs 1))) last]
                 [(tol (c/_1 rg)) (if (c/one+? rg) (tol (nth rg 1)) last)]))
             end (if (> end last) last end)]
            (if (<= start end)
              (conj! %1 (num-range<> start end)) %1))
         (-> (.replaceFirst ^String rangeStr
                            "^\\s*bytes=" "")
             c/strim
             (c/split ",")))]
      (when-not (empty? chunks)
        (let
          [cs (sanitize-ranges rgObj chunks)
           many? (c/one+? cs)
           {:keys [source ctype]} rgObj
           R (mapv #(let
                      [{:keys [start end]} %]
                      (if-not many?
                        (brange-chunk<> source ctype start end)
                        (brange-chunk<+> source ctype start end))) cs)]
          (u/assert-BadData (not-empty R) "Invalid byte ranges")
          (assoc rgObj
                 :ranges R
                 :total-bytes (c/mu-int* (calc-all rgObj))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fmt-error

  "Format headers for range error."
  {:tag HttpHeaders
   :arglists '([hds body])}
  [hds body]
  {:pre [(c/is? HttpHeaders hds)]}

  (let [sz (cond (bytes? body) (alength ^bytes body)
                 (i/file? body) (i/fsize body) :else 0)
        last (if (pos? sz) (- sz 1) 0)]
    (.set ^HttpHeaders hds (n/h1hdr* ACCEPT_RANGES) (n/h1hdv* BYTES))
    (.set ^HttpHeaders hds (n/h1hdr* CONTENT_RANGE) (str "bytes 0-" last "/" sz))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fmt-success

  "Format headers for range result."
  {:tag HttpHeaders
   :arglists '([hds rgObj])}
  [hds rgObj]
  {:pre [(c/is? HttpHeaders hds)]}

  (let [{:keys [ranges flen]} rgObj]
    (.set ^HttpHeaders hds (n/h1hdr* ACCEPT_RANGES) (n/h1hdv* BYTES))
    (if (c/one? ranges)
      (let [{:keys [start end]} (c/_1 ranges)]
        (.set ^HttpHeaders hds
              (n/h1hdr* CONTENT_RANGE)
              (str (n/h1hdv* BYTES)
                   " " start "-" end "/" flen))
        (.set ^HttpHeaders hds
              (n/h1hdr* CONTENT_TYPE)
              (str "multipart/byteranges; boundary=" DEF-BD))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-ranges<>

  "Maybe handle http byte ranges."
  {:arglists '([range source]
               [range cType source])}

  ([range source]
   (http-ranges<> range "application/octet-stream" source))

  ([range cType source]
   (c/debug "range= %s, type= %s, source= %s." range cType source)
   (when (c/matches? range "^\\s*bytes=[0-9,-]+")
     (u/try!!!
       (let [[s ln] (cond (xfile? source) (xfiles?? source)
                          (bytes? source) [source (n# source)]
                          :else (u/throw-BadArg "bad source"))]
         (c/debug "file-range-object: len = %s, source = %s." ln s)
         (range-init (c/object<> HttpRangesObj
                                 :ranges nil
                                 :flen ln
                                 :source s
                                 :finz? false
                                 :ctype cType
                                 :bytes-read (c/mu-int)
                                 :current (c/mu-int)
                                 :total-bytes (c/mu-int)) range))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

