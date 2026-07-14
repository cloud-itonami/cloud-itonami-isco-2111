(ns physics.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.governor :as governor]
            [physics.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :title "Galaxy Formation"})
    (store/register-dataset! st {:dataset-id "ds-1" :project-id "proj-1" :description "Hubble"})
    st))

(deftest rejects-unregistered-project-hard
  (let [st (fresh-store)
        request {:project-id "no-project"}
        proposal {:op :analyze-dataset :effect :propose :confidence 0.9}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (seq (:violations verdict)))
    (is (some #(= :no-project (:rule %)) (:violations verdict)))))

(deftest rejects-non-propose-effect-hard
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :analyze-dataset :effect :commit :confidence 0.9}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (some #(= :no-actuation (:rule %)) (:violations verdict)))))

(deftest rejects-missing-dataset-for-analyze-hard
  (let [st (fresh-store)
        request {:project-id "proj-1" :dataset-id "no-dataset"}
        proposal {:op :analyze-dataset :effect :propose :confidence 0.9}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (some #(= :no-dataset (:rule %)) (:violations verdict)))))

(deftest rejects-finalized-manuscript-claim-hard
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :draft-manuscript :effect :propose :confidence 0.9 :finalized? true}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (some #(= :no-finalized-claims (:rule %)) (:violations verdict)))))

(deftest escalates-flag-anomalous-result
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :flag-anomalous-result :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))
    (is (not (:ok? verdict)))))

(deftest escalates-draft-manuscript-with-novel-claim
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :draft-manuscript :effect :propose :confidence 0.9 :novel? true}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))
    (is (not (:ok? verdict)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        request {:project-id "proj-1" :dataset-id "ds-1"}
        proposal {:op :analyze-dataset :effect :propose :confidence 0.4}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))
    (is (not (:ok? verdict)))))

(deftest approves-clean-low-stake-request
  (let [st (fresh-store)
        request {:project-id "proj-1" :dataset-id "ds-1"}
        proposal {:op :analyze-dataset :effect :propose :confidence 0.95 :stake :low}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (not (:escalate? verdict)))
    (is (:ok? verdict))))

(deftest approves-draft-manuscript-without-novel-claim
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :draft-manuscript :effect :propose :confidence 0.85 :novel? false}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (not (:escalate? verdict)))
    (is (:ok? verdict))))
