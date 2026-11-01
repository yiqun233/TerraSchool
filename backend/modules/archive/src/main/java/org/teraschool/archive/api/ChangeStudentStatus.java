package org.teraschool.archive.api;

/**
 * 学员档案状态变更。
 *
 * <p>单独提供而不是复用整套修改：没有敏感字段权限的账号看不到完整证件号与电话，整套提交会把脱敏值写回成为 权威数据，因此状态变更只提交版本与目标状态。
 */
public record ChangeStudentStatus(Long expectedVersion, String status) {}
