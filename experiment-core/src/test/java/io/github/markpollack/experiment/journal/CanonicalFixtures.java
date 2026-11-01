package io.github.markpollack.experiment.journal;

import java.util.*;
import io.github.markpollack.experiment.runtime.AgentRecords;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

final class CanonicalFixtures {

	static Result result(String run, String providerSession) {
		var id = Identity.of(run, "item", "variant", 0);
		var a = new Attempt(id, "inv-" + run, "wf-" + run, "logical", "physical", 1);
		var inv = new Invocation(a, "SUCCEEDED", "UNKNOWN", List.of(), "MISSING", List.of(), List.of(), "NOT_ATTEMPTED",
				List.of(providerSession), List.of(Measurement.unknown("cost", "USD", "No capture", "inv-" + run)),
				"answer", "", 1000, 1);
		var row = new Row(id, List.of(inv), "NOT_STARTED", new Domain(List.of(), List.of()), List.of(), "NOT_ASSESSED",
				"", "RETAINED", List.of());
		return new Result(AgentRecords.CURRENT_SCHEMA_VERSION, "AGENT_CANONICAL", run, "experiment", 100, List.of(row),
				List.of());
	}

}
