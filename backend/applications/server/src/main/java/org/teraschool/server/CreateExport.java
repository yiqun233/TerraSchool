package org.teraschool.server;

import java.util.UUID;

record CreateExport(String exportType, UUID schoolId, UUID requestId, String from, String to) {}
