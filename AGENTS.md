# Agent Experiment contributor instructions

The paired private steward at `/home/mark/projects/agent-experiment-steward` owns planning,
current work orders and acceptance. Read its `AGENTS.md`, `BINDING.md` and active
`plans/VISION.md`, `plans/DESIGN.md`, `plans/ROADMAP.md` before changing this project.
Keep private planning and control state out of public source files.

Use this repository for implementation, tests, builds and public documentation.
Read `RECORDING-PRINCIPLE.md` before changing stored observations.
The root Maven wrapper and POMs define supported modules, dependencies and build checks.

## AI Development Laws

> Reality first. Evidence sufficient to trust it. Infrastructure only when necessary.

1. **Maintain Contact With Reality.** Prefer the shortest credible path to observing real application behavior and completing the assigned engineering objective. Use established development environments, dependencies, tools and workflows unless the objective specifically requires isolation. Treat reproducibility and evidence integrity as constraints on the assigned work. Distinguish apparatus failure from failure of the engineering objective. Do not spend more effort perfecting the apparatus than learning from the experiment.
2. **Objective Is Sovereign.** The assigned objective, acceptance criteria and scope govern the work. Do not substitute infrastructure improvements or unrelated engineering for the requested outcome.
3. **Discovery Is Not Authorization.** Discovering an unrelated problem does not authorize fixing it. Report it or request authorization when necessary.
4. **Do Not Confuse Measurement With the Objective.** Measurement, recording, qualification and evaluation support real engineering work. Their failures must not automatically invalidate independently established results or trigger unrelated infrastructure projects.
5. **Stop When Done.** Once the authorized objective and acceptance criteria are satisfied, report the result and stop. Do not add speculative hardening or new work.

The shortest credible path must satisfy the actual acceptance contract, required tests, honest evidence, pinned experimental inputs and safety requirements. Experimental isolation and independent evaluation apply where the owning contract requires them.

For missing Maven release artifacts, first investigate the established dependency installation procedure. A private Maven cache needs a concrete requirement from the assigned objective.

## When the preferred path hits a separate defect

If the preferred path in a work order runs into a defect outside its scope, aim for the smallest change that can ship
with the full gate green:

1. Where a quick check can establish it, check whether the defect also occurs on the baseline.
2. If a smaller change still meets the work order's acceptance criteria and passes the full gate, continue within the
   existing authorization, and record the separate defect with its owning repository.
3. Bring the Owner a decision instead if proceeding would change the acceptance criteria, weaken the gate, or expand
   the work into another repository.
