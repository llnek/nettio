;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol RouteInfo
  (match-path [_ mtd path] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol RouteCracker
  (is-routable? [_ msgInfo] "")
  (has-routes? [_] "")
  (gen-route [_ id params] "")
  (crack-route [_ msgInfo] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RouteMatchResult [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RouteInfoObj []
  Object
  (toString [me] (i/fmt->edn me))
  RouteInfo
  (match-path [me mtd path]
    (let [{:keys [verb regex groups]} me]
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
                        :info me
                        :params
                        (c/preduce<map>
                          #(let [[k v] %2]
                             (if (> v gc)
                               %1
                               (assoc! %1
                                       k
                                       (.group m (int v))))) groups))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn gen-path

  "Generate a route uri."
  [route params]

  (let [{:keys [name
                regex
                pattern]} route]
    (c/sreduce<>
      #(if (.equals "/" %2)
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RouteCrackerObj [routes]
  RouteCracker
  (is-routable? [_ gist]
    (some? (crack-route _ gist)))
  (has-routes? [_]
    (boolean (not-empty routes)))
  (gen-route [_ id params]
    (if-some [r (get routes id)]
      (let [s (gen-path r params)
            v (:verb r)
            m (.matcher ^Pattern
                        (:regex r) s)]
        (u/assert-BadArg (.matches m)
                         "Cannot gen path for route %s." id)
        (conj (into '() v) s))))
  (crack-route [_ gist]
    (let [{:keys [uri request-method]} gist]
      (some #(let [[_ v] %]
               (match-path v request-method uri)) routes))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn regex-path

  "Parse a route uri => [regex-path pieces params]."
  [pattern groups]

  (with-local-vars [params {} cg 0 parts 0]
    [(c/sreduce<>
       #(do
          (if-not (.equals "/" %2)
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
    (c/object<> RouteInfoObj
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
  [route-defs]

  (RouteCrackerObj. (load-routes route-defs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

