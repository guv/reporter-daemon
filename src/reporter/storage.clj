; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.storage
  (:require [com.stuartsierra.component :as comp]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.protocols :as jdbc-proto]
            [clojure.java.io :as io]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [clojure.string :as str]
            [next.jdbc.prepare :as p]
            [clojure.tools.logging :as log])
  (:import (com.mchange.v2.c3p0 ComboPooledDataSource)
           (java.sql DriverManager SQLException)))


;TODO: Apache Derby zickt noch rum - wie geht create on demand genau?
(defn connection-pool
  [database-name]
  (doto (ComboPooledDataSource.)
    (.setJdbcUrl
      (str "jdbc:derby:" database-name))
    #_(.setUser (:user spec))
    #_(.setPassword (:password spec))
    ;; expire excess connections after 30 minutes of inactivity:
    (.setMaxIdleTimeExcessConnections (* 30 60))
    ;; expire connections after 3 hours of inactivity:
    (.setMaxIdleTime (* 3 60 60))))


(defn setup-derby
  []
  (.newInstance (Class/forName "org.apache.derby.jdbc.EmbeddedDriver"))
  nil)


(defn shutdown-derby
  []
  (try
    ; throws sql exception
    (DriverManager/getConnection "jdbc:derby:;shutdown=true")
    (catch SQLException e
      nil)))


(defn db-exists?
  [database-name]
  (.exists (io/file database-name)))


(defn create-report-table
  [conn]
  (jdbc/execute-one! conn
    ["CREATE TABLE reports (
      id INTEGER PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
      server_id INTEGER NOT NULL REFERENCES servers(id),
      remote_id INTEGER NOT NULL,
      timestamp BIGINT NOT NULL,
      type VARCHAR(10),
      context VARCHAR(40),
      message VARCHAR(10000))"]))


(defn create-server-table
  [conn]
  (jdbc/execute-one! conn
    ["CREATE TABLE servers (
      id INTEGER PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
      url VARCHAR(200))"]))


(defn create-database
  [database-name]
  (with-open [conn (DriverManager/getConnection (format "jdbc:derby:%s;create=true", database-name))]
    (create-server-table conn)
    (create-report-table conn)))


(defrecord DBStorage [config, database-name, pool]
  comp/Lifecycle
  (start [db-storage]
    (log/info "Starting DBStorage")
    (setup-derby)
    (let [{:keys [config-map]} config
          database-name (get-in config-map [:storage, :database])]
      (when (str/blank? database-name)
        (throw (Exception. "No database name specified (:storage :database)!")))
      (when-not (db-exists? database-name)
        (create-database database-name))
      (assoc db-storage
        :database-name database-name
        :pool (connection-pool database-name))))

  (stop [db-storage]
    (log/info "Stopping DBStorage")
    (some-> ^ComboPooledDataSource pool .close)
    (shutdown-derby)
    (assoc db-storage :pool nil)))


(extend-protocol jdbc-proto/Sourceable
  DBStorage
  (get-datasource [this]
    (:pool this)))


(defn new-storage
  []
  (map->DBStorage {}))


(defn table-list
  [db-storage]
  (not-empty
    (persistent!
      (reduce
        (fn [result-list, {:keys [tablename]}]
          (conj! result-list (str/lower-case tablename)))
        (transient [])
        (jdbc/plan db-storage
          ["SELECT tablename FROM sys.systables WHERE tabletype = 'T'"]
          {:builder-fn rs/as-unqualified-lower-maps})))))


(def ^:const report-attributes [:id, :timestamp, :type, :context, :message, :server_id])
(def ^:const report-insert-statement (format "INSERT INTO reports (%s) values (%s)"
                                       (->> report-attributes rest (mapv name) (list* "remote_id") (str/join ","))
                                       (->> (repeat (count report-attributes) "?") (str/join ","))))

(defn insert-reports-batched
  [db-storage, server-id, report-list]
  (with-open [con (jdbc/get-connection db-storage)
              ps (jdbc/prepare con [report-insert-statement])]
    (let [insert-count (reduce
                         (fn [count, report]
                           ; batch up insertions
                           (p/set-parameters ps
                             (mapv (assoc report :server_id server-id) report-attributes))
                           (.addBatch ps)
                           (inc count))
                         0
                         report-list)]
      ; execute batch
      (.executeBatch ps)
      insert-count)))


(defn report-remote-identification-set
  "Get the remote ids of the stored reports."
  [db-storage, server-id]
  (not-empty
    (persistent!
      (reduce
        (fn [result-list, {:keys [remote_id, timestamp]}]
          (conj! result-list [remote_id, timestamp]))
        (transient #{})
        (jdbc/plan db-storage
          ["SELECT remote_id, timestamp FROM reports WHERE server_id = ?" server-id]
          {:builder-fn rs/as-unqualified-lower-maps})))))


(defn add-server
  [con, url]
  (-> (jdbc/execute-one! con
        ["INSERT INTO servers (url) VALUES (?)" (-> url str/trim str/lower-case)]
        {:return-keys true})
    vals
    first
    long))


(defn server-by-url
  [con, url]
  (jdbc/execute-one! con
    ["SELECT * FROM servers WHERE url = ?" (-> url str/trim str/lower-case)]
    {:builder-fn rs/as-unqualified-lower-maps}))