;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.graph.propagator
  (:use
    [potemkin]
    [lamina.core.graph.core]
    [lamina.core.utils])
  (:require
    [lamina.core.lock :as l]
    [lamina.core.result :as r]
    [lamina.core.graph.node :as n]
    [clojure.tools.logging :as log])
  (:import
    [lamina.core.lock
     AsymmetricLock]
    [lamina.core.graph.core
     Edge]
    [java.util.concurrent.atomic
     AtomicBoolean]
    [java.util.concurrent
     ConcurrentHashMap]))

(deftype CallbackPropagator [callback]
  IDescribed
  (description [_] (describe-fn callback))
  IPropagator
  (close [_])
  (error [_ _])
  (transactional [_] false)
  (downstream [_] nil)
  (propagate [_ msg _]
    (try
      (callback msg)
      (catch Exception e
        (log/error e "Error in permanent callback.")))))

(defn callback-propagator [callback]
  (CallbackPropagator. callback))

(deftype BridgePropagator [description callback downstream]
  IDescribed
  (description [_] description)
  IPropagator
  (close [_]
    (doseq [^Edge e downstream]
      (close (.node e))))
  (error [_ err]
    (doseq [^Edge e downstream]
      (error (.node e) err)))
  (downstream [_] downstream)
  (propagate [_ msg _] (callback msg))
  (transactional [this]
    (doseq [n (downstream-nodes this)]
      (transactional n))))

(deftype TerminalPropagator [description]
  IDescribed
  (description [_] description)
  IPropagator
  (close [_])
  (error [_ err])
  (downstream [_] nil)
  (propagate [_ _ _] nil)
  (transactional [_] nil))

(defn terminal-propagator [description]
  (TerminalPropagator. description))

;;;

(defn close-and-clear [lock ^AtomicBoolean closed? ^ConcurrentHashMap downstream]
  (l/with-exclusive-lock lock
    (.set closed? true)
    (let [downstream (vals downstream)]
      (.clear ^ConcurrentHashMap downstream)
      downstream)))

(deftype DistributingPropagator
  [facet
   generator
   ^AsymmetricLock lock
   ^AtomicBoolean closed?
   ^AtomicBoolean transactional?
   ^ConcurrentHashMap downstream]
  IDescribed
  (description [_] "distributor")
  IPropagator
  (close [_]
    (doseq [n (close-and-clear lock closed? downstream)]
      (close n)))
  (error [_ err]
    (doseq [n (close-and-clear lock closed? downstream)]
      (error n err)))
  (transactional [_]
    (let [downstream
          (l/with-exclusive-lock
            (when (.compareAndSet transactional? false true)
              (vals downstream)))]
      (doseq [n downstream]
        (transactional n))))
  (downstream [_]
    (map #(edge nil %) (vals downstream)))
  (propagate [_ msg _]
    (if-let [n (l/with-lock lock
                 (when-not (.get closed?)
                   (let [id (facet msg)]
                     (if-let [n (.get downstream id)]
                       n
                       (let [n (generator id)]
                         (when (.get transactional?)
                           (transactional n))
                         (or (.putIfAbsent downstream id n)
                           (do
                             (r/subscribe (n/closed-result n)
                               (r/result-callback
                                 (fn [_] (.remove downstream id))
                                 (fn [_] (.remove downstream id))))
                             n)))))))]
      (propagate n msg true)
      :lamina/closed!)))

(defn distributing-propagator [facet generator]
  (DistributingPropagator.
    facet
    generator
    (l/asymmetric-lock)
    (AtomicBoolean. false)
    (AtomicBoolean. false)
    (ConcurrentHashMap.)))

;;;

(defn bridge-siphon [src edge-description node-description callback dsts]
  (let [downstream (to-array (map #(edge nil %) dsts))
        n (BridgePropagator. node-description
            (fn [x]
              (try
                (callback x)
                (catch Exception e
                  (log/error e (str "error in " (or node-description edge-description)))
                  (error src e))))
            downstream)
        upstream (edge edge-description n)
        dsts (filter n/node? dsts)]
    (n/link src n upstream
      nil
      (fn []
        (doseq [dst dsts]
          (n/on-state-changed dst nil (n/siphon-callback src n)))))))

(defn bridge-join [src edge-description node-description callback dsts]
  (let [downstream (to-array (map #(edge nil %) dsts))
        n (BridgePropagator. node-description
            (fn [x]
              (try
                (callback x)
                (catch Exception e
                  (log/error e (str "error in " (or node-description edge-description)))
                  (error src e))))
            downstream)
        upstream (edge edge-description n)]
    (n/link src n upstream
      nil
      (fn [_]
        (n/on-state-changed src nil (n/join-callback n))
        (doseq [dst dsts]
          (n/on-state-changed dst nil (n/siphon-callback src n)))))))

