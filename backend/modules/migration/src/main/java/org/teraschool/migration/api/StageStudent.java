package org.teraschool.migration.api;

/** 暂存一条旧库学员：学员号是关联键，家长联系人可空。 */
public record StageStudent(
    String legacyId,
    String studentNo,
    String name,
    String sex,
    String birthday,
    String certCode,
    String phone,
    String contactName,
    String contactPhone) {}
