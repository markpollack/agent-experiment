# Schema 4 fixtures

These files were written by the schema 4 `FileSystemResultStore` at commit `91817ec`, before schema 5 added
direct execution. They are kept byte for byte so that tests can show schema 4 records still read with their
original meaning.

- `workflow-succeeded.json`: the `ThreeStageConsumerExampleTest` result, a SUCCEEDED Workflow row with its
  proof and a Judge V6 verdict.
- `workflow-failed.json`: a FAILED, quarantined Workflow row with its proof and a TIMEOUT invocation.
- `not-started-with-invocation.json`: a NOT_STARTED row that carries an invocation with Workflow
  coordinates but no proof. Schema 4 allowed this; schema 5 does not write it.
