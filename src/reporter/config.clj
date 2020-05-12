; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.config
  (:require [clojure.string :as str]
            [reporter.crypto :as crypto]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.edn :as edn]
            [com.stuartsierra.component :as comp]
            [reporter.scheduling :as sched]
            [clojure.tools.logging :as log]
            [clojure.stacktrace :as st])
  (:import (javax.swing JPanel JPasswordField JLabel JOptionPane)
           (java.io PushbackReader)
           (java.util Properties)
           (org.apache.log4j PropertyConfigurator)
           (java.security GeneralSecurityException)
           (javax.crypto AEADBadTagException)))



(def ^:const example-config
  {:encrypted? false
   :console-log? true
   :storage {:database "reports.db"}
   :certificates {:path "certificates"}
   :servers [{:name "CTest"
              :url "https://localhost:8443"
              :user "reporter"
              :password "reporter"
              :delete-reports? false
              :error-report {:minute 0
                             :period 2}
              :memory-check {:minute 0
                             :period 2
                             :warn-at 0.75}
              :daily-summary {:hour 0
                              :minute 0}}]
   :timezone "Europe/Berlin"
   :email {:settings {:host "mail.uni-ulm.de"
                      :tls :yes
                      :port 587
                      :user "jsmith"
                      :pass "secret"}
           :sender "reporter@example.org"
           :receiver "info-list@example.org"}})


(defn password-dialog
  [^String message]
  (let [password-field (JPasswordField. 20)
        chosen-option (JOptionPane/showConfirmDialog nil, password-field, message,
                        JOptionPane/OK_CANCEL_OPTION, JOptionPane/PLAIN_MESSAGE)]
    (when (= chosen-option JOptionPane/OK_OPTION)
      (let [pw (.getPassword password-field)]
        (when-not (zero? (alength pw))
          pw)))))


(defn console-available?
  []
  (boolean (System/console)))


(defn password-console
  [^String message]
  (let [console (System/console)]
    (.print (System/out) message)
    (.readPassword console)))


(defn get-password
  [message]
  (let [pw-fn (if (console-available?)
                password-console
                password-dialog)]
    (pw-fn message)))


(defn update-in-if-needed
  [m, path, f & args]
  (if (get-in m path)
    (apply update-in m path f args)
    m))


(defn update-if-needed
  [m, key, f & args]
  (if (get m key)
    (apply update m key f args)
    m))


(defn transform-config
  [f, config-map]
  (-> config-map
    (update-in-if-needed [:email, :settings, :user] f)
    (update-in-if-needed [:email, :settings, :pass] f)
    (update-in-if-needed [:servers]
      (fn [server-list]
        (mapv
          #(-> % (update-if-needed :user f) (update-if-needed :password f))
          server-list)))))


(defn encrypt-config
  [password, config-map]
  (-> (transform-config (partial crypto/encrypt password) config-map)
    (assoc :encrypted? true)))


(defn decrypt-config
  [password, config-map]
  (-> (transform-config (partial crypto/decrypt password) config-map)
    (assoc :encrypted? false)))


(defn write-config-file
  [filename, config-map]
  (with-open [w (io/writer filename)]
    (pp/pprint config-map w)))


(defn create-initial-config
  [filename]
  (write-config-file filename, example-config))


(defn encrypt-config-file
  [password, config-file]
  (let [{:keys [encrypted?] :as config-map} (-> config-file io/reader (PushbackReader.) edn/read)]
    (if encrypted?
      (println (format "The config file \"%s\" is already marked as encrypted." config-file))
      (write-config-file config-file, (encrypt-config password, config-map)))))


(defn load-config
  [config-file]
  (if (-> config-file io/file .exists)
    (let [{:keys [encrypted?] :as config-map} (-> config-file io/reader (PushbackReader.) edn/read)]
      (if encrypted?
        (try
          (decrypt-config (get-password "Enter password:") config-map)
          (catch AEADBadTagException e
            (binding [*out* *err*]
              (println "You entered the wrong password!")
              (throw e)))
          (catch Throwable t
            (binding [*out* *err*]
              (println "Failed to decrypt config file.")
              (st/print-cause-trace t))
            (throw (Exception. (format "Failed to decrypt config file \"%s\"." config-file), t))))
        config-map))
    (throw (IllegalArgumentException. (format "Config file \"%s\" does not exist!" config-file)))))


(defn configure-logging
  "Configures the logging for ctest. Log level and log file can be specified in the configuration."
  [{:keys [log-level, log-file, console-log?] :or {log-level "INFO", log-file "daemon.log"} :as config}]
  (let [props (doto (Properties.)
                (.setProperty "log4j.rootLogger" (format "%s, file" (-> log-level name str/upper-case)))
                (.setProperty "log4j.appender.file" "org.apache.log4j.RollingFileAppender")
                (.setProperty "log4j.appender.file.File" (str log-file))
                (.setProperty "log4j.appender.file.MaxFileSize" "4MB")
                (.setProperty "log4j.appender.file.MaxBackupIndex" "5")
                (.setProperty "log4j.appender.file.layout" "org.apache.log4j.PatternLayout")
                (.setProperty "log4j.appender.file.layout.ConversionPattern" "%d{yyyy.MM.dd HH:mm:ss} %5p %c: %m%n")
                ; jetty is too chatty
                (.setProperty "log4j.logger.org.eclipse.jetty" "WARN")
                ; only interested in warnings or more severe logs from connection pooling
                (.setProperty "log4j.logger.com.mchange.v2.c3p0" "WARN"))]
    (when console-log?
      (doto props
        (.setProperty "log4j.rootLogger" (format "%s, file, console" (-> log-level name str/upper-case)))
        (.setProperty "log4j.appender.console" "org.apache.log4j.ConsoleAppender")
        (.setProperty "log4j.appender.console.Target" "System.err")
        (.setProperty "log4j.appender.console.layout" "org.apache.log4j.PatternLayout")
        (.setProperty "log4j.appender.console.layout.ConversionPattern" "%d{yyyy.MM.dd HH:mm:ss} %5p %c: %m%n")))
    (PropertyConfigurator/configure props))
  nil)


(defrecord ConfigProvider [config-file, config-map]

  comp/Lifecycle

  (start [config-provider]
    (log/info "Starting Config")
    (let [config-map (load-config config-file)]
      (configure-logging config-map)
      (assoc config-provider :config-map config-map)))

  (stop [config-provider]
    (log/info "Stopping Config")
    (assoc config-provider :config-map nil)))


(defn new-config-provider
  [config-file]
  (map->ConfigProvider {:config-file config-file}))