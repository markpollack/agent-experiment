package io.github.markpollack.experiment.scoring.claude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Extracts VERIFY criteria from a Forge-style roadmap markdown document.
 *
 * <p>
 * Matches lines containing {@code VERIFY:} with optional checkbox prefix ({@code - [ ]}
 * or {@code - [x]}). Strips the prefix and whitespace, deduplicates, and returns the
 * criterion text in order of appearance.
 */
final class CriteriaExtractor {

	private static final Pattern VERIFY_PATTERN = Pattern.compile("^\\s*(?:-\\s*\\[[ xX]\\]\\s*)?VERIFY:\\s*(.+)$",
			Pattern.MULTILINE);

	private CriteriaExtractor() {
	}

	/**
	 * Extract VERIFY criteria from roadmap markdown.
	 * @param roadmapMarkdown the roadmap content (nullable)
	 * @return deduplicated list of criterion texts in order of appearance; empty if input
	 * is null, empty, or contains no VERIFY lines
	 */
	static List<String> extract(@Nullable String roadmapMarkdown) {
		if (roadmapMarkdown == null || roadmapMarkdown.isEmpty()) {
			return List.of();
		}
		LinkedHashSet<String> criteria = new LinkedHashSet<>();
		Matcher matcher = VERIFY_PATTERN.matcher(roadmapMarkdown);
		while (matcher.find()) {
			String criterion = matcher.group(1).trim();
			if (!criterion.isEmpty()) {
				criteria.add(criterion);
			}
		}
		return new ArrayList<>(criteria);
	}

}
