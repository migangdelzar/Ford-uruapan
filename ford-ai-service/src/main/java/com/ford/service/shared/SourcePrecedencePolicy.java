package com.ford.service.shared;

import java.time.Instant;
import java.util.List;

public interface SourcePrecedencePolicy {

    SourceSelection choose(List<SourceCandidate> candidates, Instant now);
}
