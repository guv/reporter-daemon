; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.truststore
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as comp]
            [clojure.tools.logging :as log])
  (:import (java.io FilenameFilter)
           (java.security.cert CertificateFactory)
           (java.security KeyStore)
           (javax.net.ssl TrustManagerFactory SSLContext HttpsURLConnection)
           (org.apache.http.conn.ssl SSLConnectionSocketFactory)))



(defn list-certificates
  [path]
  (when path
    (.listFiles (io/file path)
      (reify FilenameFilter
        (accept [this dir name]
          (-> name str/lower-case (str/ends-with? ".pem")))))))


(defn load-certificate
  [cert-file]
  (->> cert-file
    io/input-stream
    (.generateCertificate (CertificateFactory/getInstance "X.509"))))


(defn build-keystore
  ^KeyStore [certificate-list]
  (let [ks (doto (KeyStore/getInstance (KeyStore/getDefaultType))
             (.load nil, nil))]
    (doseq [[i, cert] (map vector (range) certificate-list)]
      (.setCertificateEntry ks (Integer/toString i), cert))
    ks))


(defn certificate-keystore
  ^KeyStore [certificate-path]
  (->> certificate-path
    list-certificates
    (map load-certificate)
    build-keystore))


(defn setup-trusted-certificates
  [certificate-path]
  (let [ks (certificate-keystore certificate-path)
        tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init ks))
        ctx (doto (SSLContext/getInstance "TLS")
              (.init nil, (.getTrustManagers tmf), nil))]
    (SSLContext/setDefault ctx)
    (SSLConnectionSocketFactory/getSocketFactory)
    (HttpsURLConnection/setDefaultSSLSocketFactory (.getSocketFactory ctx))
    nil))


(defrecord TruststoreManager [config, certificate-path, trust-store]

  comp/Lifecycle

  (start [truststore-manager]
    (log/info "Starting TruststoreManager")
    (let [{:keys [config-map]} config
          certificate-path (get-in config-map [:certificates, :path])]
      (assoc truststore-manager
        :certificate-path certificate-path
        :trust-store (certificate-keystore certificate-path))))

  (stop [truststore-manager]
    (log/info "Stoping TruststoreManager")
    (assoc truststore-manager :trust-store nil)))


(defn new-truststore-manager
  []
  (map->TruststoreManager {}))