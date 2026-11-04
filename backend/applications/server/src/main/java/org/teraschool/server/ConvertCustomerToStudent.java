package org.teraschool.server;

import java.util.UUID;

/** 客户转学员。客户联系人关系必须由操作人明确选择。 */
record ConvertCustomerToStudent(
    UUID requestId,
    Long expectedCustomerVersion,
    UUID campusId,
    String studentName,
    String studentNameEnglish,
    String sex,
    String birthday,
    String certType,
    String certCode,
    String studentPhone,
    String email,
    String customerContactRelation,
    boolean confirmDuplicate) {}
