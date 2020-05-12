; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.scheduling
  (:require [clojure.tools.logging :as log])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (java.lang.management ManagementFactory)
           (java.time ZoneId ZonedDateTime)
           (java.time.temporal ChronoUnit)))




(defn thread-count
  ^long []
  (.availableProcessors (Runtime/getRuntime)))


(defn new-scheduler
  []
  (Executors/newScheduledThreadPool (min 2 (thread-count))))


(defn now
  ^ZonedDateTime []
  (ZonedDateTime/now (ZoneId/systemDefault)))


(defn delay-to-next
  ^long [^long delay, ^long period]
  (if (>= delay 0)
    delay
    (recur (unchecked-add delay period), period)))


(defn schedule-fixed-rate
  [^ScheduledExecutorService scheduler, ^long minute-of-hour, ^long period-in-minutes, f]
  (let [now (now)
        current-hour (.truncatedTo now ChronoUnit/HOURS)
        next-event (.plusMinutes current-hour minute-of-hour)
        delay (.between ChronoUnit/MILLIS now next-event)
        period (* period-in-minutes 60 1000)
        delay (delay-to-next delay, period)]
   (.scheduleAtFixedRate scheduler, f, delay, period, TimeUnit/MILLISECONDS)))


(defn schedule-fixed-rate-starting-at
  [^ScheduledExecutorService scheduler, hour, minute, period-in-minutes, f]
  (let [now (now)
        current-day (.truncatedTo now ChronoUnit/DAYS)
        next-event (-> current-day (.plusHours hour) (.plusMinutes minute))
        next-event (cond-> next-event (.isBefore next-event now) (.plusDays 1))
        delay (.between ChronoUnit/MILLIS now next-event)
        period (* period-in-minutes 60 1000)]
    (.scheduleAtFixedRate scheduler, f, delay, period, TimeUnit/MILLISECONDS)))


(defn shutdown
  [^ScheduledExecutorService scheduler]
  (log/info "Shutting down scheduler ...")
  (.shutdownNow scheduler)
  nil)