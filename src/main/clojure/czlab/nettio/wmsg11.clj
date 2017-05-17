;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.nettio.wmsg11

  (:require [czlab.basal.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.str :as s]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c])

  (:import [io.netty.handler.codec.http.multipart
            HttpDataFactory
            Attribute
            HttpPostRequestDecoder]
           [io.netty.handler.codec DecoderResult]
           [io.netty.handler.codec.http
            HttpContent
            HttpMethod
            HttpHeaders
            HttpMessage
            HttpRequest
            HttpVersion
            HttpResponse
            HttpResponseStatus]
           [czlab.jasal XData]
           [java.io IOException]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
  ;;protected HttpPostRequestDecoder _decoder;
  ;;protected HttpRequest _attrOwner;
  ;;protected HttpDataFactory _fac;
  ;;protected HttpMessage _msg;
  ;;protected Attribute _attr;
  ;;protected XData _body;
(def ^:private ^String body-attr-id "--body--")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defprotocol WholeMsgProto
  ""
  (append-msg-content [_ c last?] "")
  (add-msg-content [_ c last?] "")
  (deref-msg [_] "")
  (end-msg-content [_ c] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- addContent "" [whole ^HttpContent c isLast?]
  (let [{:keys [impl]}
        target (deref-msg whole)]
    (cond
      (c/ist? Attribute impl)
      (.addContent ^Attribute impl
                   (.. c content retain) isLast?)
      (c/ist? HttpPostRequestDecoder impl)
      (.offer ^HttpPostRequestDecoder impl c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- appendContent "" [whole ^HttpContent c isLast?]
  (let [{:keys [body impl fac owner]}
        target (deref-msg whole)]
  (addContent whole c isLast?)
  (if isLast?
    (cond
      (c/ist? Attribute impl)
      (do
        (.removeHttpDataFromClean
          ^HttpDataFactory
          fac
          ^HttpRequest
          owner
          ^Attribute
          impl)
        (.reset ^XData body
                (end-msg-content whole))
        (.release ^Attribute impl))
      (c/ist? HttpPostRequestDecoder impl)
      (do
        (.reset ^XData body
                (end-msg-content whole))
        (.destroy ^HttpPostRequestDecoder impl))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- prepareBody ""
  [^HttpDataFactory df ^HttpRequest msg]
  (if (-> (cc/chkFormPost msg)
          (s/eqAny? ["multipart" "post"]))
    (HttpPostRequestDecoder. df msg (nc/getMsgCharset msg))
    (.createAttribute df msg body-attr-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wholeRequest<> "" [^HttpDataFactory fac ^HttpRequest owner]
  (let [impl (prepareBody fac owner)
        _ (assert (some? impl))
        target {:impl impl :fac fac
                :owner owner :body (i/xdata<>)}]
    (reify HttpRequest
      (getMethod [me] (.method me))
      (getUri [me] (.uri me))
      (method [me]
        (if-some+ [x (-> (.headers owner)
                         (.get "X-HTTP-Method-Override"))]
                  (HttpMethod/valueOf x)
                  (.method owner)))
      (setProtocolVersion [_ _]
        (throw (UnsupportedOperationException.)))
      (setMethod [_ _]
        (throw (UnsupportedOperationException.)))
      (setUri [_ _]
        (throw (UnsupportedOperationException.)))
      (uri [_] (.uri owner))
      (getDecoderResult [me] (.decoderResult me))
      (decoderResult [_] (.decoderResult owner))
      (setDecoderResult [_ _]
        (throw (UnsupportedOperationException.)))
      (getProtocolVersion [me] (.protocolVersion me))
      (headers [_] (.headers owner))
      (protocolVersion [_] (.protocolVersion owner))
      (setProtocolVersion [_ _]
        (throw (UnsupportedOperationException.)))
      WholeMsgProto
      (append-msg-content [me c last?]
        (appendContent me c (bool! last?)))
      (add-msg-content [me c last?]
        (addContent me c (bool! last?)))
      (deref-msg [_] target)
      (end-msg-content [me]
        (cond
          (c/ist? HttpPostRequestDecoder impl)
          (parsePost impl)
          (c/ist? Attribute impl)
          (getHttpData impl))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wholeResoponse<> "" [^HttpDataFactory fac ^HttpResponse resp]
  (let [impl (prepareBody fac (nc/fakeRequest<>))
        _ (assert (some? impl))
        target {:impl impl :fac fac
                :owner owner :body (i/xdata<>)}]
    (reify HttpResponse
      (setProtocolVersion [_ _]
        (throw (UnsupportedOperationException.)))
      (getDecoderResult [me] (.decoderResult me))
      (decoderResult [_] (.decoderResult owner))
      (setDecoderResult [_ _]
        (throw (UnsupportedOperationException.)))
      (getProtocolVersion [me] (.protocolVersion me))
      (headers [_] (.headers resp))
      (protocolVersion [_] (.protocolVersion owner))
      (setProtocolVersion [_ _]
        (throw (UnsupportedOperationException.)))
      (getStatus [me] (.status me))
      (setStatus [_ _]
        (throw (UnsupportedOperationException.)))
      (status [_] (.status resp))
      WholeMsgProto
      (append-msg-content [me c last?]
        (appendContent me c (bool! last?)))
      (add-msg-content [me c last?]
        (addContent me c (bool! last?)))
      (deref-msg [_] target)
      (end-msg-content [me]
        (getHttpData impl)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


