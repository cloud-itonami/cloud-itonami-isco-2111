(ns physics.store
  "SSoT for the ISCO-08 2111 research support actor (physicists and
  astronomers). Store is a protocol injected into the `physics.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep
  backend; a Datomic/kotoba-server-backed implementation can be
  swapped in without touching the actor or governor (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    project  — a registered research project (:project-id, :title)
    dataset  — a recorded observational/experimental dataset associated
               with a project (:dataset-id, :project-id, :description)
    instrument — a telescope/instrument resource (:instrument-id, :name)
    record   — a committed research operation under a project
               (analysis result, manuscript draft, anomaly flag,
               instrument time request, calibration procedure) — written
               ONLY via commit-record!, never mutated in place
    ledger   — an append-only audit trail of every proposal/verdict/
               disposition, regardless of outcome (commit or hold)")

(defprotocol Store
  (project [s project-id])
  (dataset [s dataset-id])
  (instrument [s instrument-id])
  (records-of [s project-id])
  (ledger [s])
  (register-project! [s project])
  (register-dataset! [s dataset])
  (register-instrument! [s instrument])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (project [_ project-id] (get-in @a [:projects project-id]))
  (dataset [_ dataset-id] (get-in @a [:datasets dataset-id]))
  (instrument [_ instrument-id] (get-in @a [:instruments instrument-id]))
  (records-of [_ project-id] (filter #(= project-id (:project-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-project! [s project]
    (swap! a assoc-in [:projects (:project-id project)] project) s)
  (register-dataset! [s dataset]
    (swap! a assoc-in [:datasets (:dataset-id dataset)] dataset) s)
  (register-instrument! [s instrument]
    (swap! a assoc-in [:instruments (:instrument-id instrument)] instrument) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:projects {} :datasets {} :instruments {} :records [] :ledger []} seed)))))
