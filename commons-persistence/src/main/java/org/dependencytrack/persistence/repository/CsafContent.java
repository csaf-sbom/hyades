package org.dependencytrack.persistence.repository;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record CsafContent(long id, String content) {}
