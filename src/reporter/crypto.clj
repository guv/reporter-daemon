; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.crypto
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import (java.security MessageDigest SecureRandom)
           (javax.crypto Cipher)
           (javax.crypto.spec SecretKeySpec GCMParameterSpec)
           (java.nio.charset StandardCharsets)
           (java.nio CharBuffer)
           (java.util Arrays Base64)
           (javax.net.ssl X509TrustManager SSLContext TrustManager SSLSocket)
           (java.security.cert X509Certificate)))



(def ^:const ^String AES-variant "AES/GCM/NoPadding")


(defn str-bytes
  ^bytes [x]
  (cond
    (string? x)
    (.getBytes ^String x StandardCharsets/UTF_8)

    (instance? (Class/forName "[C") x)
    (let [byte-buffer (.encode StandardCharsets/UTF_8 (CharBuffer/wrap ^chars x))
          bytes (Arrays/copyOfRange (.array byte-buffer), (.position byte-buffer), (.limit byte-buffer))]
      (Arrays/fill (.array byte-buffer), (byte 0))
      bytes)

    :else
    (throw (IllegalArgumentException. (format "Unsupported type: %s" (class x))))))


(defn derive-key
  ^SecretKeySpec [^String password]
  (as-> (str-bytes password) key-data
    (.digest (MessageDigest/getInstance "SHA-256") key-data)
    (SecretKeySpec. key-data, "AES")))


(defn new-cipher
  ^Cipher [mode, password, iv]
  (doto (Cipher/getInstance AES-variant)
    (.init
      (int (case mode
             :encrypt Cipher/ENCRYPT_MODE
             :decrypt Cipher/DECRYPT_MODE))
      (derive-key password)
      (GCMParameterSpec. 128, iv))))


(defn encode-base64
  ^String [^bytes data]
  (.encodeToString (Base64/getEncoder) data))


(def default-line-separator (into-array Byte/TYPE [13 10]))

(defn encode-base64-mime
  ^String [^bytes data]
  (.encodeToString (Base64/getMimeEncoder 64, default-line-separator) data))


(defn decode-base64
  ^bytes [^String s]
  (.decode (Base64/getDecoder) s))


(defn random-iv
  ^bytes []
  (let [prng (SecureRandom.)
        iv (byte-array 32)]
    (.nextBytes prng iv)
    iv))


(defn prepend-iv
  ^bytes [^bytes iv, ^bytes encrypted-bytes]
  (let [iv-length (alength iv)
        encrypted-length (alength encrypted-bytes)
        concatenation (byte-array (+ 1 iv-length encrypted-length))]
    (aset-byte concatenation, 0 iv-length)
    (System/arraycopy iv, 0, concatenation, 1, iv-length)
    (System/arraycopy encrypted-bytes, 0, concatenation, (+ iv-length 1), encrypted-length)
    concatenation))


(defn encrypt
  [password, content]
  (if content
    (let [iv (random-iv)
          cipher (new-cipher :encrypt, password, iv)
          encrypted-bytes (.doFinal cipher, (str-bytes content))]
      (encode-base64 (prepend-iv iv, encrypted-bytes)))
    (do
      (log/warn (Exception.) "You tried to encrypt a nil value")
      nil)))


(defn extract-iv+encrypted
  [^bytes data]
  (let [iv-length (aget data 0)
        iv (byte-array iv-length)
        encrypted-length (- (alength data) 1 iv-length)
        encrypted (byte-array encrypted-length)]
    (System/arraycopy data, 1, iv, 0, iv-length)
    (System/arraycopy data, (+ 1 iv-length), encrypted, 0, encrypted-length)
    [iv, encrypted]))


(defn decrypt
  [password, encrypted]
  (let [[iv, encrypted] (extract-iv+encrypted (decode-base64 encrypted))
        cipher (new-cipher :decrypt, password, iv)
        decrypted-bytes (.doFinal cipher, encrypted)]
    (String. decrypted-bytes, "UTF-8")))


(defn trusty-trustmanager
  []
  (reify X509TrustManager
    (checkServerTrusted [this x509Certificates s]
      )
    (checkClientTrusted [this x509Certificates s]
      )
    (getAcceptedIssuers [this]
      (make-array X509Certificate 0))))


(defn trusty-ssl-context
  ^SSLContext []
  (doto (SSLContext/getInstance "TLS")
    (.init nil, (into-array TrustManager [(trusty-trustmanager)]), nil)))


(defn encoded-certificate
  [^X509Certificate certificate]
  (->> certificate
    .getEncoded
    encode-base64-mime
    (format "-----BEGIN CERTIFICATE-----\n%s\n-----END CERTIFICATE-----")))


(defn download-certificate
  [filename, ^String host, ^long port]
  (let [context (trusty-ssl-context)
        socket-factory (.getSocketFactory context)
        certificates (with-open [^SSLSocket socket (.createSocket socket-factory host, port)]
                       (.getPeerCertificates (.getSession socket)))]
    (->> certificates
      (mapv encoded-certificate)
      (str/join "\n")
      (spit filename))))