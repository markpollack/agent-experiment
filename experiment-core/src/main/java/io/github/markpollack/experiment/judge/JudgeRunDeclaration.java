package io.github.markpollack.experiment.judge;

import java.util.List;
import io.github.markpollack.experiment.recording.RowIdentity;

/** Frozen intended population and named current reporting rules. */
public record JudgeRunDeclaration(String candidateId, String measureId, String scorerRevision, String truthKind,
		int replicateCount, String comparisonRule, String countRule, List<RowIdentity> rows) {
	public JudgeRunDeclaration {
		for (String value : new String[] { candidateId, measureId, scorerRevision, truthKind })
			if (value == null || value.isBlank())
				throw new IllegalArgumentException("Run declaration identity required");
		if (replicateCount < 1 || !"scored-comparisons:v1".equals(comparisonRule)
				|| !"candidate-outcome:v1".equals(countRule))
			throw new IllegalArgumentException("Unsupported Judge run rules");
		rows = List.copyOf(rows);
		if (rows.stream().map(RowIdentity::rowId).distinct().count() != rows.size())
			throw new IllegalArgumentException("Duplicate declared row");
	}
}
