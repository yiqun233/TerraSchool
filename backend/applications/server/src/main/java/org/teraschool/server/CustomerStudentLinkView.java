package org.teraschool.server;

import java.util.UUID;

record CustomerStudentLinkView(
    UUID customerId, UUID studentId, UUID schoolId, UUID ownerId, long customerVersion) {}
