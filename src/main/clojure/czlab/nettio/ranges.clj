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
           [czlab.basal.core GenericMutable]
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
(def ^String DEF_BD "21458390-ebd6-11e4-b80c-0800200c9a66")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private numRange<> "" [s e] `(doto {:start ~s :end ~e}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private tol "" [obj] `(Long/valueOf (s/strim ~obj)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- chunkSize "" [ck]
  (let [{:keys [start end]} @ck] (+ (- end start) 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- chunkTotalSize "" [ck]
  (+ (chunkSize ck) (alength ^bytes (:preamble @ck))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- chunkReadableBytes "" [ck] (- (chunkSize ck) (:rangePos @ck)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- chunkRead "" [ck out pos len]
  (let [{:keys [start rangePos source]} @ck
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- chunkPack "" [ck out offset]
  (let [^bytes pre (:preamble @ck)
        bufsz (alength ^bytes out)
        plen (alength pre)
        pos (c/decl-int-var offset)
        count (c/decl-int-var 0)
        ppos (c/decl-int-var (:preamblePos @ck))]
    (while (and (< (c/int-var pos) bufsz)
                (< (c/int-var ppos) plen))
      (aset ^bytes out (c/int-var pos) (aget pre (c/int-var ppos)))
      (c/int-var ppos + 1)
      (c/int-var pos + 1)
      (c/int-var count + 1))
    (c/setf! ck :preamblePos (c/int-var ppos))
    (when (< (c/int-var pos) bufsz)
      (let [r (chunkReadableBytes ck)
            d (- bufsz (c/int-var pos))
            len (if (> r d) d r)
            c (chunkRead ck out (c/int-var pos) (int len))
            {:keys [start rangePos length]} @ck]
        (if (< c 0)
          (c/throwIOE
            "error reading file: length=%s, seek=%s"
            length (+ start rangePos)))
        (c/setf! ck :rangePos (+ rangePos c))
        (c/int-var count + c)))
    (c/int-var count)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- byteRangeChunk<> "" [src ctype start end]
  (c/do-with [b (GenericMutable.
                  {:preamble (byte-array 0)
                   :preamblePos 0
                   :rangePos 0
                   :source src
                   :length 0
                   :start start
                   :end end
                   :cType ctype})]
    (let [{:keys [source]} @b
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
      (c/copy* b {:length ln :source s}))))

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
          (.append start)
          (.append "-")
          (.append end)
          (.append "/")
          (.append length)
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
(defprotocol HttpRanges
  ""
  (list-ranges [_] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- hasNext? [rg]
  (let [{:keys [^List ranges current]} @rg]
    (and (< current (.size ranges))
         (> (chunkReadableBytes (.get ranges current)) 0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeIntersect? "" [rg r1 r2]
  (or (and (>= (:start r1) (:start r2))
           (<= (:start r1) (:end r2)))
      (and (>= (:end r1) (:start r2))
           (<= (:start r1) (:end r2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- mergeRanges "" [rg r1 r2]
  (numRange<>
    (if (< (:start r1) (:start r2)) (:start r1) (:start r2))
    (if (> (:end r1) (:end r2)) (:end r1) (:end r2))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- calcTotalBytes "" [rg]
  (let [{:keys [^List ranges]}
        @rg
        z (c/decl-long-var 0)]
    (doseq [r ranges]
      (c/long-var z + (chunkTotalSize r)))
    (c/long-var z)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- sanitizeRanges "" [rg chunks]
  (let [rc (ArrayList.)
        sorted
        (c/sortby :start
                  #(.compareTo (Long/valueOf
                                 ^long %1) ^long %2)
                  (vec chunks))
        slen (count sorted)]
  (.add rc (first sorted))
  (loop [n 1]
    (if (>= n slen)
      rc
      (let [r1 (.get rc (dec (.size rc)))
            c1 (nth sorted n)]
        (if (maybeIntersect? rg c1 r1)
          (.set rc (dec (.size rc))
                (mergeRanges rg c1 r1))
          (.add rc c1))
        (recur (inc n)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- rangeInit "" [rg ^String s]
  (let
    [rvs (-> (.replaceFirst s
                            "^\\s*bytes=", "") s/strim (.split ","))
     {:keys [^List ranges flen]} @rg
     last (- flen 1)
     chunks (ArrayList.)]
    (doseq [r rvs
            :let [rs (s/strim r)]]
      (let
        [[start end]
         (if (.startsWith rs "-")
           [(- last (tol (.substring rs 1))) last]
           (let [rg (.split rs "-")]
             [(tol (first rg))
              (if (> (count rg) 1)
                (tol (nth rg 1)) last)]))
         end (if (> end last) last end)]
        (if (<= start end)
          (.add chunks (numRange<> start end)))))
      (.clear ranges)
      (when-not (.isEmpty chunks)
        (let [^List cs (sanitizeRanges rg chunks)
              {:keys [source cType]} @rg
              many? (> (.size cs) 1)]
          (doseq [r cs
                  :let [{:keys [start end]} r]]
            (.add ranges
              (if many?
                (multiByteRangeChunk<> source cType start end)
                (byteRangeChunk<> source cType start end))))))
      (if (.isEmpty ranges)
        (c/throwBadData "Invalid byte ranges"))
      (c/setf! rg :totalBytes (calcTotalBytes rg))
      rg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn fmtError [^HttpHeaders hds body]

  (let [sz (cond
             (c/ist? File body)
             (.length ^File body)
             (m/instBytes? body)
             (alength ^bytes body)
             :else 0)
        last (if (> sz 0) (dec sz) 0)]
    (.set hds
          HttpHeaderNames/ACCEPT_RANGES
          HttpHeaderValues/BYTES)
    (.set hds
          HttpHeaderNames/CONTENT_RANGE
          (str "bytes 0-" last "/" sz))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn fmtSuccess [^HttpHeaders hds rgObj]
  (let [{:keys [^List ranges]} @rgObj]
    (.set hds
          HttpHeaderNames/ACCEPT_RANGES
          HttpHeaderValues/BYTES)
    (if (== 1 (.size ranges))
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
                   (:flen @rgObj))))
      (.set hds
            HttpHeaderNames/CONTENT_TYPE
            (str "multipart/byteranges; boundary="  DEF_BD)))))

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
        (if (> (chunkReadableBytes (.get ranges
                                         (c/int-var cur))) 0)
          (c/int-var count
                     +
                     (chunkPack (.get ranges
                                      (c/int-var cur))
                                buff (c/int-var count)))
          (c/int-var cur + 1)))
      (c/setf! me :current (c/int-var cur))
      (when (> (c/int-var count) 0)
        (c/setf! me
                 :bytesRead
                 (+ (:bytesRead @me) (c/int-var count)))
        (log/debug "range:reading-chunk: count=%s", (c/int-var count))
        (log/debug "range:reading-chunk: bytesread= %s" (:bytesRead @me))
        (Unpooled/wrappedBuffer buff 0 (int (c/int-var count))))))
  (length [me] (:totalBytes @me))
  (progress [me] (:bytesRead @me))
  (isEndOfInput [me] (not (hasNext? me)))
  (close [me]
    (if-some [s (c/cast? Closeable
                         (:source @me))]
      (i/closeQ s))
    (c/setf! me :source nil))
  Object
  (finalize [me] (.close me))
  HttpRanges
  (list-ranges [me] (vec (:ranges @me))))

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
    (log/debug "file-range-object: len = %s, source = %s" ln s)
    (c/mutable<> HttpRangesObj
                 {:ranges (ArrayList.)
                  :totalBytes 0
                  :bytesRead 0
                  :current 0
                  :flen ln :source s :cType ctype})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn evalRanges ""
  ([rangeStr cType source]
   (when (isValid? rangeStr)
     (let [rg (httpRanges<> cType source)]
       (c/try! (rangeInit rg rangeStr)))))
  ([rangeStr source]
   (evalRanges rangeStr "application/octet-stream" source)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

