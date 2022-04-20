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
;; Copyright © 2013-2022, Kenneth Leung. All rights reserved.

(ns czlab.niou.routes

  "Http url routing."

  (:require [flatland.ordered.map :as om]
            [clojure.string :as cs]
            [czlab.basal.io :as i]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u])

  (:import [java.io File]
           [java.util.regex Matcher Pattern]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def place-holder #"^\{([\*@a-zA-Z0-9_\+\-\.]+)\}$")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;example
(c/def- a-route-spec {:name :some-name
                      :pattern "/foo/yoo/wee/{id}/"
                      :verb :get ; or :verb #{:get :post}
                      :groups {:x "" :y ""}
                      :handler nil
                      :extra {:secure? true
                              :session? true
                              :template "abc.html"
                              :mount "/public/somedir"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RouteCracker [routes])
(defrecord RouteMatchResult [])
(defrecord RouteInfo []
  Object
  (toString [_] (i/fmt->edn _)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn match-path??

  "Try to match the uri path.  If matched, return the result."
  {:arglists '([ri mtd path])}
  [ri mtd path]
  {:pre [(c/is? RouteInfo ri)]}

  (let [{:keys [verb regex groups]} ri]
    (when (or (nil? verb)
              (c/in? verb mtd))
      (let [m (-> ^Pattern
                  regex
                  (.matcher path))
            ok? (.matches m)
            gc (if-not ok?
                 -1 (.groupCount m))]
        (if ok?
          (c/object<> RouteMatchResult
                      :info ri
                      :params
                      (c/preduce<map>
                        #(let [[k v] %2]
                           (if (> v gc)
                             %1
                             (assoc! %1
                                     k
                                     (.group m (int v))))) groups)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gen-path

  "Generate a route uri."
  {:arglists '([route params])}
  [route params]

  (let [{:keys [name
                regex
                pattern]} route]
    (c/sreduce<>
      #(if (c/eq? "/" %2)
         (c/sbf+ %1 %2)
         (c/sbf+ %1
                 (if-some [[_ k] (re-matches place-holder %2)]
                   (let [gk (keyword k)
                         gv (get params gk)
                         ok? (contains? params gk)]
                     (u/assert-BadArg
                       (and ok? gv)
                       "Bad parameter %s for route %s." k name)
                     gv)
                   %2)))
       (c/split-str (c/strim pattern) "/" true))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn has-routes?

  "Any routes defined?"
  {:arglists '([rc])}
  [rc]
  {:pre [(c/is? RouteCracker rc)]}

  (not (empty? (:routes rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gen-route

  "Generate a route given these inputs."
  {:arglists '([rc id params])}
  [rc id params]
  {:pre [(c/is? RouteCracker rc)]}

  (if-some [r (get (:routes rc) id)]
    (let [s (gen-path r params)
          v (:verb r)
          m (.matcher ^Pattern
                      (:regex r) s)]
      (u/assert-BadArg (.matches m)
                       "Cannot gen path for route %s." id)
      (conj (into '() v) s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn crack-route

  "Best attempt to match this path with a route."
  {:arglists '([rc gist])}
  [rc gist]
  {:pre [(c/is? RouteCracker rc)]}

  (let [{:keys [uri request-method]} gist]
    (some #(let [[_ v] %]
             (match-path?? v request-method uri)) (:routes rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn is-routable?

  "If the path matches a route?"
  {:arglists '([rc gist])}
  [rc gist]
  {:pre [(c/is? RouteCracker rc)]}

  (some? (crack-route rc gist)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn regex-path

  "Parse a route uri => [regex-path pieces params]."
  {:arglists '([pattern groups])}
  [pattern groups]

  (with-local-vars [params {} cg 0 parts 0]
    [(c/sreduce<>
       #(do
          (if-not (c/eq? "/" %2)
            (var-set parts (+ 1 @parts)))
          (c/sbf+ %1
                  (if-some [[_ k] (re-matches place-holder %2)]
                    (let [gk (keyword k)
                          gv (get groups gk)]
                      (var-set cg (+ 1 @cg))
                      (var-set params
                               (assoc @params gk @cg))
                      (str "(" (c/stror gv "[^/]+") ")"))
                    %2)))
       (c/split-str pattern "/" true)) @parts @params]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- mk-route

  "Make a route-info object from definition."
  [cljrt {:keys [pattern name
                 handler
                 verb groups extras] :as rt}]

  (let [kee (cond (keyword? name) name
                  (string? name) (keyword name)
                  :else (keyword (u/jid<>)))
        verb (cond (keyword? verb) #{verb}
                   (string? verb) #{(keyword verb)}
                   (set? verb) (if (not-empty verb) verb))
        handler (if handler (u/var* cljrt handler))
        [path pieces params]
        (regex-path (c/strim pattern) groups)]
    (c/debug "Route input: %s." pattern)
    (c/debug "Route regex: %s." path)
    (c/object<> RouteInfo
                (assoc rt
                       :verb verb
                       :name kee
                       :width pieces
                       :groups params
                       :handler handler
                       :regex (Pattern/compile path)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn load-routes

  "Path can be /host.com/abc/:id1/gg/:id2."
  {:arglists '([routes])}
  [routes]
  {:pre [(or (nil? routes)
             (sequential? routes))]}

  (let [clj (u/cljrt<>)]
    (reduce
      (fn [acc r]
        (assoc acc
               (:name r) r))
      (om/ordered-map)
      (u/sortby :width
                (c/compare-des* identity)
                (map #(mk-route clj %) routes)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn route-cracker<>

  "Create a uri route cracker."
  {:arglists '([route-defs])}
  [route-defs]

  (RouteCracker. (load-routes route-defs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

