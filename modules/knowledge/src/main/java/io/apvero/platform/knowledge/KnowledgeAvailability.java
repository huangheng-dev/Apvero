package io.apvero.platform.knowledge;

/** Fail-closed gate for every Knowledge command, query, and background claim. */
public interface KnowledgeAvailability {

    boolean isEnabled();

    void requireEnabled();
}
