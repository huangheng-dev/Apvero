package io.apvero.platform.runtime.internal;

import java.util.Set;
import tools.jackson.databind.JsonNode;

record ValidatedGroundedAnswer(JsonNode output, Set<String> markers) {
    ValidatedGroundedAnswer {
        output = output.deepCopy();
        markers = Set.copyOf(markers);
    }
}
