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

  czlab.test.nettio.h2

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.nettio.client :as cl]
            [czlab.nettio.server :as sv]
            [czlab.niou.core :as cc]
            [czlab.basal.proc :as p]
            [czlab.basal.util :as u]
            [czlab.basal.log :as l]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
            [czlab.basal.xpis :as po]
            [czlab.basal.core :as c
             :refer [ensure?? ensure-thrown??]])

  (:import [java.nio.charset Charset]
           [czlab.basal XData]
           [java.net URL URI]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce MODULE (cl/web-client-module<>))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(c/deftest test-h2
(comment
  (ensure??
    "ssl/h2-h1"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:server-key "*"
                 :user-cb #(-> (cc/http-result %1)
                               (cc/res-body-set "hello")
                               cc/reply-result)})
              (po/start {:port 8443}))
          _ (u/pause 888)
          c (cc/hc-h2-conn MODULE host port {:server-cert "*"})
          p (cc/cc-write c (cc/h1-msg<> :post "/form" nil "hello"))
          {:keys [^XData body]} (deref p 5000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and body (.equals "hello" (.strit body)))))
)
(comment)
  (ensure??
    "ssl/h2-frames"
    (let [{:keys [host port] :as w}
          (-> (sv/web-server-module<>
                {:server-key "*"
                 :h2-frames? true
                 :user-cb #(cc/reply-result %1)})
              (po/start {:port 8443}))
          _ (u/pause 888)
          c (cc/hc-h2-conn MODULE host port {:h2-frames? true
                                             :server-cert "*"})
          p (cc/cc-write c (cc/h2-msg<> :post "/form" nil "hello"))
          {:keys [^XData body]} (deref p 5000 nil)]
      (po/stop w)
      (cc/cc-finz c)
      (u/pause 500)
      (and body (.equals "hello" (.strit body)))))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-h2 nettio-test-h2
  (ct/is (c/clj-test?? test-h2)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


