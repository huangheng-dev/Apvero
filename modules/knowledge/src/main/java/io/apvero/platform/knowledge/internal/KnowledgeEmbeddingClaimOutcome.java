package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;

record KnowledgeEmbeddingClaimOutcome(
        BuildRow build,
        RecoveryAction action,
        boolean providerInvoked) {}
