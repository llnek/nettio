;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "Http url routing."
    :author "Kenneth Leung"}

  czlab.niou.routes

  (:require [clojure.string :as cs]
            [czlab.basal
             [io :as i]
             [log :as l]
             [core :as c]
             [util :as u]])

  (:import [java.io
            File]
           [jregex
            Matcher
            Pattern]
           [clojure.lang
            APersistentVector]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol RouteInfo
  ""
  (ri-collect-info [_ matcher] "")
  (ri-match-route [_ mtd path] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol RouteCracker
  ""
  (rc-is-routable? [_ msgInfo] "")
  (rc-has-routes? [_] "")
  (rc-crack-route [_ msgInfo] ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RouteMatchResult [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RouteInfoObj []
  Object
  (toString [me] (i/fmt->edn me))
  RouteInfo
  (ri-match-route [me mtd path]
    (let [{:keys [verb regex]} me
          um (keyword (c/lcase mtd))]
      (if-some [m (some-> ^Pattern
                          regex (.matcher path))]
        (if (and (.matches m)
                 (or (nil? verb)
                     (= verb um)
                     (and (coll? verb)
                          (c/in? verb um)))) m))))
  (ri-collect-info [me _]
    (let [^Matcher mc _
          gc (.groupCount mc)]
      ;;first one is always the fully matched, skip it
      (c/object<> RouteMatchResult
                  :groups (c/preduce<vec>
                            #(if (pos? %2)
                               (conj! %1
                                      (.group mc (int %2))) %1)
                            (if (pos? gc) (range 0 gc) []))
                  :places (c/preduce<map>
                            #(let [[_ r2] %2]
                               (assoc! %1
                                       r2
                                       (str (.group mc ^String r2))))
                            (:place-holders me))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn parse-path
  "Parse a route uri." [pathStr]
  (with-local-vars [phs [] cg 0]
    [(c/sreduce<>
       #(c/sbf+ %1
                (if (cs/starts-with? %2 ":")
                  (let [gn (subs %2 1)]
                    (var-set cg (+ 1 @cg))
                    (var-set phs
                             (conj @phs [@cg gn]))
                    (str "({" gn "}[^/]+)"))
                  (let [c (c/count-char %2 \()]
                    (if (pos? c)
                      (var-set cg (+ @cg c))) %2)))
       (c/split-str pathStr "/" true)) @phs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- mk-route
  "Make a route-info object from definition."
  [cljrt {:keys [secure? session?
                 uri handler
                 template verb mount] :as rt}]
  (let [{:keys [path] :as ro}
        (-> {:session? (boolean session?)
             :secure? (boolean secure?)
             :path (c/strim uri)
             :verb verb
             :max-size -1
             :handler (if handler (u/var* cljrt handler))}
            (assoc :template
                   (if (c/hgl? template) template))
            (merge (if (c/hgl? mount)
                     {:static? true :mount mount})))
        [pp groups] (parse-path path)]
    (l/debug "Route added: %s\ncanon'ed to: %s." path pp)
    (c/object<> RouteInfoObj
                (merge ro {:regex (Pattern. pp)
                           :path pp :place-holders groups}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn load-routes
  "Path can be /host.com/abc/:id1/gg/:id2."
  [routes]
  {:pre [(or (nil? routes)
             (sequential? routes))]}
  (let [clj (u/cljrt<>)
        {:keys [s r]}
        (reduce
          #(let [ro (mk-route clj %2)]
             (l/debug "route def === %s." %2)
             (update-in %1
                        [(if (:static? ro) :s :r)] conj ro))
          {:s [] :r []} routes)]
    (vec (concat s r))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord RouteCrackerObj []
  RouteCracker
  (rc-is-routable? [me gist]
    (:status? (rc-crack-route me gist)))
  (rc-has-routes? [me]
    (boolean (not-empty (:routes me))))
  (rc-crack-route [me gist]
    (let [{:keys [uri method]} gist
          {:keys [routes]} me
          r?? (fn [m u]
                (some #(if-some
                         [mc (ri-match-route %1 m u)] [%1 mc]) routes))
          [ri mc] (r?? method uri)
          rt {:route-info ri :matcher mc :status? (boolean ri)}]
      (if (and (not (:status? rt))
               (not (cs/ends-with? uri "/"))
               (some? (r?? method (str uri "/"))))
        {:status? true :redirect (str uri "/")} rt))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn route-cracker<>
  "Create a url route cracker."
  [route-defs]
  (c/object<> RouteCrackerObj
              :routes (load-routes route-defs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

