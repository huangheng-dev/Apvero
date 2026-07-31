package io.apvero.platform.runtime.internal;

import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.runtime.GroundingContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class GroundingContextBudget {
    static final int MAXIMUM_HITS = 128;
    static final int MAXIMUM_UTF8_BYTES = 100_000;

    private final ObjectMapper json;
    private final ArrayNode context;

    GroundingContextBudget(ObjectMapper json) {
        this.json = json;
        this.context = json.createArrayNode();
    }

    KnowledgeRetrievalResult accept(KnowledgeRetrievalResult retrieved) {
        List<KnowledgeRetrievalHit> accepted = new ArrayList<>();
        for (KnowledgeRetrievalHit candidate : retrieved.hits()) {
            if (context.size() >= MAXIMUM_HITS
                    || candidate.content() == null
                    || candidate.content().isBlank()) {
                continue;
            }
            String marker = "[K" + (context.size() + 1) + "]";
            ObjectNode evidence = evidence(marker, candidate);
            context.add(evidence);
            if (utf8Size(context) > MAXIMUM_UTF8_BYTES) {
                context.remove(context.size() - 1);
                break;
            }
            accepted.add(withRank(candidate, accepted.size() + 1));
        }
        return new KnowledgeRetrievalResult(
                accepted.isEmpty()
                        ? KnowledgeRetrievalResult.Status.NO_EVIDENCE
                        : KnowledgeRetrievalResult.Status.MATCHES,
                retrieved.indexVersionId(),
                retrieved.retrievalPolicyVersionId(),
                retrieved.queryDigest(),
                accepted,
                retrieved.latencyMs());
    }

    boolean isEmpty() {
        return context.isEmpty();
    }

    GroundingContext build() {
        if (context.isEmpty()) {
            throw new IllegalStateException("APVERO_RUNTIME_GROUNDING_CONTEXT_EMPTY");
        }
        int bytes = utf8Size(context);
        return new GroundingContext(
                context,
                context.size(),
                Math.max(1, (bytes + 3L) / 4L));
    }

    private ObjectNode evidence(String marker, KnowledgeRetrievalHit hit) {
        ObjectNode node = json.createObjectNode();
        node.put("marker", marker);
        node.put("content", hit.content());
        node.put("contentDigest", hit.contentDigest());
        if (hit.sourceTitle() != null) node.put("sourceTitle", hit.sourceTitle());
        node.put("sourceType", hit.sourceType().name());
        if (hit.page() != null) node.put("page", hit.page());
        if (hit.heading() != null) node.put("heading", hit.heading());
        if (hit.paragraph() != null) node.put("paragraph", hit.paragraph());
        if (hit.lineStart() != null) node.put("lineStart", hit.lineStart());
        if (hit.lineEnd() != null) node.put("lineEnd", hit.lineEnd());
        return node;
    }

    private static KnowledgeRetrievalHit withRank(
            KnowledgeRetrievalHit hit, int rank) {
        return new KnowledgeRetrievalHit(
                rank,
                hit.score(),
                hit.sourceId(),
                hit.sourceRevisionId(),
                hit.documentId(),
                hit.chunkId(),
                hit.contentDigest(),
                hit.content(),
                hit.sourceTitle(),
                hit.sourceType(),
                hit.page(),
                hit.heading(),
                hit.paragraph(),
                hit.lineStart(),
                hit.lineEnd());
    }

    private static int utf8Size(ArrayNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
