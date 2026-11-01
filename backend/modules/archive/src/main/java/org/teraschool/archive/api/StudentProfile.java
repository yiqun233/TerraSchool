package org.teraschool.archive.api;

import java.util.List;
import java.util.UUID;

/** 学员详情；联系人与学员分开建模，一个学员可有多位家长/联系人。 */
public record StudentProfile(
    UUID id,
    String studentNo,
    String name,
    String nameEnglish,
    String sex,
    String birthday,
    String certType,
    String certCode,
    String phone,
    String email,
    UUID schoolId,
    String schoolName,
    UUID campusId,
    String status,
    long version,
    List<StudentContact> contacts,
    boolean sensitiveFieldsMasked) {
  public StudentProfile {
    contacts = List.copyOf(contacts);
  }
}
