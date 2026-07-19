(ns physics.advisor
  "ResearchAdvisor — proposes a research operation (analyze a dataset,
  draft a manuscript section, flag an anomalous result, request
  instrument time, calibrate an instrument) for a registered project.
  The advisor is swappable: `mock-advisor` (deterministic, default in
  dev/tests/CI) or `llm-advisor` (wraps a real `langchain.model/ChatModel`).
  Either way the advisor ONLY produces a PROPOSAL — it never writes to the
  store and has no notion of project provenance or research-integrity
  risk; `physics.governor` is the independent system that decides whether
  the proposal may proceed, per the itonami actor pattern.

  A proposal is a map:
    {:op :analyze-dataset|:draft-manuscript|:flag-anomalous-result
        |:request-instrument-time|:calibrate-instrument
     :effect :propose        ; the advisor NEVER emits a raw store write
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str
     :finalized? bool        ; optional, :draft-manuscript only — carried
                              ; through from the request when the requester
                              ; declares the draft finalized; the governor's
                              ; no-finalized-claims hard rule reads this
     :novel? bool}           ; optional, :draft-manuscript only — carried
                              ; through from the request when the requester
                              ; declares the draft a novel-result claim; the
                              ; governor's escalation rule reads this
  LLM parse failures always yield `:confidence 0.0` (never fabricate
  confidence), which forces the governor to escalate/hold."
  (:require [clojure.string :as str]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared op/stake
  straight through (a stand-in for what an LLM would extract from free
  text), with a stake-derived confidence. Also carries through the
  request's declared `:finalized?`/`:novel?` claim flags when present —
  these are downstream `physics.governor` hard/escalation triggers on
  `:draft-manuscript` proposals, so a mock advisor that silently dropped
  them (instead of reflecting what the request actually declared) would
  make those governor rules unreachable via the actor pipeline even
  though `physics.governor/check` itself implements them correctly."
  [_store {:keys [op stake finalized? novel?] :as request}]
  (cond-> {:op op
           :effect :propose
           :stake (or stake :low)
           :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
           :rationale (str "proposed " (name op) " for project " (:project-id request))}
    (some? finalized?) (assoc :finalized? finalized?)
    (some? novel?) (assoc :novel? novel?)))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a research advisor for physics and astronomy. Given a
   research operation request, propose an :op, an honest :confidence
   (0.0-1.0), and a :stake (:low/:medium/:high). Never fabricate
   confidence you don't have.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "research operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
