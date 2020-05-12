; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.reports.memory
  (:require [clojure.tools.logging :as log]
            [reporter.monitor :as mon]
            [reporter.reports.errors :as rep-err]
            [reporter.mail :as mail]
            [clojure.string :as str]))



(defn mega-bytes
  ^long [^long bytes]
  (quot bytes 1000000))


(defn check-memory
  [{:keys [mailer] :as daemon}, {:keys [warn-at] :as server-config}]
  (let [server-name (rep-err/server-name server-config)]
    (try
      (when-let [monitor (rep-err/connect-to-server daemon, server-config)]
        ; download reports
        (let [{:keys [used-memory, max-memory]} (mon/system-info monitor)
              used-ratio (/ (double used-memory) max-memory)
              used-percent (* 100.0 used-ratio)]
          (log/infof "[%s] memory info %d MB / %d MB (%.2f%%)"
            server-name, (mega-bytes used-memory), (mega-bytes max-memory), used-percent)
          ; when limit exceeded
          (when (>= used-ratio warn-at)
            ; log warn condition fulfilled
            (log/infof "[%s] warning required at %.2f%% memory usage." server-name, used-percent)
            ; send warn mail
            (mail/send-mail mailer
              (format "[%s] Memory Warning" server-name)
              (str/join "\n"
                [(str "MEMORY WARNING for " server-name "\n")
                 (format "The server is using more than %.2f%% of its maximum memory!" used-percent)])))))
      (catch Throwable t
        (log/errorf t "[%s] Exception during check-memory." server-name)))))