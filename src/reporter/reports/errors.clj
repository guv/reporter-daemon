; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.reports.errors
  (:require [clojure.tools.logging :as log]
            [reporter.monitor :as mon]
            [reporter.storage :as store]
            [reporter.mail :as mail]
            [clojure.string :as str]
            [clojure.stacktrace :as st])
  (:import (java.time ZonedDateTime Instant ZoneId)
           (java.time.format DateTimeFormatter)))



(defn new-reports
  [storage, server-id, server-name, report-list]
  (let [remote-identification-set (or
                                    (store/report-remote-identification-set storage, server-id)
                                    #{})
        new-reports (into []
                      (remove (comp remote-identification-set (juxt :id :timestamp)))
                      report-list)]
    (log/infof "[%s] Found %d / %d new reports." server-name (count new-reports) (count report-list))
    new-reports))


(defn server-name
  [{:keys [name, url] :as server-config}]
  (if (str/blank? name)
    url
    name))


(defn handle-failed-connection
  [mailer, {:keys [url] :as server-config}, exception]
  (let [server-name (server-name server-config)]
    (mail/send-mail mailer
      (format "[%s] ERROR: Connection failed" server-name)
      (str/join "\n"
        [(format "ERROR REPORT for %s" server-name)
         ""
         (str
           "Could not establish connection to "
           server-name
           (when-not (= server-name url)
             (str " at " url))
           "!")
         (with-out-str (st/print-cause-trace exception))]))))


(defn handle-failed-login
  [mailer, {:keys [url] :as server-config}]
  (let [server-name (server-name server-config)]
    (mail/send-mail mailer
      (format "[%s] ERROR: Login failed" server-name)
      (str/join "\n"
        [(str "ERROR REPORT for " server-name)
         ""
         (str
           "Could not log in to "
           server-name
           (when-not (= server-name url)
             (str " at " url))
           "!")
         ""
         (format "Please, ensure that you created a reporter account on %s and specified the correct user name in the reporter daemon config file."
           server-name)]))))


(defn format-timestamp
  [timezone, ^long unix-timestamp]
  (-> (ZonedDateTime/ofInstant (Instant/ofEpochMilli unix-timestamp),
        (if timezone
          (ZoneId/of timezone)
          (ZoneId/systemDefault)))
    (.format (DateTimeFormatter/ofPattern "dd.MM.yyyy HH:mm"))))


(defn report-text
  [timezone, report-list]
  (->> report-list
    (mapv
      (fn [{:keys [context, message, timestamp]}]
        (str (format-timestamp timezone, timestamp) " " context "\n" message)))
    (str/join "\n\n")))


(defn handle-reports
  [{:keys [timezone] :as mailer}, {:keys [url] :as server-config}, error-list, warn-list]
  (let [server-name (server-name server-config)]
    (log/infof "[%s] handle-reports: %d errors, %d warnings" server-name (count error-list), (count warn-list))
    (mail/send-mail mailer
      (format "[%s] Error Report" server-name)
      (str/join "\n"
        (cond-> [(str "ERROR REPORT for " server-name "\n")]
          (seq error-list)
          (conj
            (format "Found %d ERROR reports:\n\n%s"
              (count error-list)
              (report-text timezone, error-list)))
          (seq warn-list)
          (conj
            (format "Found %d WARNING reports:\n\n%s"
              (count warn-list)
              (report-text timezone, error-list))))))))


(defn connect-to-server
  [{:keys [mailer] :as daemon}, server-config]
  (if-let [monitor (try
                     (mon/login server-config)
                     (catch Throwable t
                       t))]
    (if (instance? Throwable monitor)
      ; connection failed with exception
      (do
        (log/errorf monitor, "Could not establish connection to \"%s\"!" (:url server-config))
        (handle-failed-connection mailer, server-config, monitor)
        nil)
      ; return monitor
      monitor)
    ; login failed
    (do
      (log/errorf "Could not login to \"%s\"!" (:url server-config))
      (handle-failed-login mailer, server-config)
      nil)))


(defn report-errors
  [{:keys [storage, mailer] :as daemon}, {:keys [delete-reports?, id] :as server-config}]
  (let [server-name (server-name server-config)]
    (try
      (when-let [monitor (connect-to-server daemon, server-config)]
        ; download reports
        (if-let [report-list (some->> (mon/report-list monitor)
                               not-empty
                               (new-reports storage, id, server-name)
                               not-empty)]
          (do
            ; store reports in DB
            (store/insert-reports-batched storage, id, report-list)
            ; check for new errors or warnings
            (let [{:strs [error, warn]} (group-by :type report-list)]
              (when (or (seq error) (seq warn))
                (handle-reports mailer, server-config, error, warn)))
            ; if specified, remove reports from the server to save space
            (when delete-reports?
              (mon/delete-reports monitor, (mapv :id report-list))))
          ; no new reports found
          (log/infof "[%s] No new reports!" server-name)))
      (catch Throwable t
        (log/errorf t "[%s] Exception during report-errors." server-name)))))