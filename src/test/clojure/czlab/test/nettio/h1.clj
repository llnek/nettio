;; Copyright ©  2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.nettio.h1

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.niou.core :as cc]
            [czlab.niou.upload :as cu]
            [czlab.basal.proc :as p]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.xpis :as po]
            [czlab.nettio.ranges :as nr]
            [czlab.nettio.client :as cl]
            [czlab.nettio.server :as sv]
            [czlab.basal.core :as c
             :refer [ensure?? ensure-thrown??]])

  (:import [org.apache.commons.fileupload FileItem]
           [czlab.niou Headers]
           [czlab.basal XData]
           [java.net URL URI]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/def-
  _file-content_ (str "hello how are you, "
                      "are you doing ok? " "very cool!"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce- MODULE (cl/web-client-module<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- echo-back
  [req]
  (p/async!
    #(let [res (cc/http-result req)]
       (u/pause 1000)
       (->> (cc/res-body-set res (:uri req)) cc/reply-result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-h1

  (ensure??
    "ssl/h1"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:server-key "*"
                 :user-cb #(-> (cc/http-result %1)
                               (cc/res-body-set (:uri %1)) cc/reply-result)})
              (po/start {:port 8443}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port {:server-cert "*"})
          p1 (cc/write-msg c (cc/h1-get<> (URI. "/blah")))
          r1 (deref p1 5000 nil)
          p2 (cc/write-msg c (cc/h1-get<>
                              (URI. "/Yoyo"))
                          {:keep-alive? false})
          r2 (deref p2 5000 nil)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and r1 r2
           (.equals "/blah" (i/x->str (:body r1)))
           (.equals "/Yoyo" (i/x->str (:body r2))))))

  (ensure??
    "h1/pipeline"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:server-key "*"
                 :pipelining? true
                 :user-cb echo-back})
              (po/start {:port 8443}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port {:server-cert "*"})
          r1 (cc/write-msg c (cc/h1-get<> "/r1"))
          r2 (cc/write-msg c (cc/h1-get<> "/r2"))
          r3 (cc/write-msg c (cc/h1-get<> "/r3"))
          rc1 (deref r1 5000 nil)
          rc2 (deref r2 5000 nil)
          rc3 (deref r3 5000 nil)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and rc1 rc2 rc3
           (.equals "/r1/r2/r3"
                    (str (i/x->str (:body rc1))
                         (i/x->str (:body rc2))
                         (i/x->str (:body rc3)))))))

  (ensure??
    "h1/form-post"
    (let [out (atom nil)
          {:keys [host port] :as w}
          (-> (sv/web-server-module<>
                 #(do (reset! out (:body %1))
                      (-> (cc/http-result %1)
                          (cc/res-body-set "hello joe") cc/reply-result)))
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port nil)
          h (-> (Headers.)
                (.add "content-type" "application/x-www-form-urlencoded"))
          rc (cc/write-msg c (cc/h1-msg<> :post
                                         "/form/a%20b%20c"
                                         h
                                         "a=b&c=3%209&name=john%27smith"))
          {:keys [^XData body]} (deref rc 5000 nil)
          items (if @out (cu/get-all-items (.content ^XData @out)))
          rmap (c/preduce<map>
                 #(let [^FileItem i %2]
                    (if-not (.isFormField i)
                      %1
                      (assoc! %1
                              (.getFieldName i)
                              (.getString i)))) items)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and (.equals "hello joe" (i/x->str body))
           (.equals "b" (rmap "a"))
           (.equals "3 9" (rmap "c"))
           (.equals "john'smith" (rmap "name")))))

  (ensure??
    "h1/form-multipart"
    (let [out (atom nil)
          {:keys [host port] :as w}
          (-> (sv/web-server-module<>
                #(do (reset! out (:body %1))
                     (-> (cc/http-result %1) cc/reply-result)))
              (po/start {:port 5555}))
          _ (u/pause 888)
          h (-> (Headers.)
                (.add "content-type"
                      "multipart/form-data; boundary=---1234"))
          c (cc/h1-conn MODULE host port nil)
          rc (cc/write-msg c (cc/h1-msg<> :post
                                         "/form" h cu/TEST-FORM-MULTIPART))
          {:keys [body]} (deref rc 5000 nil)
          items (.content ^XData @out)
          rmap (c/preduce<map>
                 #(let [^FileItem i %2]
                    (assoc! %1
                            (str (.getFieldName i)
                                 (.getString i))
                            (.getString i))) (cu/get-all-fields items))
          fmap (c/preduce<map>
                 #(let [^FileItem i %2]
                    (assoc! %1
                            (str (.getFieldName i)
                                 (.getName i))
                            (i/x->str (.get i)))) (cu/get-all-files items))]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and body
           (nil? (.content ^XData body))
           (.equals "fieldValue" (rmap "fieldfieldValue"))
           (.equals "value1" (rmap "multivalue1"))
           (.equals "value2" (rmap "multivalue2"))
           (.equals "file content(1)\n" (fmap "file1foo1.tab"))
           (.equals "file content(2)\n" (fmap "file2foo2.tab")))))

  (ensure??
    "preflight-not-allowed"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:cors-cfg {:short-circuit? true
                            :origins ["blah.com"]}
                 :user-cb #(-> (cc/http-result %1) cc/reply-result)})
              (po/start {:port 5555}))
          _ (u/pause 888)
          h (-> (Headers.)
                (.add "origin" (str "http://" host)))
          c (cc/h1-conn MODULE host port nil)
          rc (cc/write-msg c (cc/h1-msg<> :options "/cors" h nil))
          p (deref rc 3000 nil)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and p (== 403 (:status p)))))

  (ensure??
    "preflight"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:cors-cfg {:allow-creds? true
                            :any-origin? true
                            :null-origin? false}
                 :user-cb #(-> (cc/http-result %1) cc/reply-result)})
              (po/start {:port 5555}))
          _ (u/pause 888)
          origin (str "http://" host)
          h (-> (Headers.)
                (.add "origin" origin)
                (.add "Access-Control-Request-Method" "PUT")
                (.add "Access-Control-Request-Headers" "X-Custom-Header"))
          c (cc/h1-conn MODULE host port nil)
          rc (cc/write-msg c (cc/h1-msg<> :options "/cors" h nil))
          p (deref rc 3000 nil)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and p
           (.equals origin
                    (cc/msg-header p "access-control-allow-origin"))
           (.equals "true"
                    (cc/msg-header p "access-control-allow-credentials")))))

  (ensure??
    "file-range/all"
    (let [des (i/tmpfile (u/jid<>))
          _ (spit des _file-content_)
          {:keys [host port] :as w}
          (-> (sv/web-server-module<>
                #(-> (cc/http-result %1)
                     (cc/res-body-set des)
                     (cc/res-header-set "content-type" "text/plain")
                     cc/reply-result))
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port nil)
          p (cc/write-msg c
                         (cc/h1-msg<> :get
                                      "/range"
                                      (-> (Headers.)
                                          (.add "range" "bytes=0-")) nil))
          {:keys [^XData body]} (deref p 5000 nil)
          s (some-> body .strit)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and (c/hgl? s) (= 0 (c/count-str s nr/DEF-BD)))))


  (ensure??
    "file-range/chunked"
    (let [des (i/tmpfile (u/jid<>))
          _ (spit des _file-content_)
          {:keys [host port] :as w}
          (-> (sv/web-server-module<>
                #(-> (cc/http-result %1)
                     (cc/res-body-set des)
                     (cc/res-header-set "content-type" "text/plain")
                     cc/reply-result))
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port nil)
          p (cc/write-msg c (cc/h1-msg<> :get
                                        "/range"
                                        (-> (Headers.)
                                            (.add "range" "bytes=0-18,8-20,21-")) nil))
          {:keys [^XData body]} (deref p 5000 nil)
          s (some-> body .strit)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and (c/hgl? s)
           (== 2 (c/count-str s nr/DEF-BD)))))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-h1 nettio-test-h1
  (ct/is (c/clj-test?? test-h1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


