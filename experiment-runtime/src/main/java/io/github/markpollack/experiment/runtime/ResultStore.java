package io.github.markpollack.experiment.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import io.github.markpollack.experiment.runtime.AgentRecords.Result;

public interface ResultStore {

	void save(Result result) throws IOException;

	Optional<Result> load(String experimentId) throws IOException;

	List<Result> list() throws IOException;

	Optional<Result> mostRecent() throws IOException;

}
