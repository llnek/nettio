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
(defmacro ^:private num-range<> "" [s e] `(doto {:start ~s :end ~e}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro ^:private tol "" [obj] `(Long/valueOf (s/strim ~obj)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- chunk-size "" [ck]
  (let [{:keys [start end]} @ck] (+ (- end start) 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- chunk-total-size "" [ck]
  (+ (chunk-size ck) (alength ^bytes (:preamble @ck))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- chunk-readable-bytes "" [ck] (- (chunk-size ck) (:range-pos @ck)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- chunk-read "" [ck out pos len]
  (let [{:keys [start range-pos source]} @ck
        target (+ start range-pos)]
    (cond
      (c/is? RandomAccessFile source)
      (let [^RandomAccessFile f source]
        (.seek f target)
        (.read f out pos len))
      (c/is? InputStream source)
      (let [^InputStream inp source]
        (.reset inp)
        (.skip inp target)
        (.read inp out pos len))
      :else -1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- chunk-pack "" [ck out offset]
  (let [^bytes pre (:preamble @ck)
        bufsz (alength ^bytes out)
        plen (alength pre)
        pos (c/int-var* offset)
        cnt (c/int-var* 0)
        ppos (c/int-var* (:preamble-pos @ck))]
    (while (and (< (c/int-var pos) bufsz)
                (< (c/int-var ppos) plen))
      (aset ^bytes out (c/int-var pos) (aget pre (c/int-var ppos)))
      (c/int-var ppos + 1)
      (c/int-var pos + 1)
      (c/int-var cnt + 1))
    (c/assoc!! ck :preamble-pos (c/int-var ppos))
    (when (< (c/int-var pos) bufsz)
      (let [r (chunk-readable-bytes ck)
            d (- bufsz (c/int-var pos))
            len (if (> r d) d r)
            c (chunk-read ck out (c/int-var pos) (int len))
            {:keys [start range-pos length]} @ck]
        (if (neg? c)
          (u/throw-IOE
            "error reading file: length=%s, seek=%s"
            length (+ start range-pos)))
        (c/assoc!! ck :range-pos (+ range-pos c))
        (c/int-var cnt + c)))
    (c/int-var cnt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- byte-range-chunk<> "" [src ctype start end]
  (c/do-with [b (atom
                  {:preamble (byte-array 0)
                   :preamble-pos 0
                   :range-pos 0
                   :source src
                   :length 0
                   :start start
                   :end end
                   :ctype ctype})]
    (let [{:keys [source]} @b
          [s ln]
          (cond
            (c/is? File source)
            (let [f (RandomAccessFile. ^File source "r")]
              [f (.length f)])
            (c/is? RandomAccessFile source)
            (let [^RandomAccessFile f source]
              [f (.length f)])
            (bytes? source)
            (let [^bytes b source
                  inp (io/input-stream b)]
              (.mark inp 0)
              [inp (alength b)])
            :else (u/throw-BadArg  "bad source"))]
      (c/assoc!! b :length ln :source s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- multi-byte-range-chunk<> "" [src ctype start end]
  (c/do-with [br (byte-range-chunk<> src ctype start end)]
    (let [{:keys [ctype start end length]} @br]
      (c/assoc!! br
                 :preamble
                 (i/x->bytes (s/sbf+ (s/sbf<>)
                                     "--"
                                     DEF-BD
                                     "\r\n"
                                     "Content-Type: " ctype
                                     "\r\n"
                                     "Content-Range: bytes "
                                     start "-" end "/" length "\r\n\r\n"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- is-valid? "" [rangeStr]
  (and (s/hgl? rangeStr)
       (.matches ^String rangeStr "^\\s*bytes=[0-9,-]+")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol HttpRanges (list-ranges [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- has-next? [rgObj]
  (let [{:keys [^List ranges current]} rgObj]
    (and (< (c/int-var current) (.size ranges))
         (pos? (chunk-readable-bytes
                 (.get ranges (c/int-var current)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- maybe-intersect? "" [rgObj r1 r2]
  (or (and (>= (:start r1) (:start r2))
           (<= (:start r1) (:end r2)))
      (and (>= (:end r1) (:start r2))
           (<= (:start r1) (:end r2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- merge-ranges "" [rgObj r1 r2]
  (num-range<>
    (if (< (:start r1) (:start r2)) (:start r1) (:start r2))
    (if (> (:end r1) (:end r2)) (:end r1) (:end r2))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- calc-total-bytes "" [rgObj]
  (let [{:keys [^List ranges]} rgObj
        z (c/long-var* 0)]
    (doseq [r ranges]
      (c/long-var z + (chunk-total-size r)))
    (c/long-var z)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- sanitize-ranges "" [rgObj chunks]
  (let [rc (ArrayList.)
        sorted
        (u/sortby :start
                  #(.compareTo (Long/valueOf
                                 ^long %1) ^long %2) chunks)
        slen (count sorted)]
  (.add rc (first sorted))
  (loop [n 1]
    (if (>= n slen)
      rc
      (let [r1 (.get rc (- (.size rc) 1))
            c1 (nth sorted n)]
        (if (maybe-intersect? rgObj c1 r1)
          (.set rc
                (- (.size rc) 1)
                (merge-ranges rgObj c1 r1))
          (.add rc c1))
        (recur (+ 1 n)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- range-init "" [rgObj ^String s]
  (l/debug "range-object = %s" (i/fmt->edn rgObj))
  (let
    [rvs (-> (.replaceFirst s
                            "^\\s*bytes=", "") s/strim (.split ","))
     {:keys [^List ranges flen]} rgObj
     last (- flen 1)
     chunks (ArrayList.)]
    (doseq [r rvs
            :let [rs (s/strim r)]]
      (let
        [[start end]
         (if (cs/starts-with? rs "-")
           [(- last (tol (subs rs 1))) last]
           (let [rg (.split rs "-")]
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
      (assoc rgObj :total-bytes (c/int-var* (calc-total-bytes rgObj)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fmt-error [^HttpHeaders hds body]
  (let [sz (cond
             (c/is? File body)
             (.length ^File body)
             (bytes? body)
             (alength ^bytes body)
             :else 0)
        last (if (pos? sz) (- sz 1) 0)]
    (.set hds
          HttpHeaderNames/ACCEPT_RANGES
          HttpHeaderValues/BYTES)
    (.set hds
          HttpHeaderNames/CONTENT_RANGE
          (str "bytes 0-" last "/" sz))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fmt-success [^HttpHeaders hds rgObj]
  (let [{:keys [^List ranges]} rgObj]
    (.set hds
          HttpHeaderNames/ACCEPT_RANGES
          HttpHeaderValues/BYTES)
    (if (= 1 (.size ranges))
      (let [{:keys [start end]}
            @(.get ranges 0)]
        (.set hds
              HttpHeaderNames/CONTENT_RANGE
              (str HttpHeaderValues/BYTES
                   " "
                   start
                   "-"
                   end
                   "/"
                   (:flen rgObj))))
      (.set hds
            HttpHeaderNames/CONTENT_TYPE
            (str "multipart/byteranges; boundary="  DEF-BD)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord HttpRangesObj []
  ChunkedInput
  (readChunk [me ^ChannelHandlerContext ctx]
    (.readChunk me (.alloc ctx)))
  (readChunk [me ^ByteBufAllocator allocator]
    (let [buff (byte-array (* 2 c/FourK))
          mlen (alength buff)
          {:keys [^List ranges
                  bytes-read current]} me
          cnt (c/int-var* 0)
          cur (c/int-var* (c/int-var current))]
      (while (and (< (c/int-var cnt) mlen)
                  (< (c/int-var cur) (.size ranges)))
        (when-some [rg (.get ranges (c/int-var cur))]
          (l/debug "reading-chunk: range=%s - %s" (c/int-var cur) rg)
          (if (c/spos? (chunk-readable-bytes rg))
            (do (c/int-var cnt
                           + (chunk-pack rg
                                         buff (c/int-var cnt)))
                (l/debug "reading-chunk: count=%s" (c/int-var cnt)))
            (c/int-var cur + 1))))
      (c/int-var current (c/int-var cur))
      (when (pos? (c/int-var cnt))
        (c/int-var bytes-read + (c/int-var cnt))
        (l/debug "reading-chunk: count=%s", (c/int-var cnt))
        (l/debug "reading-chunk: read= %s" (c/int-var bytes-read))
        (Unpooled/wrappedBuffer buff 0 (int (c/int-var cnt))))))
  (length [me] (c/int-var (:total-bytes me)))
  (progress [me] (c/int-var (:bytes-read me)))
  (isEndOfInput [me] (not (has-next? me)))
  (close [me]
    (if-some [s (c/cast? Closeable
                         (:source me))]
      (i/klose s)))
  Object
  (finalize [me] (.close me))
  HttpRanges
  (list-ranges [me] (vec (:ranges me))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- http-ranges<> "" [ctype source]
  (let
    [[s ln]
     (cond
       (c/is? RandomAccessFile source)
       (let [^RandomAccessFile f source]
         [f (.length f)])
       (c/is? File source)
       (let [f (RandomAccessFile. ^File source "r")]
         [f (.length f)])
       (bytes? source)
       (let [^bytes b source]
         [b (alength b)])
       :else (u/throw-BadArg "bad source"))]
    (l/debug "file-range-object: len = %s, source = %s" ln s)
    (assoc (HttpRangesObj.)
           :total-bytes (c/int-var)
           :bytes-read (c/int-var)
           :current (c/int-var)
           :ranges (ArrayList.)
           :flen ln :source s :ctype ctype)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn eval-ranges ""
  ([rangeStr cType source]
   (l/debug "rangeStr = %s" rangeStr)
   (when (is-valid? rangeStr)
     (c/try! (range-init
               (http-ranges<> cType source) rangeStr))))
  ([rangeStr source]
   (eval-ranges rangeStr "application/octet-stream" source)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

