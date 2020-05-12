; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.system
  (:require [com.stuartsierra.component :as comp]
            [reporter.storage :as store]
            [reporter.daemon :as daemon]
            [reporter.truststore :as trust]
            [reporter.config :as cfg]
            [reporter.mail :as mail]))



(defn new-system
  [config-file]
  (comp/system-map
    :config (cfg/new-config-provider config-file)
    :storage (comp/using (store/new-storage)
               [:config])
    :truststore-manager (comp/using (trust/new-truststore-manager)
                          [:config])
    :mailer (comp/using (mail/new-mailer)
              [:config])
    :daemon (comp/using (daemon/new-daemon)
              [:config, :storage, :truststore-manager, :mailer])))