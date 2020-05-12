; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.main
  (:gen-class)
  (:require [clojure.tools.cli :as cli]
            [reporter.config :as cfg]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [reporter.crypto :as crypto]
            [reporter.system :as sys]
            [reporter.signal :as sig]
            [com.stuartsierra.component :as comp]
            [clojure.stacktrace :as st])
  (:import (javax.crypto AEADBadTagException)))


(defn exit
  [code]
  (System/exit code))


(defmacro get-version
  []
  (System/getProperty "reporter-daemon.version"))


(def current-version (get-version))




(defn print-init-help
  ([banner]
   (print-init-help banner, nil))
  ([banner, msg]
   (when msg
     (println msg "\n")
     (println))
   (println
     (format
       (str "reporter-daemon %s\n"
         "Usage: java -jar reporter-daemon-%s.jar init <options>\n\n"
         "Options:\n"
         "%s")
       current-version
       current-version
       banner))
   (flush)))

(def ^:private init-options
  [["-h" "--help" "Show help." :default false]
   ["-c" "--config FILE" "Specifies the name of the config file to create."]])


(defn run-init
  [args]
  (let [{:keys [options, summary, errors]} (cli/parse-opts args, init-options)
        {:keys [help, config]} options]
    (cond
      (seq errors)
      (do
        (print-init-help summary (str/join "\n" errors))
        (exit 1))

      (or (empty? args) help)
      (print-init-help summary)

      (str/blank? config)
      (do
        (print-init-help summary, "You must specify a config file to initialize.")
        (exit 1))

      :ok
      (cfg/create-initial-config config))))


(defn print-encrypt-help
  ([banner]
   (print-encrypt-help banner, nil))
  ([banner, msg]
   (when msg
     (println msg "\n")
     (println))
   (println
     (format
       (str "reporter-daemon %s\n"
         "Usage: java -jar reporter-daemon-%s.jar encrypt <options>\n\n"
         "Options:\n"
         "%s")
       current-version
       current-version
       banner))
   (flush)))


(def ^:private encrypt-options
  [["-h" "--help" "Show help." :default false]
   ["-c" "--config FILE" "Specifies the config file to encrypt."]])


(defn run-encrypt
  [args]
  (let [{:keys [options, summary, errors]} (cli/parse-opts args, encrypt-options)
        {:keys [help, config]} options]
    (cond
      (seq errors)
      (do
        (print-encrypt-help summary (str/join "\n" errors))
        (exit 1))

      (or (empty? args) help)
      (print-encrypt-help summary)

      (str/blank? config)
      (do
        (print-encrypt-help summary, "You must specify a config file to encrypt.")
        (exit 1))

      (not (.exists (io/file config)))
      (do
        (print-encrypt-help summary,
          (format "The config file \"%s\" that you specified does not exist!" config))
        (exit 1))

      :ok
      (if-let [password (cfg/get-password "Enter password:")]
        (cfg/encrypt-config-file password, config)
        (print-encrypt-help summary,
          "You must specify a master password that is used to encrypt user names and passwords in the config file.")))))


(defn print-download-help
  ([banner]
   (print-download-help banner, nil))
  ([banner, msg]
   (when msg
     (println msg "\n")
     (println))
   (println
     (format
       (str "reporter-daemon %s\n"
         "Usage: java -jar reporter-daemon-%s.jar download <options>\n\n"
         "Options:\n"
         "%s")
       current-version
       current-version
       banner))
   (flush)))


(def ^:private download-options
  [["-h" "--help" "Show help." :default false]
   ["-H" "--host HOST" "Specifies the host whose certificate shall be downloaded."]
   ["-P" "--port PORT" "Specifies the port on which the web application is delivered." :parse-fn #(Long/parseLong %) :default 443]
   ["-T" "--target FILE" "Specifies the filename used to save the downloaded certificate."]])


(defn run-download
  [args]
  (let [{:keys [options, summary, errors]} (cli/parse-opts args, download-options)
        {:keys [help, host, port, target]} options]
    (cond
      (seq errors)
      (do
        (print-download-help summary (str/join "\n" errors))
        (exit 1))

      (or (empty? args) help)
      (print-download-help summary)

      (str/blank? host)
      (do
        (print-download-help summary, "You must specify a host.")
        (exit 1))

      (str/blank? host)
      (do
        (print-download-help summary, "You must specify a target file.")
        (exit 1))

      :ok
      (crypto/download-certificate target, host, port))))


(defn cause
  [^Throwable t]
  (loop [^Throwable t t]
    (if-let [c (.getCause t)]
      (recur c)
      t)))


(defn initial-cause?
  [class, exception]
  (instance? class (cause exception)))


(defn print-run-help
  ([banner]
   (print-run-help banner, nil))
  ([banner, msg]
   (when msg
     (println msg "\n")
     (println))
   (println
     (format
       (str "reporter-daemon %s\n"
         "Usage: java -jar reporter-daemon-%s.jar run <options>\n\n"
         "Options:\n"
         "%s")
       current-version
       current-version
       banner))
   (flush)))


(def ^:private run-options
  [["-h" "--help" "Show help." :default false]
   ["-c" "--config FILE" "Specifies the name of the config file."]])


(defn run-daemon
  [args]
  (let [{:keys [options, summary, errors]} (cli/parse-opts args, encrypt-options)
        {:keys [help, config]} options]
    (cond
      (seq errors)
      (do
        (print-run-help summary (str/join "\n" errors))
        (exit 1))

      (or (empty? args) help)
      (print-run-help summary)

      (str/blank? config)
      (do
        (print-run-help summary, "You must specify a config file.")
        (exit 1))

      (not (.exists (io/file config)))
      (do
        (print-run-help summary,
          (format "The config file \"%s\" that you specified does not exist!" config))
        (exit 1))

      :ok
      (let [system (sys/new-system config)
            system (try
                     (comp/start-system system)
                     (catch AEADBadTagException e
                       (exit 2))
                     (catch Throwable t
                       (if (initial-cause? AEADBadTagException t)
                         (exit 2)
                         (binding [*out* *err*]
                           (println "Exception occurred during system start:")
                           (st/print-cause-trace t)))))
            shutdown-promise (sig/setup-system-stop-on-signal system)]
        (deref shutdown-promise)))))


(defn print-main-help
  ([banner]
   (print-main-help banner, nil))
  ([banner, msg]
   (when msg
     (println msg "\n")
     (println))
   (println
     (format
       (str "reporter-daemon %s\n"
         "Usage: java -jar reporter-daemon-%s.jar [-h] [init|encrypt|download|run] <options>\n\n"
         "Options:\n"
         "%s")
       current-version
       current-version
       banner))
   (flush)))


(def ^:private main-options
  [["-h" "--help" "Show help." :default false]
   ["-V" "--version" "Show version." :default false]])




(defn -main
  [& args]
  (let [{:keys [options, arguments, summary, errors]} (cli/parse-opts args, main-options, :in-order true)
        {:keys [help, version]} options
        command (keyword (first arguments))]
    (cond
      (seq errors)
      (do
        (print-main-help summary (str/join "\n" errors))
        (exit 1))

      version
      (println (format "reporter-daemon %s" current-version))

      (or (empty? args) help)
      (print-main-help summary)

      (= command :init)
      (run-init (rest arguments))

      (= command :encrypt)
      (run-encrypt (rest arguments))

      (= command :download)
      (run-download (rest arguments))

      (= command :run)
      (run-daemon (rest arguments))

      :else
      (do
        (print-main-help summary, "Command must be one of: init, encrypt or run")
        (System/exit 1)))))