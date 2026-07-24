package io.apvero.platform.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record EmbeddingExecutionRequest(
        UUID workspaceId,
        String exactRouteReference,
        String executionIdentity,
        List<EmbeddingInput> orderedInputs) {

    public EmbeddingExecutionRequest {
        Objects.requireNonNull(workspaceId, "APVERO_WORKSPACE_ID_REQUIRED");
        requireExactRouteReference(exactRouteReference);
        if (executionIdentity == null || executionIdentity.isBlank() || executionIdentity.length() > 200) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_EXECUTION_IDENTITY_INVALID");
        }
        orderedInputs = List.copyOf(Objects.requireNonNull(
                orderedInputs, "APVERO_EMBEDDING_INPUTS_REQUIRED"));
        if (orderedInputs.isEmpty() || orderedInputs.size() > EmbeddingRouteProfile.MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_BATCH_SIZE_INVALID");
        }
        Set<UUID> itemIds = new HashSet<>();
        for (EmbeddingInput input : orderedInputs) {
            if (!itemIds.add(input.itemId())) {
                throw new IllegalArgumentException("APVERO_EMBEDDING_INPUT_DUPLICATE");
            }
        }
        exactRouteReference = exactRouteReference.trim();
        executionIdentity = executionIdentity.trim();
    }

    static void requireExactRouteReference(String reference) {
        if (reference == null) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_REFERENCE_REQUIRED");
        }
        int separator = reference.lastIndexOf('@');
        if (separator < 1 || separator == reference.length() - 1) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_REFERENCE_INVALID");
        }
        try {
            if (Long.parseLong(reference.substring(separator + 1)) < 1) {
                throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_REFERENCE_INVALID");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_REFERENCE_INVALID", exception);
        }
    }
}
