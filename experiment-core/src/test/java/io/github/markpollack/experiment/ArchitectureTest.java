package io.github.markpollack.experiment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.github.markpollack.experiment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule core_must_not_depend_on_claude = noClasses().that()
		.resideInAPackage("io.github.markpollack.experiment..")
		.and()
		.resideOutsideOfPackage("io.github.markpollack.experiment.agent.claude..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("io.github.markpollack.experiment.agent.claude..")
		.allowEmptyShould(true)
		.because("experiment-core must not depend on experiment-claude");

	@ArchTest
	static final ArchRule data_models_must_not_depend_on_services = noClasses().that()
		.resideInAnyPackage("io.github.markpollack.experiment.result..", "io.github.markpollack.experiment.dataset..",
				"io.github.markpollack.experiment.agent..")
		.and()
		.areRecords()
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("io.github.markpollack.experiment.runner..", "io.github.markpollack.experiment.store..",
				"io.github.markpollack.experiment.comparison..")
		.allowEmptyShould(true)
		.because("data model records must not depend on service classes");

}
