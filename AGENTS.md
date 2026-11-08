# Agent Experiment contributor instructions

The paired private steward at `/home/mark/projects/agent-experiment-steward` owns planning,
current work orders and acceptance. Read its `AGENTS.md`, `BINDING.md` and active
`plans/VISION.md`, `plans/DESIGN.md`, `plans/ROADMAP.md` before changing this project.
Keep private planning and control state out of public source files.

Use this repository for implementation, tests, builds and public documentation.
Read `RECORDING-PRINCIPLE.md` before changing stored observations.
The root Maven wrapper and POMs define supported modules, dependencies and build checks.

## When the preferred path hits a separate defect

If the preferred path in a work order runs into a defect outside its scope, aim for the smallest change that can ship
with the full gate green:

1. Where a quick check can establish it, check whether the defect also occurs on the baseline.
2. If a smaller change still meets the work order's acceptance criteria and passes the full gate, continue within the
   existing authorization, and record the separate defect with its owning repository.
3. Bring the Owner a decision instead if proceeding would change the acceptance criteria, weaken the gate, or expand
   the work into another repository.
