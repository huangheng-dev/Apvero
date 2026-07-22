package io.apvero.platform.knowledge;

import java.net.URI;

public record CreateWebKnowledgeSourceCommand(String name, URI url) {}
