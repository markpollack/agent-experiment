package io.github.markpollack.experiment.scoring.claude;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CriteriaExtractorTest {

	@Test
	void extractsFromCheckboxFormat() {
		String markdown = "- [ ] VERIFY: ./mvnw clean compile";

		List<String> criteria = CriteriaExtractor.extract(markdown);

		assertThat(criteria).containsExactly("./mvnw clean compile");
	}

	@Test
	void extractsFromBareFormat() {
		String markdown = "VERIFY: ./mvnw clean verify";

		List<String> criteria = CriteriaExtractor.extract(markdown);

		assertThat(criteria).containsExactly("./mvnw clean verify");
	}

	@Test
	void extractsFromCheckedCheckbox() {
		String markdown = "- [x] VERIFY: ./mvnw test passes";

		List<String> criteria = CriteriaExtractor.extract(markdown);

		assertThat(criteria).containsExactly("./mvnw test passes");
	}

	@Test
	void extractsMultipleCriteriaInOrder() {
		String markdown = """
				- [ ] VERIFY: ./mvnw clean compile
				- [ ] VERIFY: ./mvnw test
				- [ ] VERIFY: no javax.* imports remain
				""";

		List<String> criteria = CriteriaExtractor.extract(markdown);

		assertThat(criteria).containsExactly("./mvnw clean compile", "./mvnw test", "no javax.* imports remain");
	}

	@Test
	void deduplicatesIdenticalCriteria() {
		String markdown = """
				- [ ] VERIFY: ./mvnw clean compile
				- [ ] VERIFY: ./mvnw test
				- [ ] VERIFY: ./mvnw clean compile
				""";

		List<String> criteria = CriteriaExtractor.extract(markdown);

		assertThat(criteria).containsExactly("./mvnw clean compile", "./mvnw test");
	}

	@Test
	void emptyStringReturnsEmptyList() {
		assertThat(CriteriaExtractor.extract("")).isEmpty();
	}

	@Test
	void nullReturnsEmptyList() {
		assertThat(CriteriaExtractor.extract(null)).isEmpty();
	}

	@Test
	void noVerifyLinesReturnsEmptyList() {
		String markdown = """
				## Step 1
				- [ ] Create the file
				- [ ] Update the config
				""";

		assertThat(CriteriaExtractor.extract(markdown)).isEmpty();
	}

	@Test
	void extractsFromMixedMarkdownContent() {
		String markdown = """
				# Roadmap

				## Stage 1: POM Changes

				### Step 1.1: Update parent
				- [ ] Change parent version to 3.0.0
				- [ ] VERIFY: ./mvnw clean compile

				## Stage 2: Code Migration

				### Step 2.1: Namespace migration
				- [ ] Run javax-to-jakarta tool
				Some explanatory text here.
				- [x] VERIFY: no javax.* imports remain

				### Step 2.2: Annotation updates
				- [ ] Update deprecated annotations
				VERIFY: ./mvnw clean verify
				""";

		List<String> criteria = CriteriaExtractor.extract(markdown);

		assertThat(criteria).containsExactly("./mvnw clean compile", "no javax.* imports remain",
				"./mvnw clean verify");
	}

	@Test
	void handlesUppercaseXInCheckbox() {
		String markdown = "- [X] VERIFY: build succeeds";

		List<String> criteria = CriteriaExtractor.extract(markdown);

		assertThat(criteria).containsExactly("build succeeds");
	}

}
