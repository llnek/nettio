;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.nettio.ranges

  (:require [czlab.nettio.core :as nc]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal
             [util :as u]
             [log :as l]
             [io :as i]
             [core :as c :refer [n# is?]]])

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
(def ^String DEF-BD "21458390-ebd6-11e4-b80c-0800200c9a66")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defmacro- num-range<> [s e] `(hash-map :start ~s :end ~e))
(c/defmacro- tol [obj] `(Long/valueOf (c/strim ~obj)))
(c/defmacro- xfile? [x]
  `(condp instance? ~x RandomAccessFile true File true false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- comp-long
  [^long a ^long b]
  (.compareTo (Long/valueOf a) b))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- xfiles??
  [src]
  (let [f (c/cast? File src)
        rf (c/cast? RandomAccessFile src)]
    [(or rf (RandomAccessFile. f "r"))
     (if f (.length f) (.length rf))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpHeadersAPI
  ""
  (fmt-error [_ body] "")
  (fmt-success [_ rgObj] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol ByteRangeChunkAPI
  ""
  (ck-size [_] "")
  (ck-total-size [_] "")
  (ck-readable-bytes [_] "")
  (ck-pack [_ out offset] "")
  (ck-read [_ out pos len] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol RangeObjAPI
  ""
  (has-next? [_] "")
  (finz! [_] "")
  (merge-ranges [_ r1 r2] "")
  (total-bytes [_] "")
  (range-init [_ s] "")
  (sanitize-ranges [_ chunks] "")
  (maybe-intersect? [_ r1 r2] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord ByteRangeChunk [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord HttpRangesObj []
  ChunkedInput
  (readChunk [me ^ChannelHandlerContext ctx]
    (.readChunk me (.alloc ctx)))
  (readChunk [me ^ByteBufAllocator allocator]
    (let [buff (byte-array (* 2 c/FourK))
          mlen (n# buff)
          {:keys [ranges bytes-read current]} me
          cnt (c/int-var* 0)
          cur (c/int-var* (c/int-var current))]
      (while (and (< (c/int-var cnt) mlen)
                  (< (c/int-var cur) (c/n# ranges)))
        (when-some [rg (nth ranges (c/int-var cur))]
          (l/debug "reading-chunk: range=%s - %s." (c/int-var cur) rg)
          (if (c/spos? (ck-readable-bytes rg))
            (do (c/int-var cnt
                           + (ck-pack rg
                                      buff (c/int-var cnt)))
                (l/debug "reading-chunk: count=%s." (c/int-var cnt)))
            (c/int-var cur + 1))))
      (c/int-var current (c/int-var cur))
      (when (pos? (c/int-var cnt))
        (c/int-var bytes-read + (c/int-var cnt))
        (l/debug "reading-chunk: count=%s.", (c/int-var cnt))
        (l/debug "reading-chunk: read= %s." (c/int-var bytes-read))
        (Unpooled/wrappedBuffer buff 0 (int (c/int-var cnt))))))
  (length [me] (c/int-var (:total-bytes me)))
  (progress [me] (c/int-var (:bytes-read me)))
  (isEndOfInput [me] (not (has-next? me)))
  (close [me]
    (if-some [s (c/cast? Closeable (:source me))] (i/klose s)))
  Object
  (finalize [me] (when (:finz? me)
                   (.close me)
                   (l/debug "rangeObject finzed!"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol ByteRangeChunkAPI
  ByteRangeChunk
  (ck-size [_]
    (let [{:keys [start end]} _] (+ (- end start) 1)))
  (ck-total-size [_]
    (+ (ck-size _) (n# (:preamble _))))
  (ck-readable-bytes [_]
    (- (ck-size _) (c/int-var (:range-pos _))))
  (ck-read [_ out pos len]
    (let [{:keys [start range-pos source]} _
          t (+ start (c/int-var range-pos))]
      (condp instance? source
        RandomAccessFile (do (.seek ^RandomAccessFile source t)
                             (.read ^RandomAccessFile source out pos len))
        InputStream (do (.reset ^InputStream source)
                        (.skip ^InputStream source t)
                        (.read ^InputStream source out pos len))
        -1)))
  (ck-pack [me out offset]
    (let [{:keys [preamble preamble-pos]} me
          pos (c/int-var* offset)
          plen (n# preamble)
          bfsz (n# out)
          cnt (c/int-var* 0)
          ppos (c/int-var* (c/int-var preamble-pos))]
      (while (and (< (c/int-var pos) bfsz)
                  (< (c/int-var ppos) plen))
        (aset ^bytes out
              (c/int-var pos)
              (aget ^bytes preamble (c/int-var ppos)))
        (c/int-var ppos + 1)
        (c/int-var pos + 1)
        (c/int-var cnt + 1))
      ;update pos
      (c/int-var preamble-pos (c/int-var ppos))
      (when (< (c/int-var pos) bfsz)
        (let [r (ck-readable-bytes me)
              d (- bfsz (c/int-var pos))
              len (if (> r d) d r)
              c (ck-read me out (c/int-var pos) (int len))
              {:keys [start range-pos length]} me]
          (if (neg? c)
            (u/throw-IOE
              "error reading file: length=%s, seek=%s"
              length (+ start (c/int-var range-pos))))
          (c/int-var range-pos + c)
          (c/int-var cnt + c)))
      (c/int-var cnt))))

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
                :range-pos (c/int-var* 0)
                :preamble-pos (c/int-var* 0)
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
(extend-protocol RangeObjAPI
  HttpRangesObj
  ;toggle for finalize to work
  (finz! [_] (assoc _ :finz? true))
  (has-next? [rgObj]
    (let [{:keys [ranges current]} rgObj
          cur (c/int-var current)]
      (and (< cur (n# ranges))
           (pos? (ck-readable-bytes
                   (nth ranges cur))))))
  (maybe-intersect? [_ r1 r2]
    (let [{s1 :start e1 :end} r1
          {s2 :start e2 :end} r2]
      (or (and (>= s1 s2) (<= s1 e2))
          (and (>= e1 s2) (<= s1 e2)))))
  (merge-ranges [_ r1 r2]
    (let [{s1 :start e1 :end} r1
          {s2 :start e2 :end} r2]
      (num-range<> (if (< s1 s2) s1 s2)
                   (if (> e1 e2) e1 e2))))
  (total-bytes [rgObj]
    (let [z (c/long-var* 0)]
      (doseq [r (:ranges rgObj)]
        (c/long-var z + (ck-total-size r))) (c/long-var z)))
  (sanitize-ranges [rgObj chunks]
    (let [chunks (u/sortby :start comp-long chunks)
          rc (ArrayList.)
          len (n# chunks)]
      (if (pos? len)
        (.add rc (c/_1 chunks)))
      (loop [n 1]
        (if (>= n len)
          (into [] rc)
          (let [E (- (.size rc) 1)
                r1 (.get rc E)
                c1 (nth chunks n)]
            (if-not (maybe-intersect? rgObj c1 r1)
              (.add rc c1)
              (.set rc E (merge-ranges rgObj c1 r1)))
            (recur (+ 1 n)))))))
  (range-init [rgObj rangeStr]
    (l/debug "range-string= %s\nrange-object= %s." rangeStr (i/fmt->edn rgObj))
    (let [{:keys [flen]} rgObj
          last (- flen 1)
          chunks
          (c/preduce<vec> #(let [rs (c/strim %2)
                                 [start end]
                                 (let [rg (c/split rs "-")]
                                   (if (cs/starts-with? rs "-")
                                     [(- last (tol (subs rs 1))) last]
                                     [(tol (c/_1 rg)) (if (c/one+? rg) (tol (nth rg 1)) last)]))
                                 end (if (> end last) last end)]
                             (if (<= start end)
                               (conj! %1 (num-range<> start end)) %1))
                          (-> (.replaceFirst ^String rangeStr
                                             "^\\s*bytes=", "") c/strim (c/split ",")))]
      (when-not (empty? chunks)
        (let [cs (sanitize-ranges rgObj chunks)
              many? (c/one+? cs)
              {:keys [source ctype]} rgObj
              R (mapv #(let [{:keys [start end]} %]
                         (if-not many?
                           (brange-chunk<> source ctype start end)
                           (brange-chunk<+> source ctype start end))) cs)]
          (if (empty? R) (u/throw-BadData "Invalid byte ranges"))
          (assoc rgObj
                 :ranges R
                 :total-bytes (c/int-var* (total-bytes rgObj))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol HttpHeadersAPI
  HttpHeaders
  (fmt-error [hds body]
    (let [sz (cond (bytes? body) (n# body)
                   (i/file? body) (i/fsize body) :else 0)
          last (if (pos? sz) (- sz 1) 0)]
      (nc/set-header* hds
                      [HttpHeaderNames/ACCEPT_RANGES HttpHeaderValues/BYTES
                       HttpHeaderNames/CONTENT_RANGE (str "bytes 0-" last "/" sz)])))
  (fmt-success [hds rgObj]
    (let [{:keys [ranges flen]} rgObj]
      (nc/set-header hds
                     HttpHeaderNames/ACCEPT_RANGES HttpHeaderValues/BYTES)
      (if (c/one? ranges)
        (let [{:keys [start end]} (c/_1 ranges)]
          (nc/set-header* hds
                          [HttpHeaderNames/CONTENT_RANGE
                           (str HttpHeaderValues/BYTES
                                " " start "-" end "/" flen)
                           HttpHeaderNames/CONTENT_TYPE
                           (str "multipart/byteranges; boundary="  DEF-BD)]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn http-ranges<>
  "Maybe handle http byte ranges."
  ([rangeStr source]
   (http-ranges<> rangeStr "application/octet-stream" source))
  ([rangeStr cType source]
   (l/debug "rangeStr = %s, type=%s, source=%s." rangeStr cType source)
   (when (c/matches? rangeStr "^\\s*bytes=[0-9,-]+")
     (u/try!!!
       (let [[s ln] (cond (xfile? source) (xfiles?? source)
                          (bytes? source) [source (n# source)]
                          :else (u/throw-BadArg "bad source"))]
         (l/debug "file-range-object: len = %s, source = %s." ln s)
         (-> (c/object<> HttpRangesObj
                         :total-bytes (c/int-var)
                         :bytes-read (c/int-var)
                         :current (c/int-var)
                         :ranges nil
                         :flen ln :source s
                         :finz? false :ctype cType)
             (range-init rangeStr)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

