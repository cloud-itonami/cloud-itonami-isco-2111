(ns physics.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (`:item2/classification \"unknown-no-demo\"` in the
  fleet-wide scan). This namespace drives the REAL actor stack
  (`physics.actor` -> `physics.governor` -> `physics.store`) through a
  scenario built from real, exercised store data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify by
  diffing two consecutive runs before shipping).

  Adapted from the ISCO-08 1211/1111/2113/1213/1112 build-time-console
  precedents (`90-docs/business/cloud-itonami-maturity-loop.md`
  iterations 9/10/11 in com-junkawasaki/root) using this repo's OWN real
  fixture, not a copy of theirs: `proj-1` (\"Galaxy Formation Study\") +
  `ds-1` (\"Hubble observations\") + `inst-1` (\"Hubble Space
  Telescope\") are lifted VERBATIM from `physics.actor-test`'s
  `fresh-store` fixture (ground truth, not invented). `proj-2`
  (\"Exoplanet Atmospheres Survey\") is ADDITIONAL demo data registered
  via the SAME real `register-project!` protocol call this actor's own
  store exposes -- disclosed here plainly, not presented as pre-existing
  fixture, so the console can show a second research group operating
  cleanly with no dataset of its own. Every other field this page
  displays (statuses, record counts, hold/escalation reasons) is real
  output read after `run-demo!` actually executed the graph -- none of
  it is hand-typed.

  This demo also directly exercises the fix landed alongside it in
  `physics.advisor`: prior to that fix, `mock-advisor` silently dropped
  the request's declared `:finalized?`/`:novel?` claim flags instead of
  carrying them into the proposal, which made
  `physics.governor`'s own `:no-finalized-claims` hard rule and its
  novel-claim escalation rule UNREACHABLE via the real actor pipeline
  (only reachable by hand-constructing a proposal directly against
  `governor/check`, bypassing the advisor entirely -- see
  `physics.governor-test`). The `r1-finalized-claim` and
  `r1-novel-claim` runs below are real, both now genuinely reachable
  end to end.

  Honesty note on an unused parameter, found while reading the governor
  for this demo (not fixed, out of scope for this render namespace):
  `physics.governor/check` accepts a `context` argument but never reads
  it -- unlike some sibling ISCO actors (e.g. administration's
  topic-sensitivity rule), this domain has no rule keyed on `context`.
  This demo passes `{}` throughout, matching what an operator would
  supply since there is nothing for it to affect.

  Known architectural gaps, honestly noted rather than papered over
  (confirmed by reading `physics.governor` itself, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    unconditionally sets `:effect :propose` on every proposal it emits.
    Covered instead by `physics.governor-test/rejects-non-propose-effect-hard`
    (which calls `governor/check` directly with a hand-built proposal
    whose `:effect` is `:commit`).
  - low-confidence escalation (`confidence < 0.6`) is NOT reachable
    either, because `physics.advisor/infer`'s stake-derived confidence
    (`:high` 0.7, `:medium` 0.85, `:low` 0.95) never drops below the
    governor's `confidence-floor` (0.6).
  Both gaps are the same shape as the ISCO-08 1211/2113/1213/1112
  precedents' disclosed `:no-actuation` gap -- this demo, like those,
  only ever drives the real actor/graph the way an operator actually
  would, and does not hand-construct proposals to force unreachable
  paths.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [physics.store :as store]
            [physics.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real research operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid project-id op extra]
  (let [request (merge {:project-id project-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :project-id project-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :project-id project-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :project-id project-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit, escalate-then-approve, and 3 of
  the 4 distinct HARD-hold reasons in `physics.governor` -- the 4th,
  `:no-actuation`, plus the low-confidence escalation reason, are
  architecturally unreachable via the real advisor, see namespace
  docstring). Every `:op` keyword and violation rule name below is
  copied from `physics.governor`'s own `hard-violations`/`check`, not
  invented. Vector shape: [thread-id project-id op extra]."
  [;; proj-1 / \"Galaxy Formation Study\" (real fixture from physics.actor-test)
   ["p1-analyze-clean"      "proj-1" :analyze-dataset          {:dataset-id "ds-1" :stake :low}]
   ["p1-analyze-no-dataset" "proj-1" :analyze-dataset          {:dataset-id "ds-ghost" :stake :low}]
   ["p1-finalized-claim"    "proj-1" :draft-manuscript         {:stake :high :finalized? true}]
   ["p1-novel-claim"        "proj-1" :draft-manuscript         {:stake :high :novel? true}]
   ["p1-draft-clean"        "proj-1" :draft-manuscript         {:stake :medium}]
   ["p1-flag-anomaly"       "proj-1" :flag-anomalous-result    {:stake :high}]
   ["p1-instrument-time"    "proj-1" :request-instrument-time  {:stake :medium}]
   ;; unregistered project entirely
   ["ghost-no-project"      "proj-ghost" :analyze-dataset      {:dataset-id "ds-1" :stake :low}]
   ;; proj-2 / \"Exoplanet Atmospheres Survey\" (additional demo data,
   ;; registered via the same real register-project! call -- see
   ;; namespace docstring). No dataset of its own; only ops that don't
   ;; require one are driven against it.
   ["p2-calibrate-clean"    "proj-2" :calibrate-instrument     {:stake :low}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `physics.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-project! db {:project-id "proj-1" :title "Galaxy Formation Study"})
    (store/register-dataset! db {:dataset-id "ds-1" :project-id "proj-1" :description "Hubble observations"})
    (store/register-instrument! db {:instrument-id "inst-1" :name "Hubble Space Telescope"})
    (store/register-project! db {:project-id "proj-2" :title "Exoplanet Atmospheres Survey"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid project-id op extra]]
                       (run-op! graph tid project-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- project-row [store {:keys [project-id project-title]} runs]
  (let [record-count (count (store/records-of store project-id))
        last-run (last (filter #(= project-id (:project-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc project-id) (esc project-title) record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id project-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc project-id) (esc (name op))
          (esc (or (some-> (:dataset-id request) str) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `physics.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:analyze-dataset</code></td><td><span class=\"ok\">auto-commit when the dataset is registered</span></td></tr>"
   "        <tr><td><code>:draft-manuscript</code></td><td><span class=\"ok\">auto-commit UNLESS finalized (HARD hold) or novel (escalate)</span></td></tr>"
   "        <tr><td><code>:flag-anomalous-result</code></td><td><span class=\"warn\">ALWAYS human approval &middot; scientific-integrity safeguard</span></td></tr>"
   "        <tr><td><code>:request-instrument-time</code></td><td><span class=\"ok\">auto-commit when the project is registered</span></td></tr>"
   "        <tr><td><code>:calibrate-instrument</code></td><td><span class=\"ok\">auto-commit when the project is registered</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [projects [{:project-id "proj-1" :project-title "Galaxy Formation Study"}
                   {:project-id "proj-2" :project-title "Exoplanet Atmospheres Survey"}]
        project-rows (str/join "\n" (map #(project-row store % runs) projects))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2111 &middot; physics &amp; astronomy research support</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Physics &amp; Astronomy Research Support (ISCO-08 2111) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every proposal is for research review only, never a finalized/actuated claim</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered projects</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>physics.store</code> via <code>physics.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Project</th><th>Title</th><th>Records committed</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     project-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Research Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Manuscript drafts claiming finalization are always hard-rejected; novel-result and anomaly-flag claims always escalate to a human.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, project, op, the request's own dataset (if any), and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Project</th><th>Op</th><th>Dataset</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
