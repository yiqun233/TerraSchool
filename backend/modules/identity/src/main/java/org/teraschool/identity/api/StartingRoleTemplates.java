package org.teraschool.identity.api;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 预设的起始角色模板：角色模板页“从模板生成”的唯一服务端来源。
 *
 * <p>模板只提供初始动作集合，生成后就是普通角色：不绑定模板、不自动分配给账号，也不随后续 模板调整同步。列表顺序即页面顺序；代码同时作为生成时角色代码的默认值，租户内仍须唯一。
 *
 * <p>动作集合逐项列举，不从目录自动派生：目录新增动作是否进入某个岗位必须逐次评审， 隐式派生会让“以后新增的功能自动给某个岗位”变成未评审的扩权。
 */
public final class StartingRoleTemplates {
  private StartingRoleTemplates() {}

  /** 一个预设模板；action 全部为 ALLOW，生成后可按普通角色调整。 */
  public record Template(String code, String name, String purpose, List<String> actions) {
    public Template {
      actions = List.copyOf(actions);
    }
  }

  public static final List<Template> TEMPLATES =
      List.of(
          new Template(
              "START_ORG_ADMIN",
              "机构管理员",
              "建校后的日常运营全权；不含授权管理，避免生成即获得授权能力",
              List.of(
                  "ORGANIZATION_READ",
                  "ORGANIZATION_MAINTAIN",
                  "TENANT_BRANDING_WRITE",
                  "EMPLOYEE_READ",
                  "EMPLOYEE_MAINTAIN",
                  "TEACHER_READ",
                  "TEACHER_MAINTAIN",
                  "ROOM_READ",
                  "ROOM_MAINTAIN",
                  "PERSONNEL_READ",
                  "PERSONNEL_MAINTAIN",
                  "STUDENT_READ",
                  "STUDENT_MAINTAIN",
                  "STUDENT_SENSITIVE_READ",
                  "STUDENT_ASSIGN",
                  "STUDENT_EXPORT",
                  "PRODUCT_READ",
                  "PRODUCT_MAINTAIN",
                  "PRICE_PUBLISH",
                  "CHANNEL_READ",
                  "CHANNEL_MAINTAIN",
                  "CUSTOMER_READ",
                  "CUSTOMER_MAINTAIN",
                  "CUSTOMER_PHONE_READ",
                  "CUSTOMER_POOL_READ",
                  "CUSTOMER_POOL_MANAGE",
                  "CUSTOMER_ASSIGN",
                  "ENROLLMENT_READ",
                  "ENROLLMENT_MAINTAIN",
                  "ENROLLMENT_APPROVE",
                  "ENROLLMENT_DISCOUNT",
                  "FUNDS_READ",
                  "FUNDS_RECEIPT",
                  "FUNDS_ALLOCATE",
                  "REFUND_READ",
                  "REFUND_APPLY",
                  "REFUND_PAY",
                  "TEACHING_READ",
                  "TEACHING_MAINTAIN",
                  "ATTENDANCE_RECORD",
                  "LESSON_CLOSE",
                  "APPROVAL_READ",
                  "APPROVAL_MANAGE",
                  "APPROVAL_DECIDE",
                  "PERIOD_READ",
                  "PERIOD_LOCK",
                  "PERIOD_UNLOCK",
                  "MIGRATION_MANAGE")),
          new Template(
              "START_FRONT_DESK",
              "前台",
              "接待、建档、报名与收款",
              List.of(
                  "ORGANIZATION_READ",
                  "STUDENT_READ",
                  "STUDENT_MAINTAIN",
                  "CUSTOMER_READ",
                  "CUSTOMER_MAINTAIN",
                  "PRODUCT_READ",
                  "ENROLLMENT_READ",
                  "ENROLLMENT_MAINTAIN",
                  "FUNDS_READ",
                  "FUNDS_RECEIPT")),
          new Template(
              "START_FINANCE",
              "财务",
              "收款、分配、退款、锁月与对账；不含无校验点的退款查看",
              List.of(
                  "ORGANIZATION_READ",
                  "STUDENT_READ",
                  "ENROLLMENT_READ",
                  "FUNDS_READ",
                  "FUNDS_RECEIPT",
                  "FUNDS_ALLOCATE",
                  "REFUND_APPLY",
                  "REFUND_PAY",
                  "APPROVAL_READ",
                  "APPROVAL_DECIDE",
                  "PERIOD_READ",
                  "PERIOD_LOCK",
                  "PERIOD_UNLOCK")),
          new Template(
              "START_MARKETING",
              "市场",
              "渠道、客户线索、公海与转派",
              List.of(
                  "ORGANIZATION_READ",
                  "PRODUCT_READ",
                  "CHANNEL_READ",
                  "CHANNEL_MAINTAIN",
                  "CUSTOMER_READ",
                  "CUSTOMER_MAINTAIN",
                  "CUSTOMER_PHONE_READ",
                  "CUSTOMER_POOL_READ",
                  "CUSTOMER_POOL_MANAGE",
                  "CUSTOMER_ASSIGN")),
          new Template(
              "START_CONSULTANT",
              "课程顾问",
              "本人客户跟进、报名与收款",
              List.of(
                  "ORGANIZATION_READ",
                  "STUDENT_READ",
                  "PRODUCT_READ",
                  "CHANNEL_READ",
                  "CUSTOMER_READ",
                  "CUSTOMER_MAINTAIN",
                  "CUSTOMER_PHONE_READ",
                  "ENROLLMENT_READ",
                  "ENROLLMENT_MAINTAIN",
                  "FUNDS_READ",
                  "FUNDS_RECEIPT")),
          new Template(
              "START_CONSULTANT_LEAD",
              "课程顾问主管",
              "顾问职责加公海、转派、审核、优惠与审批",
              List.of(
                  "ORGANIZATION_READ",
                  "STUDENT_READ",
                  "PRODUCT_READ",
                  "CHANNEL_READ",
                  "CUSTOMER_READ",
                  "CUSTOMER_MAINTAIN",
                  "CUSTOMER_PHONE_READ",
                  "CUSTOMER_POOL_READ",
                  "CUSTOMER_POOL_MANAGE",
                  "CUSTOMER_ASSIGN",
                  "STUDENT_ASSIGN",
                  "ENROLLMENT_READ",
                  "ENROLLMENT_MAINTAIN",
                  "ENROLLMENT_APPROVE",
                  "ENROLLMENT_DISCOUNT",
                  "FUNDS_READ",
                  "FUNDS_RECEIPT",
                  "APPROVAL_READ",
                  "APPROVAL_DECIDE")),
          new Template(
              "START_LEARNING_ADVISOR",
              "学习顾问",
              "学员服务、续费跟进与上课情况；可维护学员与报名",
              List.of(
                  "ORGANIZATION_READ",
                  "STUDENT_READ",
                  "STUDENT_MAINTAIN",
                  "PRODUCT_READ",
                  "CUSTOMER_READ",
                  "ENROLLMENT_READ",
                  "ENROLLMENT_MAINTAIN",
                  "TEACHING_READ",
                  "ATTENDANCE_RECORD")),
          new Template(
              "START_ACADEMIC",
              "教务",
              "班级、排课、教师/教室、考勤与关课",
              List.of(
                  "ORGANIZATION_READ",
                  "EMPLOYEE_READ",
                  "TEACHER_READ",
                  "TEACHER_MAINTAIN",
                  "ROOM_READ",
                  "ROOM_MAINTAIN",
                  "STUDENT_READ",
                  "STUDENT_MAINTAIN",
                  "PRODUCT_READ",
                  "ENROLLMENT_READ",
                  "TEACHING_READ",
                  "TEACHING_MAINTAIN",
                  "ATTENDANCE_RECORD",
                  "LESSON_CLOSE",
                  "APPROVAL_READ")),
          new Template(
              "START_TEACHER", "教师", "本人课表与考勤", List.of("TEACHING_READ", "ATTENDANCE_RECORD")),
          new Template(
              "START_HR",
              "人力",
              "员工、教师与人事档案",
              List.of(
                  "ORGANIZATION_READ",
                  "EMPLOYEE_READ",
                  "EMPLOYEE_MAINTAIN",
                  "TEACHER_READ",
                  "TEACHER_MAINTAIN",
                  "PERSONNEL_READ",
                  "PERSONNEL_MAINTAIN")));

  /** 按代码查找；调用方传入的代码先去除首尾空白并转大写。 */
  public static Optional<Template> find(String code) {
    if (code == null) return Optional.empty();
    var normalized = code.strip().toUpperCase(Locale.ROOT);
    return TEMPLATES.stream().filter(template -> template.code().equals(normalized)).findFirst();
  }
}
