(ns physics.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.actor :as actor]
            [physics.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :title "Galaxy Formation Study"})
    (store/register-dataset! st {:dataset-id "ds-1" :project-id "proj-1" :description "Hubble observations"})
    (store/register-instrument! st {:instrument-id "inst-1" :name "Hubble Space Telescope"})
    st))

(deftest commits-a-clean-low-risk-analysis-request
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:project-id "proj-1" :dataset-id "ds-1" :op :analyze-dataset :stake :low}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "proj-1"))))))

(deftest holds-on-unregistered-project-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:project-id "no-such-project" :op :analyze-dataset :stake :low :dataset-id "ds-1"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "no-such-project")))
    (is (= :hold (:disposition (:state result))))))

(deftest holds-on-missing-dataset-for-analyze-op
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:project-id "proj-1" :dataset-id "no-such-ds" :op :analyze-dataset :stake :low}
        result (actor/run-request! graph request {} "thread-3")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "proj-1")))
    (is (= :hold (:disposition (:state result))))))

(deftest interrupts-then-commits-on-human-approval-for-anomaly-flag
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; flag-anomalous-result always escalates (governor invariant)
        request {:project-id "proj-1" :op :flag-anomalous-result :stake :high}
        interrupted (actor/run-request! graph request {} "thread-4")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "proj-1")))
    (let [resumed (actor/approve! graph "thread-4")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record])))
      (is (= 1 (count (store/records-of st "proj-1")))))))

(deftest holds-on-finalized-manuscript-claim-in-draft
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; manuscript proposals claiming finalization are hard-rejected
        request {:project-id "proj-1" :op :draft-manuscript :stake :high}
        proposal {:op :draft-manuscript :effect :propose :stake :high :confidence 0.8 :finalized? true}
        result (actor/run-request! graph request {} "thread-5")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "proj-1")))
    (is (= :hold (:disposition (:state result))))))
