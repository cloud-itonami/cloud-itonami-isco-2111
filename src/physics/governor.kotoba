(ns physics.governor
  "ResearchGovernor — the independent safety/traceability layer for
  the ISCO-08 2111 research support actor (physicists and astronomers).
  Wired as its own `:govern` node in `physics.actor`'s StateGraph,
  downstream of `:advise` — the Advisor has no notion of project
  provenance or research-integrity risk, so this MUST be a separate
  system able to reject a proposal (itonami actor pattern, per
  ADR-2607011000 / CLAUDE.md Actors section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. project provenance  — the request's project must be registered.
    2. dataset verification — :analyze-dataset ops must reference a
                              registered dataset.
    3. no-actuation — proposal :effect must be :propose.
    4. no-finalized-claims — :draft-manuscript proposals can never claim
                             a result as finalized/novel/publishable
                             (draft is draft-for-review only, not the
                             manuscript-is-ready proposal).

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    5. :flag-anomalous-result — always escalates (scientific integrity
                                safeguard, never silently dismissed).
    6. :draft-manuscript with :novel? true — novel claims require
                                             human review before proceeding.
    7. low confidence (< `confidence-floor`)."
  (:require [physics.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:flag-anomalous-result :draft-manuscript})

(defn- hard-violations [{:keys [proposal request]} project-record dataset-record]
  (cond-> []
    (nil? project-record)
    (conj {:rule :no-project :detail "未登録 project"})

    (and (= :analyze-dataset (:op proposal))
         (nil? dataset-record))
    (conj {:rule :no-dataset :detail "analyze-dataset 前に dataset は要登録"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

    (and (= :draft-manuscript (:op proposal))
         (:finalized? proposal))
    (conj {:rule :no-finalized-claims :detail "manuscript 最終化は draft 提案では不可（draft は査読用のみ）"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `physics.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [project-record (store/project store (:project-id request))
        dataset-record (when (:dataset-id request) (store/dataset store (:dataset-id request)))
        hard (hard-violations {:proposal proposal :request request} project-record dataset-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        is-flag? (= :flag-anomalous-result (:op proposal))
        is-draft-novel? (and (= :draft-manuscript (:op proposal)) (:novel? proposal))
        risky-op? (and (contains? escalating-ops (:op proposal))
                       (or is-flag? is-draft-novel?))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
