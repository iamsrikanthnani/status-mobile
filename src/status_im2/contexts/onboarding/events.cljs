(ns status-im2.contexts.onboarding.events
  (:require
    [clojure.string :as string]
    [native-module.core :as native-module]
    [re-frame.core :as re-frame]
    [status-im.ethereum.core :as ethereum]
    [status-im.utils.types :as types]
    [status-im2.config :as config]
    [status-im2.constants :as constants]
    [taoensso.timbre :as log]
    [utils.i18n :as i18n]
    [utils.re-frame :as rf]
    [utils.security.core :as security]))

(re-frame/reg-fx
 :multiaccount/create-account-and-login
 (fn [request]
   (native-module/create-account-and-login request)))

(re-frame/reg-fx
 :multiaccount/validate-mnemonic
 (fn [[mnemonic on-success on-error]]
   (native-module/validate-mnemonic
    (security/safe-unmask-data mnemonic)
    (fn [result]
      (let [{:keys [error keyUID]} (types/json->clj result)]
        (if (seq error)
          (when on-error (on-error error))
          (on-success mnemonic keyUID)))))))

(re-frame/reg-fx
 :multiaccount/restore-account-and-login
 (fn [request]
   (native-module/restore-account-and-login request)))

(rf/defn profile-data-set
  {:events [:onboarding-2/profile-data-set]}
  [{:keys [db]} onboarding-data]
  {:db       (update db :onboarding-2/profile merge onboarding-data)
   :dispatch [:navigate-to :create-profile-password]})

(rf/defn enable-biometrics
  {:events [:onboarding-2/enable-biometrics]}
  [_]
  {:biometric-auth/authenticate [#(rf/dispatch [:onboarding-2/biometrics-done %]) {}]})

(rf/defn show-biometrics-message
  [cofx bioauth-message bioauth-code]
  (let [content (or (when (get #{"NOT_AVAILABLE" "NOT_ENROLLED"} bioauth-code)
                      (i18n/label :t/grant-face-id-permissions))
                    bioauth-message)]
    (when content
      {:utils/show-popup
       {:title   (i18n/label :t/biometric-auth-login-error-title)
        :content content}})))

(rf/defn biometrics-done
  {:events [:onboarding-2/biometrics-done]}
  [{:keys [db] :as cofx} {:keys [bioauth-success bioauth-message bioauth-code]}]
  (if bioauth-success
    {:db       (assoc-in db [:onboarding-2/profile :auth-method] constants/auth-method-biometric)
     :dispatch [:onboarding-2/create-account-and-login]}
    (show-biometrics-message cofx bioauth-message bioauth-code)))

(defn strip-file-prefix
  [path]
  (when path
    (string/replace-first path "file://" "")))

(rf/defn create-account-and-login
  {:events [:onboarding-2/create-account-and-login]}
  [{:keys [db]}]
  (let [{:keys [display-name
                seed-phrase
                password
                image-path
                color]} (:onboarding-2/profile db)
        log-enabled?    (boolean (not-empty config/log-level))
        effect          (if seed-phrase
                          :multiaccount/restore-account-and-login
                          :multiaccount/create-account-and-login)
        request         {:displayName                 display-name
                         :deviceName                  (native-module/get-installation-name)
                         :password                    (ethereum/sha3 (security/safe-unmask-data
                                                                      password))
                         :mnemonic                    (when seed-phrase
                                                        (security/safe-unmask-data seed-phrase))
                         :imagePath                   (strip-file-prefix image-path)
                         :customizationColor          color
                         :backupDisabledDataDir       (native-module/backup-disabled-data-dir)
                         :rootKeystoreDir             (native-module/keystore-dir)
                         ;; Temporary fix until https://github.com/status-im/status-go/issues/3024 is
                         ;; resolved
                         :wakuV2Nameserver            "1.1.1.1"
                         :logLevel                    (when log-enabled? config/log-level)
                         :logEnabled                  log-enabled?
                         :logFilePath                 (native-module/log-file-directory)
                         :openseaAPIKey               config/opensea-api-key
                         :verifyTransactionURL        config/verify-transaction-url
                         :verifyENSURL                config/verify-ens-url
                         :verifyENSContractAddress    config/verify-ens-contract-address
                         :verifyTransactionChainID    config/verify-transaction-chain-id
                         :upstreamConfig              config/default-network-rpc-url
                         :networkId                   config/default-network-id
                         :poktToken                   config/POKT_TOKEN
                         :infuraToken                 config/INFURA_TOKEN

                         :alchemyOptimismMainnetToken config/ALCHEMY_OPTIMISM_MAINNET_TOKEN
                         :alchemyOptimismGoerliToken  config/ALCHEMY_OPTIMISM_GOERLI_TOKEN
                         :alchemyArbitrumMainnetToken config/ALCHEMY_ARBITRUM_MAINNET_TOKEN
                         :alchemyArbitrumGoerliToken  config/ALCHEMY_ARBITRUM_GOERLI_TOKEN

                         :currentNetwork              config/default-network
                         :previewPrivacy              config/blank-preview?}]
    {effect    request
     :dispatch [:navigate-to :generating-keys]
     :db       (-> db
                   (dissoc :profile/login)
                   (dissoc :auth-method)
                   (assoc :onboarding-2/new-account? true))}))

(rf/defn on-delete-profile-success
  {:events [:onboarding-2/on-delete-profile-success]}
  [{:keys [db]} key-uid]
  (let [multiaccounts (dissoc (:profile/profiles-overview db) key-uid)]
    (merge
     {:db (assoc db :profile/profiles-overview multiaccounts)}
     (when-not (seq multiaccounts)
       {:set-root :intro}))))

(rf/defn password-set
  {:events [:onboarding-2/password-set]}
  [{:keys [db]} password]
  {:db       (-> db
                 (assoc-in [:onboarding-2/profile :password] password)
                 (assoc-in [:onboarding-2/profile :auth-method] constants/auth-method-password))
   :dispatch [:navigate-to :enable-biometrics]})

(rf/defn seed-phrase-entered
  {:events [:onboarding-2/seed-phrase-entered]}
  [_ seed-phrase on-error]
  {:multiaccount/validate-mnemonic [seed-phrase
                                    (fn [mnemonic key-uid]
                                      (re-frame/dispatch [:onboarding-2/seed-phrase-validated
                                                          mnemonic key-uid]))
                                    on-error]})

(rf/defn seed-phrase-validated
  {:events [:onboarding-2/seed-phrase-validated]}
  [{:keys [db]} seed-phrase key-uid]
  (if (contains? (:profile/profiles-overview db) key-uid)
    {:utils/show-confirmation
     {:title               (i18n/label :t/multiaccount-exists-title)
      :content             (i18n/label :t/multiaccount-exists-content)
      :confirm-button-text (i18n/label :t/unlock)
      :on-accept           (fn []
                             (re-frame/dispatch [:pop-to-root :profiles])
                             (re-frame/dispatch
                              [:profile/profile-selected key-uid]))
      :on-cancel           #(re-frame/dispatch [:pop-to-root :multiaccounts])}}
    {:db       (assoc-in db [:onboarding-2/profile :seed-phrase] seed-phrase)
     :dispatch [:navigate-to :create-profile]}))

(rf/defn navigate-to-create-profile
  {:events [:onboarding-2/navigate-to-create-profile]}
  [{:keys [db]}]
  ;; Restart the flow
  {:db       (dissoc db :onboarding-2/profile)
   :dispatch [:navigate-to :create-profile]})

(rf/defn onboarding-new-account-finalize-setup
  {:events [:onboarding-2/finalize-setup]}
  [{:keys [db]}]
  (let [masked-password    (get-in db [:onboarding-2/profile :password])
        key-uid            (get-in db [:profile/profile :key-uid])
        biometric-enabled? (=
                            constants/auth-method-biometric
                            (get-in db [:onboarding-2/profile :auth-method]))]

    (cond-> {:dispatch [:navigate-to :identifiers]}
      biometric-enabled?
      (assoc :biometric/enable-and-save-password
             {:key-uid         key-uid
              :masked-password masked-password
              :on-success      #(log/debug "successfully saved biometric")
              :on-error        #(log/error "failed to save biometrics"
                                           {:key-uid key-uid
                                            :error   %})}))))
