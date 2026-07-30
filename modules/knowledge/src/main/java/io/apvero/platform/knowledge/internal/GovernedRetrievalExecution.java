package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.ExactRetrievalCandidate;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.util.List;

record GovernedRetrievalExecution(
        VersionRow indexVersion,
        RetrievalPolicyRow retrievalPolicy,
        String queryDigest,
        List<ExactRetrievalCandidate> rankedCandidates,
        long providerLatencyMillis) {

    GovernedRetrievalExecution {
        rankedCandidates = List.copyOf(rankedCandidates);
    }
}
