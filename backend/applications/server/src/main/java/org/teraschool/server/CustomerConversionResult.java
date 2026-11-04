package org.teraschool.server;

import org.teraschool.archive.api.StudentCreation;

record CustomerConversionResult(CustomerStudentLinkView conversion, StudentCreation creation) {}
