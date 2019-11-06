;; Copyright © 2013-2019, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns
  ^{:doc "Netty servers."
    :author "Kenneth Leung"}

  czlab.nettio.server

  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [czlab.basal.core :as c]
            [czlab.basal.log :as l]
            [czlab.basal.util :as u]
            [czlab.basal.io :as i]
            [czlab.basal.xpis :as po]
            [czlab.niou.routes :as cr]
            [czlab.nettio.core :as n]
            [czlab.nettio.inizor :as iz])

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
              (l/debug "stopped channel: %s." ch)))
    (-> (dissoc server :host :port)
        (assoc :impl nil :channel nil :started? false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- build<tcp>
  [server]
  (l/debug "about to build a web-server...")
  (let [{:as args'
         :keys [boss workers routes rcv-buf options temp-dir inizor
                server-key passwd backlog max-msg-size max-mem-size]}
        (merge {:max-msg-size Integer/MAX_VALUE
                :temp-dir i/*tempfile-repo*
                :backlog c/KiloBytes
                :workers 0
                :boss 1
                :routes nil
                :rcv-buf (* 2 c/MegaBytes)
                :max-mem-size i/*membuf-limit*} (:args server))
         args' (dissoc args' :routes :options)
         bs (ServerBootstrap.)
         [^EventLoopGroup g
          ^ChannelHandler z] (n/group+channel boss :tcps)
         [^EventLoopGroup g' _] (n/group+channel workers :tcps)
         hdlr (if (fn? inizor)
                (inizor args')
                (if (c/nichts? server-key)
                  (iz/web-inizor<> args')
                  (iz/web-ssl-inizor<> server-key passwd args')))]
    (n/config-disk-files true (u/fpath temp-dir))
    (if (pos? workers)
      (l/info "threads=%s." workers)
      (l/info "threads= 2*num_of_processors."))
    (l/debug "setting child options...")
    (doseq [[k v] (partition 2 (or (:child options)
                                   [:TCP_NODELAY true
                                    :SO_RCVBUF (int rcv-buf)]))]
      (.childOption bs (n/chopt* k) v))
    (l/debug "setting server options...")
    (doseq [[k v] (partition 2 (or (:server options)
                                   [:SO_REUSEADDR true
                                    :SO_BACKLOG (int backlog)]))]
      (.option bs (n/chopt* k) v))

    (.group bs g g')
    (.channel bs z)
    (.childHandler bs ^ChannelHandler hdlr)
    (.handler bs (LoggingHandler. LogLevel/INFO))

    (l/debug "set generic attributes for all channels...")
    (.childAttr bs n/chcfg-key args')
    (.childAttr bs
                n/dfac-key
                (H1DataFactory. (int max-mem-size)))

    (l/debug "routes provided: %s." (not-empty routes))
    (when-not (empty? routes)
      (l/debug "creating routes cracker...")
      (->> (cr/route-cracker<> routes)
           (.childAttr bs n/routes-key)))

    (try (assoc server
                :impl bs
                :channel (object-array 1))
         (finally
           (l/debug "web-server implemented - ok.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- start<tcp>
  [server options]
  (letfn
    [(ssvr [bs host port]
       (let [bs' (c/cast? ServerBootstrap bs)
             ^H1DataFactory
             dfac (some-> bs' .config .childAttrs (.get n/dfac-key))
             ip (if (c/nichts? host)
                  (InetAddress/getLocalHost)
                  (InetAddress/getByName host))
             quit
             (c/fn_1 (c/try! (some-> dfac .cleanAllHttpData))
                     (c/try! (some-> bs' .config .childGroup .shutdownGracefully))
                     (c/try! (.. bs' config group shutdownGracefully))
                     (l/debug "server @ip %s stopped." ip))]
         (c/do-with [ch (.. bs' (bind ip (int port)) sync channel)]
           (l/info "web-server starting on %s:%s." ip port)
           (n/cf-cb (.closeFuture ch) quit))))]
    (u/assert-ISE (not (:started? server)) "server running!")
    (let [server (build<tcp> server)
          {:keys [host port]} options
          {:keys [impl channel]} server
          port (c/num?? port 80)
          host (c/stror host n/lhost-name)]
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
        bs (Bootstrap.)
        [^EventLoopGroup g ^ChannelHandler z] (n/group+channel threads :udps)]

    (if (pos? threads)
      (l/info "threads=%s." threads)
      (l/info "threads= 2*num_of_processors"))

    (l/debug "setting server options...")
    (doseq [[k v] (partition 2 (or options
                                   [:SO_BROADCAST true]))]
      (.option bs (n/chopt* k) v))

    (.channel bs z)
    (.group bs g)
    (.handler bs
              ^ChannelHandler
              (if (fn? inizor)
                (inizor args')
                (iz/udp-inizor<> args')))

    (try (assoc server :impl bs :channel (object-array 1))
         (finally (l/debug "udp-server implemented - ok.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- start<udp>
  [server options]
  (letfn
    [(ssvr [bs host port]
       (let [bs (c/cast? Bootstrap bs)
             ip (if (c/nichts? host)
                  (InetAddress/getLocalHost)
                  (InetAddress/getByName host))
             quit (c/fn_1 (c/try! (.. bs config group shutdownGracefully))
                          (l/debug "server @ip %s stopped." ip))]
         (c/do-with [ch (.. bs (bind ip (int port)) sync channel)]
           (l/info "udp-server starting on %s:%s." ip port)
           (n/cf-cb (.closeFuture ch) quit))))]
    (u/assert-ISE (not (:started? server)) "server running!")
    (let [server (build<udp> server)
          {:keys [host port]} options
          {:keys [impl channel]} server
          port (c/num?? port 4444)
          host (c/stror host n/lhost-name)]
      (->> (ssvr impl host port)
           (aset #^"[Ljava.lang.Object;" channel 0))
      (assoc server :started? true :host host :port port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyTcpServer []
  po/Startable
  (stop [_] (stop-server _))
  (start [_] (.start _ nil))
  (start [_ options] (start<tcp> _ options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyUdpServer []
  po/Startable
  (stop [_] (stop-server _))
  (start [_] (.start _ nil))
  (start [_ options] (start<udp> _ options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-server<>
  "A UDP Server using netty."
  [args]
  (c/object<> NettyUdpServer :args args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn web-server<>
  "A Web Server using netty."
  [args]
  (c/object<> NettyTcpServer :args args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

