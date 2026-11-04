package org.teraschool.archive.api;

import java.util.UUID;

/**
 * 学员列表行。
 *
 * <p>敏感字段在服务端按当前权限脱敏后返回，调用方无法据此还原完整值；{@code sensitiveFieldsMasked} 只用于提示界面“当前显示的是脱敏值”。
 */
public record StudentSummary(
    UUID id,
    String studentNo,
    String name,
    String sex,
    String phone,
    UUID schoolId,
    String schoolName,
    UUID campusId,
    UUID ownerAccountId,
    String status,
    long version,
    int contactCount,
    boolean sensitiveFieldsMasked) {}
