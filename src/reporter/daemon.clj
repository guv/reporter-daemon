; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.daemon
  (:require [com.stuartsierra.component :as comp]
            [next.jdbc :as jdbc]
            [reporter.scheduling :as sched]
            [reporter.reports.errors :as rep-err]
            [reporter.reports.memory :as check-mem]
            [reporter.reports.summary :as summary]
            [clojure.tools.logging :as log]
            [reporter.storage :as store]))



(defn start-tasks
  "Starts the specified tasks and returns the corresponding scheduler."
  [{:keys [servers],
    {:keys [trust-store]} :truststore-manager
    :as daemon}]
  (let [scheduler (sched/new-scheduler)]
    (doseq [server-config servers
            report-type [:error-report, :memory-check, :daily-summary]]
      (when-let [{:keys [hour, minute, period]
                  :or {hour 0, minute 0, period 30}
                  :as report-config} (get server-config report-type)]
        (let [report-fn (case report-type
                          :error-report rep-err/report-errors
                          :memory-check check-mem/check-memory
                          :daily-summary summary/send-summary)
              server+report-options (merge
                                      (assoc server-config :trust-store trust-store)
                                      (dissoc report-config :minute, :period))
              scheduled-fn (partial report-fn daemon server+report-options)]
          (if (= report-type :daily-summary)
            (sched/schedule-fixed-rate-starting-at scheduler, hour, minute, (* 24 60), scheduled-fn)
            (sched/schedule-fixed-rate scheduler, minute, period, scheduled-fn)))))
    ; return scheduler
    scheduler))


(defn assign-server-id
  [storage, {:keys [url] :as server-config}]
  (jdbc/with-transaction [con storage]
    (let [server-id (if-let [{:keys [id]} (store/server-by-url con, url)]
                      id
                      (store/add-server con, url))]
      (assoc server-config :id server-id))))


(defrecord Daemon [config, storage, truststore-manager, servers, scheduler]

  comp/Lifecycle

  (start [daemon]
    (log/info "Starting Daemon")
    (let [{:keys [config-map]} config
          servers (mapv (partial assign-server-id storage) (get-in config-map [:servers]))
          daemon (assoc daemon :servers servers)]
      (assoc daemon :scheduler (start-tasks daemon))))

  (stop [daemon]
    (log/info "Stopping Daemon")
    (some-> scheduler sched/shutdown)
    (assoc daemon :scheduler nil)))


(defn new-daemon
  []
  (map->Daemon {}))


