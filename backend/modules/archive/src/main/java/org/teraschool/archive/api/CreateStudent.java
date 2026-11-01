package org.teraschool.archive.api;

import java.util.List;
import java.util.UUID;

/**
 * 新建学员档案。
 *
 * <p>{@code requestId} 是幂等身份：同主体同请求号重试返回首次结果；{@code confirmDuplicate} 只在 已看到重复提示后由人工确认，不改变证件号唯一约束。
 */
public record CreateStudent(
    UUID requestId,
    UUID schoolId,
    UUID campusId,
    String name,
    String nameEnglish,
    String sex,
    String birthday,
    String certType,
    String certCode,
    String phone,
    String email,
    List<StudentContactInput> contacts,
    boolean confirmDuplicate) {
  public CreateStudent {
    contacts = contacts == null ? List.of() : List.copyOf(contacts);
  }
}
