; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the LICENSE file at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns reporter.mail
  (:require [postal.core :as post]
            [com.stuartsierra.component :as comp]
            [clojure.tools.logging :as log]))



(defrecord Mailer [config, server-settings, sender-address, receiver-address, timezone]

  comp/Lifecycle

  (start [mailer]
    (log/info "Starting Mailer")
    (let [{:keys [config-map]} config
          {server-settings :settings
           :keys [sender, receiver]} (:email config-map)]
      (assoc mailer
        :server-settings server-settings
        :sender-address sender
        :receiver-address receiver
        :timezone (:timezone config-map))))

  (stop [mailer]
    (log/info "Stopping Mailer")
    (assoc mailer
      :server-settings nil
      :sender-address nil
      :receiver-address nil
      :timezone nil)))


(defn new-mailer
  []
  (map->Mailer {}))



(defn send-mail
  ([mailer, title, message]
   (send-mail mailer, title, message, {}))
  ([{:keys [server-settings, sender-address, receiver-address] :as mailer}, title, message, options]
   (post/send-message server-settings,
     (merge
       {:from sender-address
        :to receiver-address
        :subject title,
        :body message}
       options))))