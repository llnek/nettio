;; Copyright ©  2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns czlab.test.nettio.apps

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.niou.core :as cc]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.xpis :as po]
            [czlab.nettio.client :as cl]
            [czlab.nettio.server :as sv]
            [czlab.test.nettio.snoop :as sn]
            [czlab.test.nettio.files :as fs]
            [czlab.test.nettio.discard :as dc]
            [czlab.basal.core :as c
             :refer [ensure?? ensure-thrown??]])

  (:import [czlab.basal XData]
           [java.io File]
           [czlab.niou Headers]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce- MODULE (cl/web-client-module<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-apps

  (ensure??
    "apps/big-file"
    (let [src (XData. (i/res->file
                        "czlab/test/niou/net/big.pdf"))
          out (atom nil)
          sz (.size src)
          {:keys [host port] :as w}
          (-> (sv/web-server-module<>
                 #(do (-> (cc/http-result %1) cc/reply-result)
                      (reset! out (.fileRef ^XData (:body %1)))))
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port nil)
          h (-> (Headers.)
                (.add "content-type" "application/pdf"))
          rc (cc/write-msg c (cc/h1-msg<> :post
                                          "/bigfile" h src))
          {:keys [status]} (deref rc 9000 nil)
          ^File f @out]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and f
           (== 200 status)
           (== sz (.length f))
           (c/do#true (i/fdelete f)))))

  (ensure??
    "snoop-httpd<>"
    (let [{:keys [host port] :as w}
          (-> (sn/snoop-httpd<>)
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port nil)
          r (cc/write-msg c
                         (cc/h1-get<>
                           "/test/snooper?a=1&b=john%20smith"))
          {:keys [^XData body]} (deref r 3000 nil)]
      ;(l/debug "bbb = %s" (.strit body))
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and body (c/hgl? (i/x->str body)))))

  (ensure??
    "discard-httpd<>"
    (let [{:keys [host port] :as w}
          (-> (dc/discard-httpd<> rand)
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/h1-conn MODULE host port nil)
          r (cc/write-msg c
                         (cc/h1-get<>
                           "/test/discarder?a=1&b=john%27smith"))
          {:keys [body]} (deref r 3000 nil)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (zero? (if body (.size ^XData body) -1))))

  (ensure??
    "file-server/get"
    (let [{:keys [host port] :as w}
          (-> (fs/file-server<>)
              (po/start {:port 5555}))
          _ (u/pause 888)
          s "test content"
          tn (u/jid<>)
          _ (spit (i/tmpfile tn) s)
          c (cc/h1-conn MODULE host port nil)
          r (cc/write-msg c (cc/h1-get<> (str "/" tn)))
          {:keys [^XData body]} (deref r 5000 nil)]
      (l/debug "bbbb = %s" (.strit body))
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and body
           (pos? (.size ^XData body))
           (.equals s (i/x->str body)))))

  (ensure??
    "file-server/put"
    (let [{:keys [host port] :as w}
          (-> (fs/file-server<>)
              (po/start {:port 5555}))
          _ (u/pause 888)
          src (i/temp-file)
          s "test content"
          tn (u/jid<>)
          _ (spit src s)
          c (cc/h1-conn MODULE host port nil)
          r (cc/write-msg c (cc/h1-post<> (str "/" tn) src))
          {:keys [body]} (deref r 5000 nil)
          des (i/tmpfile tn)]
      (po/stop w)
      (po/finz c)
      (u/pause 500)
      (and body
           (zero? (.size ^XData body))
           (.exists des)
           (.equals s (slurp des)))))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-apps nettio-test-apps
  (ct/is (c/clj-test?? test-apps)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


