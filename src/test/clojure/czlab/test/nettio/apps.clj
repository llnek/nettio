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

  czlab.test.nettio.apps

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.niou.core :as cc]
            [czlab.niou.module :as mo]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.xpis :as po]
            [czlab.basal.core :as c
            [czlab.test.nettio.snoop :as sn]
            [czlab.test.nettio.files :as fs]
            [czlab.test.nettio.discard :as dc]
             :refer [ensure?? ensure-thrown??]])

  (:import [czlab.basal XData]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/defonce-
  MODULE
  (mo/client-module<> {:implements :czlab.nettio.client/netty}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-apps

  (ensure??
    "snoop-httpd<>"
    (let [{:keys [host port] :as w}
          (-> (sn/snoop-httpd<>)
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/hc-h1-conn MODULE host port nil)
          r (cc/cc-write c
                         (cc/h1-get<>
                           "/test/snooper?a=1&b=john smith"))
          {:keys [body]} (deref r 3000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (c/hgl? (i/x->str body))))

  (ensure??
    "discard-httpd<>"
    (let [{:keys [host port] :as w}
          (-> (dc/discard-httpd<> rand)
              (po/start {:port 5555}))
          _ (u/pause 888)
          c (cc/hc-h1-conn MODULE host port nil)
          r (cc/cc-write c
                         (cc/h1-get<>
                           "/test/discarder?a=1&b=john%27smith"))
          {:keys [body]} (deref r 3000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (zero? (if body (.size ^XData body) -1))))

  (ensure??
    "file-server/get"
    (let [{:keys [host port] :as w}
          (-> (fs/file-server<>)
              (po/start {:port port}))
          _ (u/pause 888)
          s "test content"
          tn (u/jid<>)
          _ (spit (i/tmpfile tn) s)
          c (cc/hc-h1-conn MODULE host port nil)
          r (cc/cc-write c (cc/h1-get<> (str "/" tn)))
          {:keys [body]} (deref r 5000 nil)]
      (po/stop w)
      (cc/cc-finz c)
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
          c (cc/hc-h1-conn MODULE host port nil)
          r (cc/cc-write c (cc/h1-post<> (str "/" tn) src))
          {:keys [body]} (deref r 5000 nil)
          des (i/tmpfile tn)]
      (po/stop w)
      (cc/cc-finz c)
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


