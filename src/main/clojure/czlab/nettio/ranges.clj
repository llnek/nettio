;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.ranges

  (:require [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

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
(defmacro ^:private num-range<> [s e] `(hash-map :start ~s :end ~e))
(defmacro ^:private tol [obj] `(Long/valueOf (s/strim ~obj)))
(defmacro ^:private xfile? [x]
  `(condp instance? ~x RandomAccessFile true File true false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- xfiles?? [src]
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
  (calc-total-bytes [_] "")
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
          mlen (count buff)
          {:keys [^List ranges
                  bytes-read current]} me
          cnt (c/int-var* 0)
          cur (c/int-var* (c/int-var current))]
      (while (and (< (c/int-var cnt) mlen)
                  (< (c/int-var cur) (.size ranges)))
        (when-some [rg (.get ranges (c/int-var cur))]
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
    (+ (ck-size _) (count (:preamble _))))
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
          plen (count preamble)
          bfsz (count out)
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
(defn- byte-range-chunk<>
  [src ctype start end]
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
                :start start :end end :ctype ctype)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- multi-byte-range-chunk<>
  [src ctype start end]
  (let [{:as C
         :keys [ctype start end length]}
        (byte-range-chunk<> src ctype start end)]
    (assoc C
           :preamble
           (i/x->bytes (s/sbf+ (s/sbf<>)
                               "--"
                               DEF-BD
                               "\r\n"
                               "Content-Type: " ctype
                               "\r\n"
                               "Content-Range: bytes "
                               start "-" end "/" length "\r\n\r\n")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- is-valid?
  "" [rangeStr]
  (and (s/hgl? rangeStr)
       (.matches ^String rangeStr "^\\s*bytes=[0-9,-]+")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol RangeObjAPI
  HttpRangesObj
  (finz! [_] (assoc _ :finz? true))
  (has-next? [rgObj]
    (let [{:keys [^List ranges current]} rgObj]
      (and (< (c/int-var current) (.size ranges))
           (pos? (ck-readable-bytes
                   (.get ranges (c/int-var current)))))))
  (maybe-intersect? [rgObj r1 r2]
    (or (and (>= (:start r1) (:start r2))
             (<= (:start r1) (:end r2)))
        (and (>= (:end r1) (:start r2))
             (<= (:start r1) (:end r2)))))
  (merge-ranges [rgObj r1 r2]
    (num-range<>
      (if (< (:start r1) (:start r2)) (:start r1) (:start r2))
      (if (> (:end r1) (:end r2)) (:end r1) (:end r2))))
  (calc-total-bytes [rgObj]
    (let [z (c/long-var* 0)
          {:keys [^List ranges]} rgObj]
      (doseq [r ranges]
        (c/long-var z + (ck-total-size r))) (c/long-var z)))
  (sanitize-ranges [rgObj chunks]
    (let [sorted
          (u/sortby :start
                    #(.compareTo (Long/valueOf
                                   ^long %1) ^long %2) chunks)
          rc (ArrayList.)
          slen (count sorted)]
      (.add rc (first sorted))
      (loop [n 1]
        (if (>= n slen)
          rc
          (let [r1 (.get rc (- (.size rc) 1))
                c1 (nth sorted n)]
            (if (maybe-intersect? rgObj c1 r1)
              (.set rc (- (.size rc) 1) (merge-ranges rgObj c1 r1))
              (.add rc c1))
            (recur (+ 1 n)))))))
  (range-init [rgObj rangeStr]
    (l/debug "range-string= %s\nrange-object= %s." rangeStr (i/fmt->edn rgObj))
    (let [rvs (-> (.replaceFirst ^String rangeStr
                                 "^\\s*bytes=", "") s/strim (s/split ","))
          {:keys [^List ranges flen]} rgObj
          last (- flen 1)
          chunks (ArrayList.)]
      (doseq [r rvs :let [rs (s/strim r)]]
        (let [[start end]
              (if (cs/starts-with? rs "-")
                [(- last (tol (subs rs 1))) last]
                (let [rg (s/split rs "-")]
                  [(tol (first rg))
                   (if (> (count rg) 1)
                     (tol (nth rg 1)) last)]))
              end (if (> end last) last end)]
          (if (<= start end)
            (.add chunks (num-range<> start end)))))
      (.clear ranges)
      (when-not (.isEmpty chunks)
        (let [^List cs (sanitize-ranges rgObj chunks)
              {:keys [source ctype]} rgObj
              many? (> (.size cs) 1)]
          (doseq [r cs
                  :let [{:keys [start end]} r]]
            (.add ranges
              (if many?
                (multi-byte-range-chunk<> source ctype start end)
                (byte-range-chunk<> source ctype start end))))))
      (if (.isEmpty ranges)
        (u/throw-BadData "Invalid byte ranges"))
      (assoc rgObj :total-bytes (c/int-var* (calc-total-bytes rgObj))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol HttpHeadersAPI
  HttpHeaders
  (fmt-error [hds body]
    (let [sz (cond (c/is? File body) (i/fsize body)
                   (bytes? body) (count body) :else 0)
          last (if (pos? sz) (- sz 1) 0)]
      (.set hds
            HttpHeaderNames/ACCEPT_RANGES HttpHeaderValues/BYTES)
      (.set hds
            HttpHeaderNames/CONTENT_RANGE (str "bytes 0-" last "/" sz))))
  (fmt-success [hds rgObj]
    (let [{:keys [^List ranges]} rgObj]
      (.set hds
            HttpHeaderNames/ACCEPT_RANGES HttpHeaderValues/BYTES)
      (if (= 1 (.size ranges))
        (let [{:keys [start end]} (.get ranges 0)]
          (.set hds
                HttpHeaderNames/CONTENT_RANGE
                (str HttpHeaderValues/BYTES
                     " " start "-" end "/" (:flen rgObj))))
        (.set hds
              HttpHeaderNames/CONTENT_TYPE
              (str "multipart/byteranges; boundary="  DEF-BD))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- http-ranges<>
  [ctype source]
  (let [[s ln] (cond (xfile? source) (xfiles?? source)
                     (bytes? source) [source (count source)]
                     :else (u/throw-BadArg "bad source"))]
    (l/debug "file-range-object: len = %s, source = %s." ln s)
    (c/object<> HttpRangesObj
                :total-bytes (c/int-var)
                :bytes-read (c/int-var)
                :current (c/int-var)
                :ranges (ArrayList.)
                :finz? false
                :flen ln :source s :ctype ctype)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn eval-ranges
  ""
  ([rangeStr cType source]
   (l/debug "rangeStr = %s, type=%s, source=%s." rangeStr cType source)
   (when (is-valid? rangeStr)
     (try (-> (http-ranges<> cType source)
              (range-init rangeStr))
          (catch Throwable _ (l/exception _) nil))))
  ([rangeStr source]
   (eval-ranges rangeStr "application/octet-stream" source)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

