;; Copyright ©  2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc ""
    :author "Kenneth Leung"}

  czlab.test.nettio.h1

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.niou.core :as cc]
            [czlab.niou.upload :as cu]
            [czlab.niou.module :as mo]
            [czlab.basal.proc :as p]
            [czlab.basal.util :as u]
            [czlab.niou.log :as l]
            [czlab.niou.io :as i]
            [czlab.niou.xpis :as po]
            [czlab.niou.core :as c
             :refer [ensure?? ensure-thrown??]])

  (:import [org.apache.commons.fileupload FileItem]
           [czlab.basal XData]
           [java.net URL URI]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce-
  MODULE
  (mo/client-module<> {:implements :czlab.nettio.client/netty}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- echo-back
  [req]
  (p/async!
    #(let [res (cc/http-result req)]
       (u/pause 2000)
       (->> (:body req)
            (cc/res-body-set res) cc/reply-result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-h1

  (ensure??
    "ssl/h1"
    (let [{:keys [host port] :as w}
          (-> (mo/web-server-module<>
                {:implements :czlab.nettio.server/netty
                 :server-key "*"
                 :user-cb #(-> (cc/http-result %1)
                               (cc/res-body-set "hello joe") cc/reply-result)})
              (po/start {:port 8443}))
          _ (u/pause 888)
          c (cc/hc-h1-conn MODULE host port {:server-cert "*"})
          p (cc/write c (cc/h1-get<> (URI. "/blah")))
          {:keys [body]} (deref p 5000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and rc (.equals "hello joe" (i/x->str body)))))

  (ensure??
    "h1/pipeline"
    (let [{:keys [host port] :as w}
          (-> (mo/web-server-module<>
                {:implements :czlab.nettio.server/netty
                 :server-key "*"
                 :user-cb echo-back})
              (po/start {:port 8443}))
          _ (u/pause 888)
          c (cc/hc-h1-conn MODULE host port {:server-cert "*"})
          r1 (cc/write c (cc/h1-get<> "/r1"))
          r2 (cc/write c (cc/h1-get<> "/r2"))
          r3 (cc/write c (cc/h1-get<> "/r3"))
          rc1 (deref r1 5000 nil)
          rc2 (deref r2 5000 nil)
          rc3 (deref r3 5000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and rc1 rc2 rc3
           (.equals "/r1/r2/r3"
                    (str (i/x->str rc1)
                         (i/x->str rc2)
                         (i/x->str rc3))))))

  (ensure??
    "form-post"
    (let [out (atom nil)
          {:keys [host port] :as w}
          (-> (mo/web-server-module<>
                {:implements :czlab.nettio.server/netty
                 :user-cb #(do (reset! out (:body %1))
                               (-> (cc/http-result %1)
                                   (cc/res-body-set "hello joe") cc/reply-result))})
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/hc-h1-conn MODULE host port nil)
          h (-> (Headers.)
                (.add "content-type" "application/x-www-form-urlencoded"))
          rc (cc/cc-write c (cc/h1-post<> "/form?a=b&c=3 9&name=john'smith" h nil))
          {:keys [body]} (deref rc 5000 nil)
          rmap (when @out
                 (c/preduce<map>
                   #(let [^FileItem i %2]
                      (if (.isFormField i)
                        (assoc! %1
                                (keyword (.getFieldName i)) (.getString i)) %1))
                   (cu/get-all-items (.content ^XData @out))))]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and (= "hello joe" (i/x->str body))
           (= (:a rmap) "b")
           (= (:c rmap) "3 9")
           (= (:name rmap) "john'smith"))))

  (ensure??
    "form-port/multipart"
    (let [out (atom nil)
          {:keys [host port] :as w}
          (-> (mo/web-server-module<>
                {:implements :czlab.nettio.server/netty
                 :user-cb #(do (reset! out (:body %1))
                               (-> (cc/http-result %1) cc/reply-result))})
              (po/start {:port 5555}))
          _ (u/pause 888)
          h (-> (Headers.)
                (.add "content-type" "multipart/form-data; boundary=---1234"))
          c (cc/hc-h1-conn MODULE host port nil)
          rc (cc/cc-write c (cc/h1-post<> "/form" h cu/TEST-FORM-MULTIPART))
          {:keys [body]} (deref rc 5000 nil)
          rmap (when @out
                 (c/preduce<map>
                   #(let [^FileItem i %2]
                      (if (.isFormField i)
                        (assoc! %1
                                (keyword (str (.getFieldName i)
                                              "+" (.getString i))) (.getString i)) %1))
                   (cu/get-all-items (.content ^XData @out))))
          fmap (when @out
                 (c/preduce<map>
                   #(let [^FileItem i %2]
                      (if-not (.isFormField i)
                        (assoc! %1
                                (keyword (str (.getFieldName i)
                                              "+" (.getName i))) (i/x->str (.get i))) %1))
                   (cu/get-all-items (.content ^XData @out))))]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and body
           (zero? (.size ^XData body))
           (= (:field+fieldValue rmap) "fieldValue")
           (= (:multi+value1 rmap) "value1")
           (= (:multi+value2 rmap) "value2")
           (= (:file1+foo1.tab fmap) "file content(1)\n")
           (= (:file2+foo2.tab fmap) "file content(2)\n"))))

  (ensure??
    "preflight-not-allowed"
    (let [{:keys [host port] :as w}
          (-> (mo/web-server-module<>
                {:implements :czlab.nettio.server/netty
                 :user-cb #(-> (cc/http-result %1) cc/reply-result)})
              (po/start {:port 5555}))
          _ (u/pause 888)
          h (-> (Headers.)
                (.add "origin" (str "http://" host))
                (.add "Access-Control-Request-Method" "PUT")
                (.add "Access-Control-Request-Headers" "X-Custom-Header"))
          c (cc/hc-h1-conn MODULE host port nil)
          rc (cc/cc-write c (cc/h1-msg<> :options "/cors" h nil))
          p (deref rc 3000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and p (== 405 (:status p)))))

  (ensure??
    "preflight"
    (let [{:keys [host port] :as w}
          (-> (mo/web-server-module<>
                {:implements :czlab.nettio.server/netty
                 :cors-cfg {:enabled? true
                            :any-origin? true
                            :nullable? false
                            :credentials? true}
                 :user-cb #(-> (cc/http-result %1) cc/reply-result)})
              (po/start {:port 5555}))
          _ (u/pause 888)
          origin (str "http://" host)
          h (-> (Headers.)
                (.add "origin" origin)
                (.add "Access-Control-Request-Method" "PUT")
                (.add "Access-Control-Request-Headers" "X-Custom-Header"))
          c (cc/hc-h1-conn MODULE host port nil)
          rc (cc/cc-write c (cc/h1-msg<> :options "/cors" h nil))
          p (deref rc 3000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and p (.equals origin
                      (cc/msg-header p "access-control-allow-origin")))))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-h1 nettio-test-h1
  (ct/is (c/clj-test?? test-h1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


