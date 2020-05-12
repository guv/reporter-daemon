; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.monitor
  (:require [clj-http.client :as http]
            [clj-http.cookies :as cookies]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))



(defprotocol IMonitor
  (GET [monitor, path] [monitor, path, req])
  (POST [monitor, path, req]))


(defrecord Monitor [url, trust-store, cookie-store]

  IMonitor

  (GET [monitor, path]
    (http/get (str url path)
      {:trust-store trust-store
       :cookie-store cookie-store
       :throw-exceptions false}))

  (GET [monitor, path, req]
    (http/get (str url path)
      (merge req
        {:trust-store trust-store
         :cookie-store cookie-store
         :throw-exceptions false})))

  (POST [monitor, path, req]
    (http/post (str url path)
      (merge req
        {:trust-store trust-store
         :cookie-store cookie-store
         :throw-exceptions false}))))


(defn login
  [{:keys [url, user, password, trust-store]}]
  (let [monitor (Monitor. url, trust-store, (cookies/cookie-store))
        {:keys [status]} (POST monitor, "/login", {:form-params {:username user, :password password}})]
    (if (= status 200)
      monitor
      (log/errorf "Login to %s failed with status %d." url, status))))


(defn report-list
  ([monitor]
   (report-list monitor, nil))
  ([monitor, {:keys [type, context] :as params}]
   (->> (GET monitor "/reports/list" {:as :json, :query-params params})
     :body
     :report-list)))


(defn delete-reports
  [monitor, report-ids]
  (POST monitor "/reports/delete"
    {:content-type :application/json
     :body (json/encode {:report-ids (vec report-ids)})
     :as :json}))


(defn system-info
  [monitor]
  (->> (GET monitor "/reports/system" {:as :json})
    :body))


(defn views-per-day
  [monitor]
  (->> (GET monitor "/reports/views" {:as :json})
    :body
    :views-per-day))


(defn test-dates
  [monitor]
  (let [{:keys [body]} (GET monitor "/reports/test-dates" {:as :json})]
    (or (:test-dates body) (:creation-dates body))))

