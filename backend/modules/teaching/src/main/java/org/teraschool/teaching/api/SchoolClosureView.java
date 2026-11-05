package org.teraschool.teaching.api;

import java.util.UUID;

public record SchoolClosureView(
    UUID id, UUID schoolId, String closedOn, String reason, boolean active, long version) {}
