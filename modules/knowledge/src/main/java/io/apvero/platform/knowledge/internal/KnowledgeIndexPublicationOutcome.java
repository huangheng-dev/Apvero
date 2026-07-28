package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;

record KnowledgeIndexPublicationOutcome(
        BuildRow build,
        IndexRow index,
        VersionRow version) {}
