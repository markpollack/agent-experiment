# Recording principle — aggregate at read time, never at write time

> **An aggregate is cheap to recompute from parts. Parts cannot be recovered from an aggregate.**

A recording principle for anything this framework persists. Derived from seven defects found over
two days of dogfooding, **four of which are experiment-layer rather than judge-layer** — which is
why it belongs here and not only in the judge manual.

---

## The seven

In every case the parts were **computed correctly**, a summary was stored instead, and the parts
were then unrecoverable without re-running the agent.

| Layer | What was stored | What it destroyed |
|---|---|---|
| ⚙️ **experiment** | cost **summed** across steps | **per-step attribution** — and with it "use a cheaper model for the cheap step", the largest untested cost lever |
| ⚙️ **experiment** | `totalTokens` | **cache composition** — ~98% of the tokens, and any independent check on cost |
| ⚙️ **experiment** | the point-in-time `usage` snapshot | the **cost-bearing aggregate**, which the source's own javadoc says reconciles to `totalCostUsd` |
| ⚙️ **experiment** | `placement k/matched` | comparability — dividing by *what was found* means **a run that finds less scores higher** |
| judge | rubric **mean** over 7 criteria | **which criterion binds** — the diagnosis, and the thing you act on |
| judge | `recall 4/5` | **which findings** — a stable count can hide unstable identities |
| judge | verdict from the mean | the per-criterion `Check`s **the judge had already recorded** |

## ⚠️ Why these are found late, if at all

An aggregate is not *wrong*. `0.95` is a true statement about seven criteria; `totalTokens` is a
true count. **Nothing throws, nothing logs, so nothing prompts anyone to look.** A summary that
silently drops the only actionable part of a measurement passes every test written against it,
because the tests were written against the summary.

⭐ The cost is asymmetric and that is the whole argument. Recomputing an aggregate from stored parts
is arithmetic. Recovering parts from a stored aggregate requires **re-running the agent** — which is
the expensive half of every experiment this framework runs.

## What this asks of the framework

1. **Persist at the finest grain you computed.** If a per-step, per-criterion, or per-item value
   exists on the way to a total, it is evidence. `metrics` is `Map<String, Object>` — an extensible
   slot that costs nothing to use.
2. **Aggregate in the reader, not the writer.** Totals belong in reports and summaries, computed on
   demand from what was stored.
3. **Never store a value the upstream source marks as unsuitable.** ⚠️ We persisted a token view
   whose own javadoc reads *"Deprecated as a cost basis: it is point-in-time and under-counts long
   runs"*, while the aggregate that reconciles to cost was computed and discarded.
4. **A denominator is part of the measurement.** `k/matched` and `k/total` are different metrics;
   only one of them is comparable across runs.
5. ⭐ **Record the binding item** — which step, which criterion, which entry decided the outcome.
   That is the diagnosis. An aggregate cannot produce it.

## Relationship to the replay argument

This is *store the artifact, not the verdict* one level down: **store the parts, not the summary.**

```
storing ARTIFACTS  makes the JUDGING   replay-evaluable   → change the judge, re-score for free
storing PARTS      makes the ANALYSIS  replay-evaluable   → change the question, re-aggregate for free
```

Both convert an expensive re-run into free arithmetic, and both fail the same way when skipped: the
number you have is true, and the number you need is gone.

## See also

- `~/.claude/skills/writing-agent-judges/SKILL.md` §7 — the same rule for judge authors
- `~/projects/agent-judge/writing-judges.md` — the judge construction manual
