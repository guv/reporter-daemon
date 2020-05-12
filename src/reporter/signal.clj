; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.signal
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as comp]))


(defn- signal-available?
  []
  (try
    (Class/forName "sun.misc.Signal")
    true
    (catch Throwable t
      false)))


(defmacro signal-handler!
  [signal, handler-fn]
  (if (signal-available?)
    `(try
       (sun.misc.Signal/handle (sun.misc.Signal. ~signal),
         (proxy [sun.misc.SignalHandler] []
           (handle [sig#] (~handler-fn sig#))))
       (catch IllegalArgumentException e#
         (log/errorf "Unable to set signal handler for signal: %s" ~signal)))
    `(log/errorf "Signal handlers are not available on this platform. (signal: %s)" ~signal)))


(defn create-stop-handler
  [shutting-down?, signal, shutdown-promise, system]
  (fn [sig]
    (log/infof "Recived %s signal." signal)
    (when (dosync
            (when-not (ensure shutting-down?)
              (alter shutting-down? (constantly true))))
      (log/infof "Stopping system (signal: %s) ...", signal)
      (comp/stop-system system)
      (log/infof "System stopped (signal: %s).", signal)
      (deliver shutdown-promise true))))


(defn setup-system-stop-on-signal
  [system]
  (let [shutting-down? (ref false)
        shutdown-promise (promise)]
    (let [sigterm-handler (create-stop-handler shutting-down?, "TERM", shutdown-promise, system)
          sigint-handler (create-stop-handler shutting-down?, "INT", shutdown-promise, system)]
      (signal-handler! "TERM", sigterm-handler)
      (signal-handler! "INT", sigint-handler)
      shutdown-promise)))
