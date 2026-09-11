# cloud-itonami-isco-2111

Open Occupation Blueprint for **ISCO-08 2111**: Physicists and Astronomers.

This repository designs a forkable OSS research support operation for physics and astronomy: an autonomous advisor proposes research operations (dataset analysis, manuscript drafting, anomaly flagging, instrument time requests, calibration procedures) under a governor-gated actor, ensuring scientific integrity, dataset verification, and human-in-the-loop escalation for novel claims and anomalies.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here an autonomous research advisor proposes analysis pipelines, manuscript preparation, and instrument scheduling under an actor that gates all proposals and an independent **Research Governor** that enforces scientific integrity. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
flagging anomalous results, or proposing novel manuscript claims) require human sign-off.

## Core Contract

```text
project + datasets + instruments + research timeline
        |
        v
Research Advisor -> Research Governor -> analyze/draft/flag, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or finalize a result without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `2111`). Required capabilities:

- :robotics
- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section, alongside `cloud-itonami-isco-2411`, and other occupation actors): a real
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
human-in-the-loop interrupt/resume via checkpointing.

```text
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

- `src/physics/store.kotoba` — `Store` protocol + `MemStore`:
  registered projects, datasets, instruments, committed records, an append-only audit ledger.
- `src/physics/advisor.kotoba` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a research operation from a
  request; `llm-advisor` wraps a `langchain.model/ChatModel` — either
  way the advisor only ever produces a `:propose`-effect proposal,
  never a committed record, and LLM parse failures always yield
  `confidence 0.0` (forces escalation, never fabricated confidence).
- `src/physics/governor.kotoba` — `ResearchGovernor/check`: a pure
  function, wired as its own `:govern` node. Hard invariants
  (unregistered project, missing dataset for analysis, a proposal whose
  `:effect` isn't `:propose`, finalized claims in draft proposals)
  always route to `:hold`. Escalation invariants (`:flag-anomalous-result`,
  `:draft-manuscript` with novel claims, or low advisor confidence) always route to
  `:request-approval` — an `interrupt-before` node that the graph
  checkpoints and only resumes on explicit human approval
  (`actor/approve!`), matching the README's robotics-premise statement
  that anomaly flags and novel manuscript claims always require
  human sign-off.
- `src/physics/actor.kotoba` — `build-graph`, `run-request!`,
  `approve!`: the `langgraph.graph/state-graph` wiring itself.

Proposal operations (advisor-only, all `:effect :propose`):
- `:analyze-dataset` — run/propose an analysis pipeline over recorded data.
- `:draft-manuscript` — prepare a paper draft section (never finalized).
- `:flag-anomalous-result` — surface a result deviating from expectation (escalates).
- `:request-instrument-time` — propose a telescope/instrument allocation.
- `:calibrate-instrument` — propose a calibration procedure run.

```bash
kbb -M:test
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
