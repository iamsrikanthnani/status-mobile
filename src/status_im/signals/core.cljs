(ns status-im.signals.core
  (:require [status-im.chat.models.message :as models.message]
            [status-im.ethereum.subscriptions :as ethereum.subscriptions]
            [utils.i18n :as i18n]
            [status-im.mailserver.core :as mailserver]
            [status-im.multiaccounts.login.core :as login]
            [status-im.notifications.local :as local-notifications]
            [status-im.transport.message.core :as transport.message]
            [status-im.visibility-status-updates.core :as visibility-status-updates]
            [utils.re-frame :as rf]
            [status-im2.contexts.chat.messages.link-preview.events :as link-preview]
            [taoensso.timbre :as log]
            [status-im2.constants :as constants]
            [quo2.foundations.colors :as colors]))

(rf/defn status-node-started
  [{db :db :as cofx} {:keys [error]}]
  (log/debug "[signals] status-node-started"
             "error"
             error)
  (if error
    (cond->
      {:db (-> db
               (update :profile/login dissoc :processing)
               (assoc-in [:profile/login :error]
                         ;; NOTE: the only currently known error is
                         ;; "file is not a database" which occurs
                         ;; when the user inputs the wrong password
                         ;; if that is the error that is found
                         ;; we show the i18n label for wrong password
                         ;; to the user
                         ;; in case of an unknown error we show the
                         ;; error
                         (if (= error "file is not a database")
                           (i18n/label :t/wrong-password)
                           error)))}
      (= (:view-id db) :progress)
      (assoc :dispatch [:navigate-to :login]))
    (login/multiaccount-login-success cofx)))

(rf/defn summary
  [{:keys [db] :as cofx} peers-summary]
  (let [previous-summary (:peers-summary db)
        peers-count      (count peers-summary)]
    (rf/merge cofx
              {:db (assoc db
                          :peers-summary peers-summary
                          :peers-count   peers-count)}
              (visibility-status-updates/peers-summary-change peers-count))))

(rf/defn wakuv2-peer-stats
  [{:keys [db]} peer-stats]
  (let [previous-stats (:peer-stats db)]
    {:db (assoc db
                :peer-stats  peer-stats
                :peers-count (count (:peers peer-stats)))}))

(rf/defn handle-local-pairing-signals
  [{:keys [db] :as cofx} {:keys [type action data error] :as event}]
  (log/info "local pairing signal received"
            {:event event})
  (let [{:keys [account password]}      data
        role                            (get-in db [:syncing :role])
        receiver?                       (= role constants/local-pairing-role-receiver)
        sender?                         (= role constants/local-pairing-role-sender)
        connection-success?             (and (= type
                                                constants/local-pairing-event-connection-success)
                                             (= action
                                                constants/local-pairing-action-connect))
        error-on-pairing?               (contains? constants/local-pairing-event-errors type)
        completed-pairing?              (and (= type
                                                constants/local-pairing-event-transfer-success)
                                             (= action
                                                constants/local-pairing-action-pairing-installation))
        received-account?               (and (= type
                                                constants/local-pairing-event-received-account)
                                             (= action
                                                constants/local-pairing-action-pairing-account)
                                             (and (some? account) (some? password)))
        multiaccount-data               (when received-account?
                                          (merge account {:password password}))
        navigate-to-syncing-devices?    (and (or connection-success? error-on-pairing?) receiver?)
        user-in-syncing-devices-screen? (= (:view-id db) :syncing-progress)]
    (merge {:db (cond-> db
                  connection-success?
                  (assoc-in [:syncing :pairing-status] :connected)

                  received-account?
                  (assoc-in [:syncing :profile/profile] multiaccount-data)

                  error-on-pairing?
                  (assoc-in [:syncing :pairing-status] :error)

                  completed-pairing?
                  (assoc-in [:syncing :pairing-status] :completed))}
           (cond
             (and navigate-to-syncing-devices? (not user-in-syncing-devices-screen?))
             {:dispatch [:navigate-to :syncing-progress]}

             (and completed-pairing? sender?)
             {:dispatch [:syncing/clear-states]}

             (and completed-pairing? receiver?)
             {:dispatch [:multiaccounts.login/local-paired-user]}

             (and error-on-pairing? (some? error))
             {:dispatch [:toasts/upsert
                         {:icon       :i/alert
                          :icon-color colors/danger-50
                          :text       error}]}))))

(rf/defn process
  {:events [:signals/signal-received]}
  [{:keys [db] :as cofx} event-str]
  ;; We only convert to clojure when strictly necessary or we know it
  ;; won't impact performance, as it is a fairly costly operation on large-ish
  ;; data structures
  (let [^js data     (.parse js/JSON event-str)
        ^js event-js (.-event data)
        type         (.-type data)]
    (case type
      "node.login"              (status-node-started cofx (js->clj event-js :keywordize-keys true))
      "backup.performed"        {:db (assoc-in db
                                      [:profile/profile :last-backup]
                                      (.-lastBackup event-js))}
      "envelope.sent"           (transport.message/update-envelopes-status cofx
                                                                           (:ids
                                                                            (js->clj event-js
                                                                                     :keywordize-keys
                                                                                     true))
                                                                           :sent)
      "envelope.expired"        (transport.message/update-envelopes-status cofx
                                                                           (:ids
                                                                            (js->clj event-js
                                                                                     :keywordize-keys
                                                                                     true))
                                                                           :not-sent)
      "message.delivered"       (let [{:keys [chatID messageID]} (js->clj event-js
                                                                          :keywordize-keys
                                                                          true)]
                                  (models.message/update-db-message-status cofx
                                                                           chatID
                                                                           messageID
                                                                           :delivered))
      "mailserver.changed"      (mailserver/handle-mailserver-changed cofx (.-id event-js))
      "mailserver.available"    (mailserver/handle-mailserver-available cofx (.-id event-js))
      "mailserver.not.working"  (mailserver/handle-mailserver-not-working cofx)
      "discovery.summary"       (summary cofx (js->clj event-js :keywordize-keys true))
      "mediaserver.started"     {:db (assoc db :mediaserver/port (.-port event-js))}
      "wakuv2.peerstats"        (wakuv2-peer-stats cofx (js->clj event-js :keywordize-keys true))
      "messages.new"            (transport.message/sanitize-messages-and-process-response cofx
                                                                                          event-js
                                                                                          true)
      "wallet"                  (ethereum.subscriptions/new-wallet-event cofx
                                                                         (js->clj event-js
                                                                                  :keywordize-keys
                                                                                  true))
      "local-notifications"     (local-notifications/process cofx
                                                             (js->clj event-js :keywordize-keys true))
      "community.found"         (link-preview/cache-community-preview-data (js->clj event-js
                                                                                    :keywordize-keys
                                                                                    true))
      "status.updates.timedout" (visibility-status-updates/handle-visibility-status-updates
                                 cofx
                                 (js->clj event-js :keywordize-keys true))
      "localPairing"            (handle-local-pairing-signals cofx
                                                              (js->clj event-js :keywordize-keys true))
      (log/debug "Event " type " not handled"))))
