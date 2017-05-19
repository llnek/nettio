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
  (chunk-size [_] "")
  (total-size [_] "")
  (readable-bytes [_] "")
  (c-pack [_ out offset] "")
  (c-read [_ out pos len] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-mutable
  ByteRangeChunkObj
  ByteRangeChunk
  (chunk-size [me] (+ (- (:end @me) (:start @me)) 1))
  (total-size [me] (+ (.chunk-size me)
                     (alength ^bytes (:preamble @me))))
  (readable-bytes [me] (- (.chunk-size me)
                          (:rangePos @me)))
  (c-pack [me out offset]
    (let [^bytes pre (:preamble @me)
          bufsz (alength ^bytes out)
          plen (alength pre)
          pos (long-array 1 offset)
          count (long-array 1 0)
          ppos (long-array 1 (:preamblePos @me))]
      (while (and (< (aget pos 0) bufsz)
                  (< (aget ppos 0) plen))
        (aset ^bytes
              out
              (aget pos 0)
              (aget pre (aget ppos 0)))
        (aset ppos 0 (+ 1 (aget ppos 0)))
        (aset pos 0 (+ 1 (aget pos 0)))
        (aset count 0 (+ 1 (aget count 0))))
      (c/setf! me :preamblePos (aget ppos 0))
      (when (< (aget pos 0) bufsz)
        (let [r (.readable-bytes me)
              d (- bufsz (aget pos 0))
              len (if (> r d) d r)
              ;;len (if (> len  Integer/MAX_VALUE) Integer/MAX_VALUE len)
              c (.c-read me out (aget pos 0) (int len))]
          (if (< c 0)
            (c/throwIOE
              "error reading file: length=%s, seek=%s"
              (:length @me) (+ (:start @me) (:rangePos @me))))
          (c/setf! me :rangePos (+ (:rangePos @me) c))
          (aset count 0 (long (+ (aget count 0) c)))))
      (aget count 0)))
  (c-read [me out pos len]
    (let [target (+ (:start @me)
                    (:rangePos @me))
          src (:source @me)]
      (cond
        (c/ist? RandomAccessFile src)
        (let [^RandomAccessFile f src]
          (.seek f target)
          (.read f out pos len))
        (c/ist? ByteArrayInputStream src)
        (let [^ByteArrayInputStream inp src]
          (.reset inp)
          (.skip inp target)
          (.read inp out pos len))
        :else -1)))
  (c-init [me]
    (let [src (:source @me)
          [s ln]
          (cond
            (c/ist? File src)
            (let [f (RandomAccessFile. ^File src "r")]
              [f (.length f)])
            (c/ist? RandomAccessFile src)
            (let [^RandomAccessFile f src]
              [f (.length f)])
            (m/instBytes? src)
            (let [^bytes b src
                  inp (ByteArrayInputStream. b)]
              (.mark inp 0)
              [inp (alength b)])
            :else (throw (IllegalArgumentException.)))]
      (c/setf! me :length ln)
      (c/setf! me :source s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- byteRangeChunk<> "" [src ctype start end]
  (c/do-with [b (c/mutable<> ByteRangeChunkObj
                             {:preamble (byte-array 0)
                              :source src
                              :length 0
                              :preamblePos 0
                              :rangePos 0
                              :start start
                              :end end
                              :cType ctype})]
             (c-init b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- multiByteRangeChunk<> "" [src ctype start end]

  (c/do-with [br (byteRangeChunk<> src ctype start end)]
    (->>
      (->
        (s/strbf<> "--")
        (.append DEF_BD)
        (.append "\r\n")
        (.append "Content-Type: ")
        (.append (:cType @br))
        (.append "\r\n")
        (.append "Content-Range: bytes ")
        (.append (Long/toString (:start @br)))
        (.append "-")
        (.append (Long/toString (:end @br)))
        (.append "/")
        (.append (Long/toString (:length @br)))
        (.append "\r\n\r\n")
        str
        c/bytesit)
      (c/setf! br :preamble ))))

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
    (let [buff (byte-array 8192)
          mlen (alength buff)
          ^List rgs (:ranges @me)
          cur (long-array 1 (:current @me))
          count (long-array 1 0)]
      (while (and (< (aget count 0) mlen)
                  (< (aget cur 0) (.size rgs))
                  (some? (.get rgs (aget cur 0))))
        (if (> (readable-bytes (.get rgs (aget cur 0))) 0)
          (aset count
                0
                (long
                  (+ (aget count 0)
                     (c-pack (.get rgs
                                   (aget cur 0))
                             buff
                             (aget count 0)))))
          (aset cur 0 (long (+ 1 (aget cur 0))))))
      (c/setf! me :current (aget cur 0))
      (when (> 0 (aget count 0))
        (c/setf! me
                 :bytesRead
                 (+ (:bytesRead @me) (aget count 0)))
        (Unpooled/wrappedBuffer buff))))
  (length [me] (:totalBytes @me))
  (progress [me] (:bytesRead @me))
  (isEndOfInput [me] (not (.has-next? me)))
  (close [me]
    (let [s (:source @me)]
      (if (c/ist? Closeable s)
        (i/closeQ s))
      (c/setf! me :source nil)))
  HttpRanges
  (fmt-response [me rsp]
    (let [hds (.headers ^HttpResponse rsp)
          ^List rgs (:ranges @me)]
      (.setStatus ^HttpResponse
                  rsp HttpResponseStatus/PARTIAL_CONTENT)
      (.add hds
            HttpHeaderNames/ACCEPT_RANGES
            HttpHeaderValues/BYTES)
      (if (== 1 (.size rgs))
        (let [r (.get rgs 0)]
          (.set hds
                HttpHeaderNames/CONTENT_RANGE
                (str HttpHeaderValues/BYTES
                     " "
                     (Long/toString (:start @r))
                     "-"
                     (Long/toString (:end @r))
                     "/"
                     (Long/toString (:flen @r)))))
        (.set hds
              HttpHeaderNames/CONTENT_TYPE
              (str "multipart/byteranges; boundary="  DEF_BD)))
      (.set hds
            HttpHeaderNames/CONTENT_LENGTH
            (Long/toString (:totalBytes @me)))))
  (has-next? [me]
    (let [cur (:current @me)
          ^List rgs (:ranges @me)]
      (and (< cur (.size rgs))
           (> (readable-bytes (.get rgs cur)) 0))))
  (r-init [me s]
    (let
      [rvs (-> (.replaceFirst ^String s
                              "^\\s*bytes=", "") s/strim (.split ","))
       chunks (ArrayList.)
       ^List rgs (:ranges @me)
       last (- (:flen @me) 1)]
      (doseq [r rvs
              :let [rs (s/strim r)]]
        (let
          [[start end]
           (if (.startsWith rs "-")
             [(- last (Long/valueOf (s/strim (.substring rs 1)))) last]
             (let [range (.split rs "-")]
               [(Long/valueOf (s/strim (first range)))
                (if (> (count range) 1)
                 (Long/valueOf (s/strim (nth range 1))) last)]))
           end (if (> end last) last end)]
          (if (<= start end)
            (.add chunks (numRange<> start end)))))
      (.clear rgs)
      (when-not (.isEmpty chunks)
        (let [^List cs (.sanitize-ranges me chunks)
              {:keys [source cType]}
              @me
              many? (> (.size cs) 1)]
          (doseq [r cs]
            (->>
              (if many?
                (multiByteRangeChunk<> source cType (:start r) (:end r))
                (byteRangeChunk<> source cType (:start r) (:end r)))
              (.add rgs)))))
      (if-not (.isEmpty rgs)
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
    (let [rgs (:ranges @me)
          z (long-array 1 0)]
      (doseq [r rgs]
        (aset z 0 (long (+ (aget z 0)
                           (total-size r)))))
      (aget z 0)))
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
    ;;for (int n = 1; n < sorted.length; ++n) {
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
       :else (throw (IllegalArgumentException.)))]
    (c/mutable<> HttpRangesObj
                 {:flen ln :source s :cType ctype})))

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

