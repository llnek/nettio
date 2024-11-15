;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; Copyright © 2013-2024, Kenneth Leung. All rights reserved.

(ns czlab.test.nettio.h2

  (:require [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.string :as cs]
            [czlab.nettio.client :as cl]
            [czlab.nettio.server :as sv]
            [czlab.niou.core :as cc]
            [czlab.basal.proc :as p]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.util :as u]
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
              (c/start {:port 8443}))
          _ (u/pause 888)
          c (cc/h2-conn MODULE host port {:server-cert "*"})
          p (cc/write-msg c (cc/h1-msg<> :post "/form" nil "hello"))
          {:keys [^XData body]} (deref p 5000 nil)]
      (c/stop w)
      (c/finz c)
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
              (c/start {:port 8443}))
          _ (u/pause 888)
          c (cc/h2-conn MODULE host port {:h2-frames? true
                                             :server-cert "*"})
          p (cc/write-msg c (cc/h2-msg<> :post "/form" nil "hello"))
          {:keys [^XData body]} (deref p 5000 nil)]
      (c/stop w)
      (c/finz c)
      (u/pause 500)
      (and body (.equals "hello" (.strit body)))))

  (ensure?? "test-end" (== 1 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ct/deftest
  ^:test-h2 nettio-test-h2
  (ct/is (c/clj-test?? test-h2)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


