; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.reports.summary
  (:require [clojure.tools.logging :as log]
            [reporter.reports.errors :as rep-err]
            [reporter.monitor :as mon]
            [reporter.mail :as mail]
            [cheshire.core :as json]
            [applied-science.darkstar :as darkstar])
  (:import (java.time ZonedDateTime Instant ZoneId)
           (java.time.format DateTimeFormatter DateTimeFormatterBuilder)
           (java.time.temporal ChronoUnit)
           (java.io File)))



(def iso-formatter
  (-> (DateTimeFormatterBuilder.)
    (.append DateTimeFormatter/ISO_LOCAL_DATE_TIME)
    (.optionalStart)
    (.appendOffsetId)
    (.toFormatter)))


(defn iso-datestr
  [^ZonedDateTime date-time]
  (.format iso-formatter date-time))


(defn date-time
  ^ZonedDateTime [timezone, timestamp]
  (ZonedDateTime/ofInstant
    (Instant/ofEpochMilli timestamp)
    (if timezone
      (ZoneId/of timezone)
      (ZoneId/systemDefault))))


(defn ->time-unit
  [x]
  (cond
    (instance? ChronoUnit x) x
    (keyword? x) (case x
                   :minutes ChronoUnit/MINUTES
                   :hours ChronoUnit/HOURS
                   :days ChronoUnit/DAYS
                   :weeks ChronoUnit/WEEKS)
    :else (throw (IllegalArgumentException. (str "Unsupported time-unit specification: " (class x))))))


(defn truncate-to
  ^ZonedDateTime [time-unit, ^ZonedDateTime date-time]
  (.truncatedTo date-time (->time-unit time-unit)))





(defn tests-per
  [time-unit, date-time-list]
  (->> date-time-list
    (mapv
      (fn [date-time]
        (->> date-time (truncate-to time-unit) iso-datestr)))
    frequencies
    (reduce-kv
      (fn [result-vec, time, freq]
        (conj! result-vec {:time time, :tests freq}))
      (transient []))
    persistent!))


(defn between
  [^ZonedDateTime first-date-time, ^ZonedDateTime last-date-time, ^ZonedDateTime date-time]
  (and
    (.isBefore first-date-time date-time)
    (.isBefore date-time last-date-time)))


(defn tests-per-day
  [test-date-list]
  {:data {:values (tests-per :days, test-date-list)}
   :encoding {:x {:field "time"
                  :type "temporal"
                  :timeUnit "yearmonthdate"
                  :axis {:title "Date"
                         :labelAngle -45}}
              :y {:field "tests"
                  :type "quantitative"
                  :axis {:title "Test Count"}}}
   :mark {:type "bar"
          :tooltip true}
   :title "Tests per Day"
   :width "600"
   :height "150"})


(defn today
  ^ZonedDateTime []
  (.truncatedTo (ZonedDateTime/now (ZoneId/systemDefault)) ChronoUnit/DAYS))


(defn tests-per-hour-prev-day
  [^ZonedDateTime day, test-date-list]
  (let [prev-day (.minusDays day 1)]
    {:data {:values (->> test-date-list
                      (filterv (partial between prev-day, day))
                      (tests-per :hours,))}
     :encoding {:x {:field "time"
                    :type "temporal"
                    :timeUnit "hours"
                    :axis {:title "Hour of Day"}}
                :y {:field "tests"
                    :type "quantitative"
                    :axis {:title "Test Count"}}}
     :mark {:type "bar"
            :tooltip true}
     :title (str "Tests per Hour on " (.format prev-day (DateTimeFormatter/ofPattern "dd.MM.yyyy")))
     :width "600"
     :height "150"}))


(defn views-per-day
  [views-per-day-list]
  {:data {:values views-per-day-list}
   :encoding {:x {:field "date"
                  :type "temporal"
                  :timeUnit "yearmonthdate"
                  :axis {:title "Date"
                         :labelAngle -45}}
              :y {:field "count"
                  :type "quantitative"
                  :axis {:title "View Count"}}}
   :mark {:type "bar"
          :tooltip true}
   :title "Views per Day"
   :width "600"
   :height "150"})


(defn generate-svg
  [plot-desc]
  (-> plot-desc
    json/encode
    darkstar/vega-lite-spec->svg))


(defn send-summary
  [{:keys [mailer] :as daemon}, server-config]
  (let [server-name (rep-err/server-name server-config)]
    (try
      (when-let [monitor (rep-err/connect-to-server daemon, server-config)]
        ; download test dates
        (if-let [timestamp-list (not-empty (mon/test-dates monitor))]
          (let [test-date-list (mapv (partial date-time (:timezone mailer)) timestamp-list)
                today (today)
                views-list (mon/views-per-day monitor)
                #_#_summary-file (export "summary-"
                               [:div
                                [:div [:vega (tests-per-day test-date-list)]]
                                [:div [:vega (tests-per-hour-prev-day today, test-date-list)]]
                                [:div [:vega (views-per-day views-list)]]])]
            (log/infof "[%s] Sending Daily Summary ..." server-name)
            (mail/send-mail mailer,
              (format "[%s] Daily Summary" server-name)
              {:type "text/html"
               :content (format "<h1>Daily Summary of %s</h1><div>%s</div><div>%s</div><div>%s</div>"
                          server-name
                          (generate-svg (tests-per-day test-date-list))
                          (generate-svg (tests-per-hour-prev-day today, test-date-list))
                          (generate-svg (views-per-day views-list)))})
            (log/infof "[%s] Daily Summary sent." server-name))
          (log/infof "[%s] No test dates retrieved." server-name)))
      (catch Throwable t
        (log/errorf t "[%s] Exception during send-summary." server-name)))))