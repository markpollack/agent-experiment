# Step 1.2: AgentExperiment Rename

> Completed: 2026-05-18

## What Changed

1. **Renamed `ExperimentRunner` → `AgentExperiment`** — class, constructors, logger
2. **Renamed test file**: `ExperimentRunnerTest` → `AgentExperimentTest`
3. **Updated javadoc references** in `ActiveSession` and `ClaudeSdkInvoker`
4. **Updated CLAUDE.md** to reflect new name

## Files Changed

- `experiment-core/.../runner/AgentExperiment.java` (was ExperimentRunner.java)
- `experiment-core/.../runner/AgentExperimentTest.java` (was ExperimentRunnerTest.java)
- `experiment-core/.../store/ActiveSession.java` (javadoc)
- `experiment-claude/.../agent/claude/ClaudeSdkInvoker.java` (javadoc)

## Notes

- `ExperimentConfig` kept as-is — it's already generic enough for any experiment type
- Test dataset `dataset.json` has historical reference to `ExperimentRunner` in description string — left as-is
- No behavior changes — purely a rename for clarity
