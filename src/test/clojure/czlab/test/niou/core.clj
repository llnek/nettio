;; Copyright ©  2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.niou.core

  (:require [czlab.test.niou.mock :as m]
            [czlab.niou.webss :as ws]
            [czlab.niou.routes :as r]
            [czlab.niou.util :as ct]
            [czlab.niou.mime :as mi]
            [czlab.niou.core :as cc]
            [czlab.niou.upload :as cu]
            [clojure.string :as cs]
            [clojure.test :as t]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.core
             :as c
             :refer [ensure?? ensure-thrown??]])

  (:import [java.net HttpCookie URL URI]
           [czlab.basal XData]
           [czlab.niou Headers]
           [org.apache.commons.fileupload FileItem]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- phone-agents
  {:winphone
  " Mozilla/5.0 (compatible; MSIE 10.0; Windows Phone 8.0; Trident/6.0; IEMobile/10.0; ARM; Touch; NOKIA; Lumia 920) "
   :safari_osx
  " Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/536.26.17 (KHTML, like Gecko) Version/6.0.2 Safari/536.26.17 "
   :chrome_osx
  " Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.22 (KHTML, like Gecko) Chrome/25.0.1364.155 Safari/537.22 "
   :ffox_linux
  " Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:19.0) Gecko/20100101 Firefox/19.0 "
   :ie_win
  " Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.2; Win64; x64; Trident/6.0) "
   :chrome_win
  " Mozilla/5.0 (Windows NT 6.2; WOW64) AppleWebKit/537.22 (KHTML, like Gecko) Chrome/25.0.1364.97 Safari/537.22 "
   :kindle
  " Mozilla/5.0 (Linux; U; en-us; KFTT Build/IML74K) AppleWebKit/535.19 (KHTML, like Gecko) Silk/2.8 Safari/535.19 Silk-Accelerated=true "
   :iphone
  " Mozilla/5.0 (iPhone; CPU iPhone OS 6_1_2 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10B146 Safari/8536.25 "
   :ipad
  " Mozilla/5.0 (iPad; CPU OS 6_1_2 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10B146 Safari/8536.25 "
   :ipod
  " Mozilla/5.0 (iPod; CPU iPhone OS 6_1_2 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10B146 Safari/8536.25 "
   :android_galaxy
  " Mozilla/5.0 (Linux; U; Android 4.1.1; en-us; SAMSUNG-SGH-I747 Build/JRO03L) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30 "})

(c/def- pkeybytes (i/x->bytes "mocker"))

(c/def- ROUTES
  [{:XXXhandler "p1"
    :pattern "/{a}/{b}"
    :groups {:a "[a-z]+" :b "[0-9]+"}
    :verb :post
    :name :r1
    :extras {:template  "t1.html"}}

   {:pattern "/{yo}"
    :name :r2
    :groups {:yo "favicon\\..+"}}

   {:XXXhandler "p2"
    :pattern "/{a}/zzz/{b}/c/{d}"
    :name :r3
    :groups {:a "[A]+" :b "[B]+" :d "[D]+"}
    :verb :get}

   {:name :g1
    :pattern "/a/{b}/c/{d}/e/{f}"
    :groups {:b "[a-z]+" :d "[0-9]+" :f "[A-Z]+"}}

   {:pattern "/4"}])

(c/def- RC (r/route-cracker<> ROUTES))
;(println "routes = " (i/fmt->edn RC))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(eval '(mi/setup-cache (i/res->url "czlab/niou/etc/mime.properties")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def- ^Headers HEADERS (doto (Headers.)
                           (.add "he" "x")
                           (.set "ha" "y")
                           (.add "yo" "a")
                           (.add "yo" "b")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-core

  (ensure?? "gist-header?"
            (and (.containsKey HEADERS "Yo")
                 (not (.containsKey HEADERS "o"))))

  (ensure?? "gist-header"
            (.equals "a" (.getFirst HEADERS "yo")))

  (ensure?? "gist-header-keys"
            (let [s (into #{} (.keySet HEADERS))]
              (and (contains? s "yo")
                   (contains? s "he")
                   (contains? s "ha"))))

  (ensure?? "gist-header-vals"
            (= ["a" "b"] (vec (.get HEADERS "yo"))))

  (ensure?? "init-test" (> (count ROUTES) 0))

  (ensure?? "has-routes?" (r/has-routes? RC))

  (ensure?? "parse-path"
            (let [[p c g]
                  (r/regex-path "/{a}/{z}/{b}/c/{d}"
                                {:a "foo"
                                 :b "man"
                                 :d "chu"
                                 :z nil})]
              (and (== 5 c)
                   (== 1 (:a g))
                   (== 2 (:z g))
                   (== 3 (:b g))
                   (== 4 (:d g))
                   (.equals "/(foo)/([^/]+)/(man)/c/(chu)" p))))

  (ensure?? "crack-route"
            (let [{:as R :keys [route params]}
                  (r/crack-route RC
                                 {:uri "/hello/007"
                                  :request-method :post})]
              (and R
                   (= :r1 (:name route))
                   (.equals "hello" (:a params))
                   (.equals "007" (:b params)))))

  (ensure?? "crack-route"
            (let [{:as R :keys [route params]}
                  (r/crack-route RC
                                 {:uri "/favicon.hello"
                                  :request-method :get})]
              (and R
                   (== 1 (count params))
                   (.equals "favicon.hello" (:yo params)))))

  (ensure?? "crack-route"
            (let [{:as R :keys [route params]}
                  (r/crack-route RC
                                 {:uri "/AAA/zzz/BBB/c/DDD"
                                  :request-method :get})]
              (and (== 3 (count params))
                   (.equals "AAA" (:a params))
                   (.equals "BBB" (:b params))
                   (.equals "DDD" (:d params)))))

  (ensure?? "crack-route"
            (let [{:as R :keys [route params]}
                  (r/crack-route RC
                                 {:uri "/4"
                                  :request-method :get})]
              (and route
                   (empty? params))))

  (ensure?? "crack-route"
            (nil? (r/crack-route RC
                                 {:request-method :get
                                  :uri "/1/1/1/1/1/1/14"})))

  ;:pattern "/a/{b}/c/{d}/e/{f}"

  (ensure?? "gen-route/nil"
            (nil? (r/gen-route RC :ggg {})))

  (ensure-thrown?? "gen-route/params"
                   :any
                   (r/gen-route RC :g1
                                {:d "911" :f "XYZ"}))

  (ensure-thrown?? "gen-route/malform"
                   :any
                   (r/gen-route RC :g1
                                {:b "xyz" :d "XXX" :f "XYZ"}))

  (ensure?? "gen-route/ok"
            (.equals "/a/xyz/c/911/e/XYZ"
                     (first (r/gen-route RC :g1
                                         {:b "xyz" :d "911" :f "XYZ"}))))

  (ensure?? "parse-form-post"
            (let [b (XData. cu/TEST-FORM-MULTIPART)
                  gist {:clen (.size b)
                        :ctype "multipart/form-data; boundary=---1234"}
                  out (cu/parse-form-post gist b)
                  rmap
                  (when out
                    (c/preduce<map>
                      #(let [^FileItem i %2]
                         (if-not (.isFormField i)
                           %1
                           (assoc! %1
                                   (keyword (str (.getFieldName i)
                                                 "+" (.getString i)))
                                   (.getString i))))
                      (cu/get-all-items out)))
                  fmap
                  (when out
                    (c/preduce<map>
                      #(let [^FileItem i %2]
                         (if (.isFormField i)
                           %1
                           (assoc! %1
                                   (keyword (str (.getFieldName i)
                                                 "+" (.getName i)))
                                   (i/x->str (.get i)))))
                      (cu/get-all-items out)))]
              (and (.equals "fieldValue" (:field+fieldValue rmap))
                   (.equals "value1" (:multi+value1 rmap))
                   (.equals "value2" (:multi+value2 rmap))
                   (.equals "file content(1)\n" (:file1+foo1.tab fmap))
                   (.equals  "file content(2)\n" (:file2+foo2.tab fmap)))))

  (ensure?? "downstream"
            (let [req (m/mock-http-request pkeybytes false)
                  res (m/mock-http-result req)
                  res (ws/downstream res)
                  cs (:cookies res)
                  c (get cs ws/session-cookie)
                  v (.getValue ^HttpCookie c)]
              (== 6 (count (.split v "=")))))

  (ensure?? "upstream"
            (let [req (m/mock-http-request pkeybytes true)
                  res (m/mock-http-result req)
                  res (ws/downstream res)
                  cs (:cookies res)
                  s (ws/upstream pkeybytes cs true)]
              (ws/validate?? s)
              (and (not (ws/is-session-null? s))
                   (not (ws/is-session-new? s)))))

  (ensure?? "parse-ie" (some? (ct/parse-ie (:winphone phone-agents))))
  (ensure?? "parse-ie" (nil? (ct/parse-ie "some crap")))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:ie_win phone-agents))))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:winphone phone-agents))))

  (ensure?? "parse-chrome" (some? (ct/parse-chrome (:chrome_osx phone-agents))))
  (ensure?? "parse-chrome" (some? (ct/parse-chrome (:chrome_win phone-agents))))
  (ensure?? "parse-chrome" (nil? (ct/parse-chrome "some crap")))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:chrome_osx phone-agents))))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:chrome_win phone-agents))))

  (ensure?? "parse-kindle" (some? (ct/parse-kindle (:kindle phone-agents))))
  (ensure?? "parse-kindle" (nil? (ct/parse-kindle "some crap")))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:kindle phone-agents))))

  (ensure?? "parse-android"
            (some? (ct/parse-android (:android_galaxy phone-agents))))
  (ensure?? "parse-android" (nil? (ct/parse-android "some crap")))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:android_galaxy phone-agents))))

  (ensure?? "parse-ffox" (some? (ct/parse-ffox (:ffox_linux phone-agents))))
  (ensure?? "parse-ffox" (nil? (ct/parse-ffox "some crap")))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:ffox_linux phone-agents))))

  (ensure?? "parse-safari" (some? (ct/parse-safari (:safari_osx phone-agents))))
  (ensure?? "parse-safari" (nil? (ct/parse-safari "some crap")))
  (ensure?? "parse-user-agent-line"
            (some? (ct/parse-user-agent-line (:safari_osx phone-agents))))

  (ensure?? "generate-nonce" (c/hgl? (ct/generate-nonce)))
  (ensure?? "generate-csrf" (c/hgl? (ct/generate-csrf)))

  (ensure?? "parse-basic-auth"
            (let [{:keys [principal credential]}
                  (ct/parse-basic-auth "  Basic   QWxhZGRpbjpPcGVuU2VzYW1l  ")]
              (and (.equals "Aladdin" principal)
                   (.equals "OpenSesame" credential))))

  (ensure?? "form-items<>"
            (let [bag (-> (cu/form-items<>)
                          (cu/add-item (cu/file-item<> true "" nil "a1" "" nil))
                          (cu/add-item (cu/file-item<> false "" nil "f1" "" nil))
                          (cu/add-item (cu/file-item<> true "" nil "a2" "" nil)))]
              (and (== 1 (count (cu/get-all-files bag)))
                   (== 2 (count (cu/get-all-fields bag))))))

  (ensure?? "file-item<>"
            (let [f (cu/file-item<> true "" nil "a1" "" (XData. "hello"))
                  b (.get f)
                  n (.getFieldName f)
                  f? (.isFormField f)
                  s (.getString f)
                  m? (.isInMemory f)
                  z (.getSize f)
                  i (.getInputStream f)]
              (i/klose i)
              (and (== z (alength b))
                   (some? i)
                   (.equals "a1" n)
                   f?
                   (.equals "hello" s)
                   m?)))

  (ensure?? "file-item<>"
            (let [f (cu/file-item<> false "text/plain" nil "f1" "a.txt" (XData. "hello"))
                  b (.get f)
                  n (.getFieldName f)
                  ct (.getContentType f)
                  f? (not (.isFormField f))
                  s (.getString f)
                  m? (.isInMemory f)
                  nn (.getName f)
                  z (.getSize f)
                  i (.getInputStream f)]
              (i/klose i)
              (and (== z (alength b))
                   (.equals "text/plain" ct)
                   (some? i)
                   (.equals "f1" n)
                   (.equals "a.txt" nn)
                   f?
                   (.equals "hello" s)
                   m?)))

  (ensure?? "mime-cache<>" (map? (mi/mime-cache<>)))

  (ensure?? "charset??"
            (.equals "utf-16" (mi/charset?? "text/plain; charset=utf-16")))

  (ensure-thrown?? "normalize-email"
                   :any
                   (mi/normalize-email "xxxx@@@ddddd"))

  (ensure-thrown?? "normalize-email"
                   :any
                   (mi/normalize-email "xxxx"))

  (ensure?? "normalize-email"
            (.equals "abc@abc.com" (mi/normalize-email "abc@ABC.cOm")))

  (ensure?? "is-signed?"
            (mi/is-signed? "saljas application/x-pkcs7-mime laslasdf lksalfkla multipart/signed signed-data "))

  (ensure?? "is-encrypted?"
            (mi/is-encrypted? "saljas laslasdf lksalfkla application/x-pkcs7-mime  enveloped-data "))

  (ensure?? "is-compressed?"
            (mi/is-compressed? "saljas laslasdf lksalfkla application/pkcs7-mime compressed-data"))

  (ensure?? "is-mdn?"
            (mi/is-mdn? "saljas laslasdf lksalfkla multipart/report   disposition-notification    "))

  (ensure?? "guess-mime-type"
            (cs/includes? (mi/guess-mime-type "/tmp/abc.jpeg") "image/"))

  (ensure?? "guess-content-type"
            (cs/includes? (mi/guess-content-type "/tmp/abc.pdf") "/pdf"))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(t/deftest
  ^:test-niou niou-test-core
  (t/is (c/clj-test?? test-core)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


