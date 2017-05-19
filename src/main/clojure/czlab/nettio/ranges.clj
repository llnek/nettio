;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.ranges

  (:require [czlab.basal.meta :as m :refer [instBytes?]]
            [czlab.basal.log :as log]
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
(def ^String DEF_BD "21458390-ebd6-11e4-b80c-0800200c9a66")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-object NumRange)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- numRange<> ""
  ([] (numRange<> 0 0))
  ([s e]
   (c/object<> NumRange {:start s :end e})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defprotocol ByteRangeChunk
  ""
  (c-init [_])
  (c-size [_] "")
  (calc-size [_] "")
  (readable-bytes [_] "")
  (c-pack [_ out offset] "")
  (c-read [_ out pos len] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-mutable
  ByteRangeChunkObj
  ByteRangeChunk
  (c-size [me]
    (let [{:keys [start end]} @me] (+ (- end start) 1)))
  (calc-size [me]
    (+ (.c-size me)
       (alength ^bytes (:preamble @me))))
  (readable-bytes [me]
    (- (.c-size me) (:rangePos @me)))
  (c-pack [me out offset]
    (let [^bytes pre (:preamble @me)
          bufsz (alength ^bytes out)
          plen (alength pre)
          pos (c/decl-int-var offset)
          count (c/decl-int-var 0)
          ppos (c/decl-int-var (:preamblePos @me))]
      (while (and (< (c/int-var pos) bufsz)
                  (< (c/int-var ppos) plen))
        (aset ^bytes
              out
              (c/int-var pos)
              (aget pre (c/int-var ppos)))
        (c/int-var ppos + 1)
        (c/int-var pos + 1)
        (c/int-var count + 1))
      (c/setf! me
               :preamblePos (c/int-var ppos))
      (when (< (c/int-var pos) bufsz)
        (let [r (.readable-bytes me)
              d (- bufsz (c/int-var pos))
              len (if (> r d) d r)
              c (.c-read me
                         out
                         (c/int-var pos) (int len))
              {:keys [start rangePos length]} @me]
          (if (< c 0)
            (c/throwIOE
              "error reading file: length=%s, seek=%s"
              length (+ start rangePos)))
          (c/setf! me :rangePos (+ rangePos c))
          (c/int-var count + c)))
      (c/int-var count)))
  (c-read [me out pos len]
    (let [{:keys [start rangePos source]}
          @me
          target (+ start rangePos)]
      (cond
        (c/ist? RandomAccessFile source)
        (let [^RandomAccessFile f source]
          (.seek f target)
          (.read f out pos len))
        (c/ist? InputStream source)
        (let [^InputStream inp source]
          (.reset inp)
          (.skip inp target)
          (.read inp out pos len))
        :else -1)))
  (c-init [me]
    (let [{:keys [source]} @me
          [s ln]
          (cond
            (c/ist? File source)
            (let [f (RandomAccessFile. ^File source "r")]
              [f (.length f)])
            (c/ist? RandomAccessFile source)
            (let [^RandomAccessFile f source]
              [f (.length f)])
            (m/instBytes? source)
            (let [^bytes b source
                  inp (i/streamit b)]
              (.mark inp 0)
              [inp (alength b)])
            :else (c/throwBadArg  "bad source"))]
      (c/copy* me {:length ln :source s}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- byteRangeChunk<> "" [src ctype start end]
  (c/do-with [b (c/mutable<> ByteRangeChunkObj
                             {:preamble (byte-array 0)
                              :preamblePos 0
                              :rangePos 0
                              :source src
                              :length 0
                              :start start
                              :end end
                              :cType ctype})]
             (c-init b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- multiByteRangeChunk<> "" [src ctype start end]

  (c/do-with [br (byteRangeChunk<> src ctype start end)]
    (let [{:keys [cType start end length]} @br]
      (->>
        (->
          (s/strbf<> "--")
          (.append DEF_BD)
          (.append "\r\n")
          (.append "Content-Type: ")
          (.append cType)
          (.append "\r\n")
          (.append "Content-Range: bytes ")
          (.append (Long/toString start))
          (.append "-")
          (.append (Long/toString end))
          (.append "/")
          (.append (Long/toString length))
          (.append "\r\n\r\n")
          str
          c/bytesit)
        (c/setf! br :preamble )))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- isValid? "" [rangeStr]
  (and (s/hgl? rangeStr)
       (.matches ^String rangeStr "^\\s*bytes=[0-9,-]+")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn fmtError "" [^HttpResponse rsp ^long totalSize]

  (let [last (if (c/spos? totalSize) (- totalSize 1) 0)
        hds (.headers rsp)
        totalSize (if (< totalSize 0) 0 totalSize)]
    (.setStatus rsp
                HttpResponseStatus/REQUESTED_RANGE_NOT_SATISFIABLE)
    (.add hds
          HttpHeaderNames/ACCEPT_RANGES
          HttpHeaderValues/BYTES)
    (.set hds
          HttpHeaderNames/CONTENT_RANGE
          (str "bytes 0-"
               (Long/toString last)
               "/"
               (Long/toString totalSize)))
    (.set hds HttpHeaderNames/CONTENT_LENGTH "0")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defprotocol HttpRanges
  ""
  (fmt-response [_ rsp] "")
  (has-next? [_] "")
  (r-init [_ s] "")
  (maybe-intersect? [_ r1 r2] "")
  (merge-ranges [_ r1 r2] "")
  (calc-total [_] "")
  (sanitize-ranges [_ chunks] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-mutable HttpRangesObj
  ChunkedInput
  (readChunk [me ^ChannelHandlerContext ctx]
    (.readChunk me (.alloc ctx)))
  (readChunk [me ^ByteBufAllocator allocator]
    (let [buff (byte-array (* 2 c/FourK))
          mlen (alength buff)
          {:keys [^List
                  ranges current]} @me
          count (c/decl-int-var 0)
          cur (c/decl-int-var current)]
      (while (and (< (c/int-var count) mlen)
                  (< (c/int-var cur) (.size ranges))
                  (some? (.get ranges (c/int-var cur))))
        (if (> (readable-bytes (.get ranges
                                     (c/int-var cur))) 0)
          (c/int-var count
                     +
                     (c-pack (.get ranges
                                   (c/int-var cur))
                             buff (c/int-var count)))
          (c/int-var cur + 1)))
      (c/setf! me :current (c/int-var cur))
      (when (> 0 (c/int-var count))
        (c/setf! me
                 :bytesRead
                 (+ (:bytesRead @me) (c/int-var count)))
        (Unpooled/wrappedBuffer buff 0 (int (c/int-var count))))))
  (length [me] (:totalBytes @me))
  (progress [me] (:bytesRead @me))
  (isEndOfInput [me] (not (.has-next? me)))
  (close [me]
    (if-some [s (c/cast? Closeable
                         (:source @me))]
      (i/closeQ s))
    (c/setf! me :source nil))
  HttpRanges
  (fmt-response [me rsp]
    (let [hds (.headers ^HttpResponse rsp)
          {:keys [^List ranges]} @me]
      (.setStatus ^HttpResponse
                  rsp HttpResponseStatus/PARTIAL_CONTENT)
      (.add hds
            HttpHeaderNames/ACCEPT_RANGES
            HttpHeaderValues/BYTES)
      (if (== 1 (.size ranges))
        (let [{:keys [start end]}
              @(.get ranges 0)]
          (.set hds
                HttpHeaderNames/CONTENT_RANGE
                (str HttpHeaderValues/BYTES
                     " "
                     (Long/toString start)
                     "-"
                     (Long/toString end)
                     "/"
                     (Long/toString (:flen @me)))))
        (.set hds
              HttpHeaderNames/CONTENT_TYPE
              (str "multipart/byteranges; boundary="  DEF_BD)))
      (.set hds
            HttpHeaderNames/CONTENT_LENGTH
            (Long/toString (.length me)))))
  (has-next? [me]
    (let [{:keys [^List ranges current]} @me]
      (and (< current (.size ranges))
           (> (readable-bytes (.get ranges current)) 0))))
  (r-init [me s]
    (let
      [rvs (-> (.replaceFirst ^String s
                              "^\\s*bytes=", "") s/strim (.split ","))
       {:keys [^List ranges flen]} @me
       last (- flen 1)
       chunks (ArrayList.)]
      (doseq [r rvs
              :let [rs (s/strim r)]]
        (let
          [[start end]
           (if (.startsWith rs "-")
             [(- last (Long/valueOf (s/strim (.substring rs 1)))) last]
             (let [rg (.split rs "-")]
               [(Long/valueOf (s/strim (first rg)))
                (if (> (count rg) 1)
                 (Long/valueOf (s/strim (nth rg 1))) last)]))
           end (if (> end last) last end)]
          (if (<= start end)
            (.add chunks (numRange<> start end)))))
      (.clear ranges)
      (when-not (.isEmpty chunks)
        (let [^List cs (.sanitize-ranges me chunks)
              {:keys [source cType]} @me
              many? (> (.size cs) 1)]
          (doseq [r cs
                  :let [{:keys [start end]} @r]]
            (->>
              (if many?
                (multiByteRangeChunk<> source cType start end)
                (byteRangeChunk<> source cType start end))
              (.add ranges)))))
      (if-not (.isEmpty ranges)
        (c/setf! me :totalBytes (.calc-total me))
        (c/throwBadData "Invalid byte ranges"))))
  (maybe-intersect? [me r1 r2]
    (or (and (>= (:start r1) (:start r2))
             (<= (:start r1) (:end r2)))
        (and (>= (:end r1) >= (:start r2))
             (<= (:start r1) (:end r2)))))
  (merge-ranges [me r1 r2]
    (numRange<>
      (if (< (:start r1) (:start r2)) (:start r1) (:start r2))
      (if (> (:end r1) (:end r2)) (:end r1) (:end r2))))
  (calc-total [me]
    (let [{:keys [^List ranges]}
          @me
          z (c/decl-long-var 0)]
      (doseq [r ranges]
        (c/long-var z + (calc-size r)))
      (c/long-var z)))
  (sanitize-ranges [me chunks]
    (let [rc (ArrayList.)
          sorted
          (sort-by :start
                   (reify Comparator
                     (compare [_ t1 t2]
                       (.compareTo (Long/valueOf ^long t1) ^long t2)))
                   (vec chunks))
          slen (count sorted)]
    (.add rc (first sorted))
    (loop [n 1]
      (if (>= n slen)
        rc
        (let [r1 (.get rc (dec (.size rc)))
              c1 (nth sorted n)]
          (if (.maybe-intersect? me c1 r1)
            (.set rc (dec (.size rc))
                  (.merge-ranges me c1 r1))
            (.add rc c1))
          (recur (inc n))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- httpRanges<> "" [ctype source]
  (let
    [[s ln]
     (cond
       (c/ist? RandomAccessFile source)
       (let [^RandomAccessFile f source]
         [f (.length f)])
       (c/ist? File source)
       (let [f (RandomAccessFile. ^File source "r")]
         [f (.length f)])
       (m/instBytes? source)
       (let [^bytes b source]
         [b (alength b)])
       :else (c/throwBadArg "bad source"))]
    (c/mutable<> HttpRangesObj
                 {:ranges (ArrayList.)
                  :flen ln :source s :cType ctype})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn evalRanges ""
  ([rangeStr cType source]
   (when (isValid? rangeStr)
     (let [rc (httpRanges<> cType source)]
       (c/try! (if (r-init rc rangeStr) rc)))))
  ([rangeStr source]
   (evalRanges rangeStr "application/octet-stream" source)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

