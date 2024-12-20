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

(ns czlab.nettio.server

  "Netty servers."

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.niou.routes :as cr]
            [czlab.nettio.core :as n]
            [czlab.nettio.iniz :as z])

  (:import [io.netty.handler.logging LogLevel LoggingHandler]
           [czlab.nettio H1DataFactory]
           [java.net URL InetAddress]
           [io.netty.bootstrap
            Bootstrap
            ServerBootstrap
            AbstractBootstrap]
           [io.netty.channel
            EventLoopGroup
            ChannelOption
            Channel
            ChannelHandler
            ChannelHandlerContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- stop-server

  [{:keys [channel] :as server}]

  (let [^Channel ch (nth channel 0)]
    (when (some-> ch .isOpen)
      (c/try! (.close ch)
              (c/debug "stopped channel: %s." ch)))
    (-> (dissoc server :host :port)
        (assoc :impl nil :channel nil :started? false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build<tcp>

  [server]

  (c/debug "about to build a web-server...")
  (let [{:as args'
         :keys [threads
                boss
                routes
                rcv-buf
                options
                temp-dir
                inizor
                backlog
                max-msg-size
                max-mem-size
                max-frame-size]}
        (merge {:max-frame-size (* 32 c/MegaBytes)
                :max-msg-size Integer/MAX_VALUE
                :temp-dir (i/file-repo)
                :backlog c/KiloBytes
                :threads 0
                :boss 1
                :routes nil
                :rcv-buf (* 2 c/MegaBytes)
                :max-mem-size i/*membuf-limit*} (:args server))
         args' (dissoc args' :routes :options)
         boss (if (pos? boss) boss 1)
         threads (if (pos? threads) threads 0)
         bs (ServerBootstrap.)
         hdlr (inizor args')
         [^EventLoopGroup gb z] (n/group+channel boss :tcps)
         [^EventLoopGroup gw _] (n/group+channel threads :tcps)]
    (n/config-disk-files true (u/fpath temp-dir))
    (if (pos? threads)
      (c/info "threads=%s." threads)
      (c/info "threads=0 => 2*num_of_processors."))
    (c/debug "setting child options...")
    (doseq [[k v] (c/chop 2 (or (:child options)
                                [:TCP_NODELAY true
                                 :SO_RCVBUF (int rcv-buf)]))]
      (.childOption bs (n/chopt* k) v))
    (c/debug "setting server options...")
    (doseq [[k v] (c/chop 2 (or (:server options)
                                [:SO_REUSEADDR true
                                 :SO_BACKLOG (int backlog)]))]
      (.option bs (n/chopt* k) v))

    (.group bs gb gw)
    (.channel bs z)
    (.childHandler bs ^ChannelHandler hdlr)
    (.handler bs (LoggingHandler. LogLevel/DEBUG))

    (c/debug "set generic attributes for all channels...")
    (.childAttr bs n/chcfg-key args')
    (.childAttr bs
                n/dfac-key
                (H1DataFactory. (int max-mem-size)))

    (c/debug "routes provided: %s." (not-empty routes))
    (when-not (empty? routes)
      (c/debug "creating routes cracker...")
      (->> (cr/route-cracker<> routes)
           (.childAttr bs n/routes-key)))

    (try (assoc server
                :impl bs
                :channel (object-array 1))
         (finally
           (c/debug "web-server implemented - ok.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- start<tcp>

  [server options]

  (letfn
    [(ssvr [^ServerBootstrap bs host port]
       (let [^H1DataFactory
             dfac (.. bs config
                      childAttrs (get n/dfac-key))
             ip (if (c/nichts? host)
                  (InetAddress/getLocalHost)
                  (InetAddress/getByName host))
             quit #(do %1
                       (c/try! (some-> dfac .cleanAllHttpData))
                       (c/try! (.. bs config
                                   childGroup shutdownGracefully))
                       (c/try! (.. bs config
                                   group shutdownGracefully))
                       (c/debug "server @ip %s stopped." host))]
         (c/do-with [ch (.. bs
                            (bind ip (int port)) sync channel)]
           (c/info "web-server starting on %s:%s." host port)
           (n/cf-cb (.closeFuture ch) quit))))]
    (u/assert-ISE (not (:started? server)) "server running!")
    (let [{:keys [impl channel] :as server}
          (build<tcp> server)
          {:keys [host port]} options
          port (c/num?? port 80)
          host (c/stror host (n/lhost-name))]
      (->> (ssvr impl host port)
           (aset #^"[Ljava.lang.Object;" channel 0))
      (assoc server :started? true :host host :port port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build<udp>

  [server]

  (let [{:as args'
         :keys [inizor threads options]}
        (merge {:threads 0
                :rcv-buf (* 2 c/MegaBytes)} (:args server))
        threads (if (pos? threads) threads 0)
        bs (Bootstrap.)
        [g z] (n/group+channel threads :udps)]

    (if (pos? threads)
      (c/info "threads=%s." threads)
      (c/info "threads=0 => 2*num_of_processors"))

    (c/debug "setting server options...")
    (doseq [[k v] (c/chop 2 (or options
                                [:SO_BROADCAST true]))]
      (.option bs (n/chopt* k) v))

    (.channel bs z)
    (.group bs g)
    (.handler bs ^ChannelHandler (inizor args'))

    (try (assoc server
                :impl bs
                :channel (object-array 1))
         (finally (c/debug "udp-server implemented - ok.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- start<udp>

  [server options]

  (letfn
    [(ssvr [^Bootstrap bs host port]
       (let [ip (if (c/nichts? host)
                  (InetAddress/getLocalHost)
                  (InetAddress/getByName host))
             quit #(c/try! %1 (.. bs config
                                  group shutdownGracefully)
                           (c/debug "server @ip %s stopped." host))]
         (c/do-with [ch (.. bs
                            (bind ip (int port)) sync channel)]
           (c/info "udp-server starting on %s:%s." host port)
           (n/cf-cb (.closeFuture ch) quit))))]
    (u/assert-ISE (not (:started? server)) "server running!")
    (let [{:keys [impl channel] :as server}
          (build<udp> server)
          {:keys [host port]} options
          port (c/num?? port 4444)
          host (c/stror host (n/lhost-name))]
      (->> (ssvr impl host port)
           (aset #^"[Ljava.lang.Object;" channel 0))
      (assoc server :started? true :host host :port port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyTcpServer [args]
  c/Startable
  (stop [_] (stop-server _))
  (start [_] (.start _ nil))
  (start [_ options] (start<tcp> _ options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyUdpServer [args]
  c/Startable
  (stop [_] (stop-server _))
  (start [_] (.start _ nil))
  (start [_ options] (start<udp> _ options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-server-module<>

  "Create a UDP server implemented in Netty."
  {:arglists '([]
               [args])}

  ([]
   (udp-server-module<> nil))

  ([args]
   (-> (if (fn? args) {:user-cb args} args)
       (assoc :inizor z/udp-inizor<>) NettyUdpServer. )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn web-server-module<>

  "Create a Http server implemented in Netty."
  {:arglists '([]
               [args])}

  ([]
   (web-server-module<> nil))

  ([args]
   (-> (if (fn? args) {:user-cb args} args)
       (assoc :inizor z/web-inizor<>) NettyTcpServer. )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

