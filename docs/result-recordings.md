# Current result recordings

New recordings use Experiment envelope version 2 (E2). Read compatibility is limited to the
unversioned Experiment 0.5/Judge 0.13 fixture and the committed historical conformance fixtures
covering Judge 0.13–0.17 shapes (H0). Unknown Experiment envelope versions are errors. This is
not a promise to understand arbitrary future formats or to read without the original libraries.

A new writer requires an explicit source revision in `experiment.writer.revision` and
`experiment.judge.revision`. Its runtime identity records SHA-256 of the loaded artifacts,
including optional Judge adapters present on the classpath. A class-directory development build
is labeled `classes:<sha256>`. The source declarations are caller assertions; archive signatures
and custody verification are outside this API. The reader requires the same Judge interpreter
artifact and revision before trusting an E2 interpretation.

The current source requires the Judge portable-v2 API. During unpublished snapshot development,
override `agent-judge.version` with the matching candidate version and use an isolated Maven
repository built from that exact producer revision. Supply both revision properties to test JVMs.
Do not use a mutable shared snapshot coordinate as evidence of which producer was tested.

## Producer documents and interpretations

`RecordedJudgment` and `RecordedVerdict` retain complete standalone producer JSON text and its
UTF-8 SHA-256. Live factories serialize the producer's ordinary Jackson representation; fixture
factories retain supplied text. Duplicate JSON members and corrupt hashes fail explicitly.
Compatibility getters are derived views and are never the serialized source of truth.

Consumer instrument associations use pointers beside the producer text. The pure producer document
can go directly to `Verdicts.interpret`; adding consumer fields inside it can invalidate its strict
contract. Each current verdict has a SUBJECT or CANDIDATE role and a bound interpretation snapshot.
Reads check source, producer revision, interpreter artifact and the newly computed interpretation.
A disagreement fails the read instead of refreshing the stored file.

## Judge experiments

Supply `candidateId` and `captureMode` explicitly. The candidate ID identifies the configuration
under comparison. Modes FAKE, FIXTURE, RETAINED and LIVE describe the actual source of the
observation; selecting a mode does not itself authorize a provider call.

The ordered declaration contains every case and replicate. Duplicate case IDs are refused;
repetition uses `replicateCount`. A stable, length-prefixed UTF-8 row ID includes case ID, candidate
ID and replicate index. Together with run ID it identifies a recorded observation.

The actual returned Judgment is captured before truth lookup or scoring. Its producer
`Verdict.single("candidate", judgment)` supplies candidate operational outcomes. A separate
comparison records supplied truth, measure ID, scorer revision, source binding and scored state.
A negative candidate can match negative truth while its operational `passed` flag is false.

`proposition-match:v1` compares eligible native boolean propositions. A status-only convenience
such as `Judgment.pass(...)` does not manufacture a native proposition. The legacy status adapter
is `status-agreement:v1`; legacy category and normalized numerical adapters remain explicitly
named. Custom scorers require a measure ID, revision and truth kind.

`agreementRate` is matches / scored comparisons. `meanScore` is separately named; both are null
when no comparison was scored. Unscored rows have reasons and do not become disagreements.
Operational pass/nonpass counts retain the candidate-outcome rule. Generic and session summaries
carry role, rule, measure identity and comparison coverage beside those operational counts.

Ordinary cross-run comparison aligns case and replicate across the declared candidate contrast.
The caller's optional `caseInputIdentity` must cover the actual constructed requirement, evidence
and context, excluding the candidate configuration. Matching local case IDs alone prove nothing.
Changed known identities are INCOMPATIBLE; absent or asymmetrically known identities are UNKNOWN.
Both prevent ordinary deltas. Missing counterpart rows remain NEW or REMOVED.

## Failures and measurements

Input, invocation, recording, observation, truth and scoring failures are separate portable facts.
Ordinary failures retain the declared population and permit later rows to execute. A returned
ERROR stays RETURNED; thrown and null calls never receive manufactured ERROR judgments. `success`
means a non-null return was captured, independently of its outcome or comparison. Cancellation
retains captured returns, marks the interrupted row and leaves later declarations NOT_ATTEMPTED.
The interrupt flag is restored. Incomplete runs expose partial diagnostics without final rates.

Timing describes the observed logical call; optional transport timing and attempt counts are
separate. Retained observations keep original timestamps when supplied. Missing tokens, cost and
duration remain null through item, run and summary accessors. A measured zero needs an observation
and source. Complete totals require compatible known coverage and no overflow or identity conflict;
known subtotals and coverage remain available when complete totals are absent.

The current Jev adapter extractor reads validated root usage once. It does not add repeated child
metadata. Caller-owned artifact capture retains references; optional trace lookup can populate
physical attempts, request ID and transport elapsed time after verifying the referenced bytes.
Missing trace lookup does not erase root usage or imply a known attempt count. Cost requires an
observed amount/currency/source or a named price rule with complete applicable usage.

## Persistence and historical reads

A Judge run makes one final save. Result IDs and session variant files cannot overwrite existing
recordings. Save/index errors fail visibly; process-crash recovery and index reconstruction are
not guaranteed. A hard kill before final save may lose that run.

H0 files are read-only in this store. Re-evaluation creates a fresh ID and records original costs
as provenance; it does not report them as fresh inference cost. Skipped historical readings keep
their original JSON as LEGACY_UNVERIFIED and supply no new comparison scores. No read implicitly
rewrites or upgrades an archive, and the interpretation re-export utility is not an E2 read path.

The recording tests exercise fixed producer vectors, independent expected facts, live object
factories, source ingestion, mapper/store discovery, summary/session paths and named historical
fixtures. Jev transport tests use a local fixed-response HTTP server only.
