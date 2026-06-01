# Brief: Abstain-Aware Consensus in Judge Aggregation

**Date**: 2026-06-01
**Origin**: weekly-kb-sync Stage 1 (consumer project)
**Affects**: agent-judge-core (VotingStrategy, Judgment, CascadedJury)

## Problem

`Judgment.abstain()` carries `BooleanScore(false)`. Voting strategies that aggregate `BooleanScore` (specifically `ConsensusStrategy`) treat ABSTAIN as failure. This collapses two semantically distinct outcomes:

```
false because the property was violated   (FAIL)
false because the judge was not applicable (ABSTAIN)
```

These are not the same. ABSTAIN means "my subject doesn't exist in this run" — not "the property failed."

## Where It Bites

Any eval system where not every judge applies to every case. Real examples from weekly-kb-sync:

```
OverrideRespectedJudge  → abstains when no HumanOverrides exist
DryRunNoMutationJudge   → abstains when the run is not dry-run
StatusFileFormatJudge   → abstains when no LIGHT status files generated
GitCleanlinessJudge     → abstains when no processable project directories
```

General examples that will recur across consumers:

```
SecurityRegressionJudge → abstains when no security-sensitive files changed
MigrationJudge          → abstains when the task is not a migration
ApiCompatibilityJudge   → abstains when no public API changed
```

## Current Behavior

**CascadedJury** is partially outcome-aware: `hasAnyFail()` checks `JudgmentStatus.FAIL`, `allPassed()` checks `JudgmentStatus.PASS`. So at the cascade tier level, ABSTAIN doesn't count as FAIL or PASS — it just causes escalation from `ACCEPT_ON_ALL_PASS` tiers.

**SimpleJury + ConsensusStrategy** is score-based, not outcome-aware. `ConsensusStrategy.toBoolean(j.score())` sees `BooleanScore(false)` from an ABSTAIN judgment and counts it as a failure. This means one ABSTAIN judge breaks unanimous consensus.

**MajorityVotingStrategy** already handles this correctly — it filters by `JudgmentStatus` (lines 93-101), excluding ABSTAIN from pass/fail counts. This is the workaround weekly-kb-sync uses for T1.

## Root Cause

`Judgment.abstain()` at line 116-121 of Judgment.java:

```java
public static Judgment abstain(String reasoning) {
    return builder()
        .score(new BooleanScore(false))  // ← ABSTAIN encoded as false
        .status(JudgmentStatus.ABSTAIN)
        .reasoning(reasoning)
        .build();
}
```

The `JudgmentStatus.ABSTAIN` is correct, but the `BooleanScore(false)` is misleading. Any aggregation path that reads the score instead of the status will misinterpret ABSTAIN as failure.

## Proposed Fix

### Option A: Fix ConsensusStrategy to be status-aware (minimal)

Make `ConsensusStrategy.aggregate()` filter out ABSTAIN judgments before computing consensus, same as `MajorityVotingStrategy` already does. This is backward-compatible and fixes the immediate issue.

```java
List<Judgment> applicable = judgments.stream()
    .filter(j -> j.status() != JudgmentStatus.ABSTAIN)
    .toList();

if (applicable.isEmpty()) {
    return Judgment.abstain("All judges abstained");
}
// consensus over applicable judgments only
```

### Option B: Make Judgment.abstain() carry no score (cleaner)

Change `Judgment.abstain()` to use a neutral score that voting strategies can safely ignore:

```java
public static Judgment abstain(String reasoning) {
    return builder()
        .score(null)  // or a NeutralScore sentinel
        .status(JudgmentStatus.ABSTAIN)
        .reasoning(reasoning)
        .build();
}
```

This requires updating all strategies to handle null/neutral scores but is semantically cleaner.

### Option C: Add per-tier AbstainPolicy (full)

Add configurable behavior for the all-abstain case:

```java
enum AbstainPolicy {
    NEUTRAL,              // abstain = not blocking
    FAIL_IF_ALL_ABSTAIN,  // T0 guardrails: all-abstain is suspicious
    PASS_IF_ALL_ABSTAIN   // behavioral tiers: not exercised = ok
}
```

T0 guardrails would use `FAIL_IF_ALL_ABSTAIN` (all judges abstaining in guardrails is a sign something is wrong). T1 behavioral would use `NEUTRAL`.

## Recommendation

**Option A now** (fix ConsensusStrategy, ~15 lines). **Option C later** if consumers need per-tier all-abstain control.

The key principle: **voting strategies should aggregate by JudgmentStatus, not just BooleanScore.** `MajorityVotingStrategy` already does this correctly — `ConsensusStrategy` should follow.

## Weekly-kb-sync Workaround

weekly-kb-sync currently works around this by using `MajorityVotingStrategy` for T1 (the tier with abstaining judges) and `ConsensusStrategy` for T0 (where ABSTAIN is unexpected). This is adequate for Stage 1 but fragile — a T0 judge that legitimately abstains would break.

## Tests Needed

1. ConsensusStrategy: PASS + ABSTAIN → passes (ABSTAIN excluded from consensus)
2. ConsensusStrategy: PASS + PASS + ABSTAIN → passes
3. ConsensusStrategy: FAIL + ABSTAIN → fails
4. ConsensusStrategy: all ABSTAIN → returns ABSTAIN verdict
5. CascadedJury: T0 passes, T1 has PASS + ABSTAIN → overall passes
6. CascadedJury: T0 passes, T1 all ABSTAIN → overall behavior depends on tier policy
