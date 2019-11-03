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
(defn- start-server
  [server options]
  (letfn
    [(ssvr [bs host port]
       (let [bs (c/cast? AbstractBootstrap bs)
             bs' (c/cast? ServerBootstrap bs)
             ^H1DataFactory
             dfac (some-> bs'
                          .config
                          .childAttrs (.get n/dfac-key))
             ip (if (c/nichts? host)
                  (InetAddress/getLocalHost)
                  (InetAddress/getByName host))]
         (c/do-with [ch (.. bs
                            (bind ip (int port)) sync channel)]
           (l/info "netty-server starting on %s:%s." ip port)
           (n/cf-cb (.closeFuture ch)
                    (c/fn_1 (c/try! (some-> dfac .cleanAllHttpData))
                            (c/try! (some-> bs'
                                            .config
                                            .childGroup .shutdownGracefully))
                            (c/try! (.. bs config group shutdownGracefully))
                            (l/debug "server bootstrap@ip %s stopped." ip))))))]
    (let [{:keys [host port block?]} options
          {:keys [bootstrap channel]} server
          p (if (c/is? ServerBootstrap bootstrap) 80 4444)]
      (->> (c/num?? port p)
           (ssvr bootstrap host)
           (aset #^"[Ljava.lang.Object;" channel 0))
      (if block? (u/block!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- stop-server
  [server]
  (let [{:keys [channel]} server
        ^Channel ch (c/_1 channel)]
    (l/debug "stopping channel: %s." ch)
    (aset #^"[Ljava.lang.Object;" channel 0 nil)
    (c/try! (if (and ch (.isOpen ch)) (.close ch)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord NettyServer []
  po/Startable
  (stop [_] (stop-server _))
  (start [_] (.start _ nil))
  (start [_ options] (start-server _ options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn udp-server<>

  "A UDP Server using netty."
  [args]
  {:pre [(map? args)]}

  (l/info "inside udp-server ctor().")
  (let [{:as args'
         :keys [inizor threads rcv-buf options]}
        (merge {:threads 0
                :rcv-buf (* 2 c/MegaBytes)} args)
        bs (Bootstrap.)
        [g z] (n/group+channel threads :udps)]
    (l/info "setting server options...")
    (doseq [[k v] (partition 2 (or options
                                   [:TCP_NODELAY true
                                    :SO_BROADCAST true
                                    :SO_RCVBUF (int rcv-buf)]))]
      (.option bs (n/chopt* k) v))

    (if (pos? threads)
      (l/info "threads=%s." threads)
      (l/info "threads=0, netty default to 2*num_of_processors"))

    (doto bs
       (.channel z)
       (.group g)
       (.handler ^ChannelHandler
                 (if (fn? inizor)
                   (inizor args)
                   (iz/udp-inizor<> args))))

     (l/info "netty-udp-server created - ok.")
     (c/object<> NettyServer
                 :bootstrap bs
                 :channel (object-array 1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn web-server<>

  "A Web Server using netty."

  [args]
  {:pre [(map? args)]}

  (l/info "inside web-server ctor().")
  (let [{:as args'
         :keys [boss workers routes rcv-buf
                options temp-dir inizor
                server-key passwd
                backlog max-msg-size max-mem-size]}
        (merge {:max-msg-size Integer/MAX_VALUE
                :temp-dir i/*tempfile-repo*
                :backlog c/KiloBytes
                :workers 0
                :boss 1
                :routes nil
                :rcv-buf (* 2 c/MegaBytes)
                :max-mem-size i/*membuf-limit*} args)
         bs (ServerBootstrap.)
         args' (dissoc args'
                       :routes :options)
         [^EventLoopGroup g
          ^ChannelHandler z] (n/group+channel boss :tcps)
         [^EventLoopGroup g' _] (n/group+channel workers :tcps)]

     (l/info "setting child options...")
     (doseq [[k v]
             (partition 2
                        (or (:child options)
                            [:TCP_NODELAY true
                             :SO_RCVBUF (int rcv-buf)]))]
       (.childOption bs (n/chopt* k) v))

     (l/info "setting server options...")
     (doseq [[k v]
             (partition 2
                        (or (:server options)
                            [:SO_REUSEADDR true
                             :SO_BACKLOG (int backlog)]))]
       (.option bs (n/chopt* k) v))

     (if (pos? workers)
       (l/info "threads=%s." workers)
       (l/info "threads=0, netty default to 2*num_of_processors."))

     (doto bs
       (.channel z)
       (.group g g')
       (.handler (LoggingHandler. LogLevel/INFO))
       (.childHandler ^ChannelHandler
                      (if (fn? inizor)
                        (inizor args)
                        (if (c/nichts? server-key)
                          (iz/web-inizor<> args)
                          (iz/web-ssl-inizor<> server-key passwd args)))))

     (l/info "assigning generic attributes for all channels...")
     (doto bs
       (.childAttr n/chcfg-key args')
       (.childAttr n/dfac-key
                   (H1DataFactory. (int max-mem-size))))

     (l/info "routes provided: %s." (not-empty routes))
     (when-not (empty? routes)
       (l/info "creating routes cracker...")
       (->> (cr/route-cracker<> routes)
            (.childAttr bs n/routes-key)))

     ;(l/debug "args = %s" (i/fmt->edn args'))

     (n/config-disk-files true (u/fpath temp-dir))
     (l/info "netty-tcp-server created - ok.")
     (c/object<> NettyServer
                 :bootstrap bs :channel (object-array 1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

