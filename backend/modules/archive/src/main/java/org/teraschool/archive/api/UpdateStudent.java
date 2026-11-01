package org.teraschool.archive.api;

import java.util.List;
import java.util.UUID;

/** 修改学员档案；联系人按整套替换，必须带读取时的版本以防止覆盖他人修改。 */
public record UpdateStudent(
    Long expectedVersion,
    UUID campusId,
    String name,
    String nameEnglish,
    String sex,
    String birthday,
    String certType,
    String certCode,
    String phone,
    String email,
    String status,
    List<StudentContactInput> contacts,
    boolean confirmDuplicate) {
  public UpdateStudent {
    contacts = contacts == null ? List.of() : List.copyOf(contacts);
  }
}
