package org.teraschool.identity.api;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户内动作目录的唯一权威定义。
 *
 * <p>界面选项、授权校验与文案都从这里派生，不在前端或各模块重复维护字符串数组：
 * 过去一处漏登记（REFUND_*、ENROLLMENT_DISCOUNT、PERIOD_UNLOCK）会让权限永远授不出去， 而业务代码照常校验，表现为“功能对所有人不可用”。
 *
 * <p>列表顺序即界面分组顺序，新增动作只追加到所属分组末尾，不打乱既有顺序。
 */
public final class PermissionCatalog {
  private PermissionCatalog() {}

  /** 范围类型：SCHOOL 按学校生效；TENANT 为租户级，不随学校。 */
  public enum Scope {
    SCHOOL,
    TENANT
  }

  /** 风险级别：SENSITIVE 在界面上额外提示，用于资金、退款、授权与导出等动作。 */
  public enum Risk {
    NORMAL,
    SENSITIVE
  }

  /**
   * 一个功能动作。
   *
   * @param code 稳定动作码，存储与接口使用，不随文案变化
   * @param group 业务域分组，同时决定界面顺序
   * @param label 中文名
   * @param description 一句话说明这个动作管什么
   * @param pages 对应页面，帮助业务人员把权限和菜单对上
   * @param scope 是否需要按学校授权
   * @param risk 风险级别
   */
  public record Action(
      String code,
      String group,
      String label,
      String description,
      List<String> pages,
      Scope scope,
      Risk risk) {
    public Action {
      pages = List.copyOf(pages);
    }
  }

  public static final List<Action> ACTIONS =
      List.of(
          // 组织与身份
          new Action(
              "ORGANIZATION_READ",
              "组织与身份",
              "组织查看",
              "查看本机构的学校、校区与部门目录",
              List.of("组织目录"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "ORGANIZATION_MAINTAIN",
              "组织与身份",
              "组织维护",
              "新增学校、校区、部门并改名",
              List.of("组织目录"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "AUTHORIZATION_MANAGE",
              "组织与身份",
              "授权管理",
              "管理账号授权、角色模板与账号生命周期",
              List.of("权限管理"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "TENANT_BRANDING_WRITE",
              "组织与身份",
              "品牌与外观设置",
              "修改机构名称、品牌色与 Logo",
              List.of("品牌与外观"),
              Scope.TENANT,
              Risk.SENSITIVE),
          // 员工与人事
          new Action(
              "EMPLOYEE_READ",
              "员工与人事",
              "员工查看",
              "查看员工档案与在职状态",
              List.of("员工管理"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "EMPLOYEE_MAINTAIN",
              "员工与人事",
              "员工维护",
              "新增、修改与停用员工档案",
              List.of("员工管理"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "TEACHER_READ",
              "员工与人事",
              "教师查看",
              "查看教师资料与可授课范围",
              List.of("教师管理"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "TEACHER_MAINTAIN",
              "员工与人事",
              "教师维护",
              "维护教师资料与授课资格",
              List.of("教师管理"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "ROOM_READ",
              "员工与人事",
              "教室查看",
              "查看教室与其容纳条件",
              List.of("教室管理"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "ROOM_MAINTAIN",
              "员工与人事",
              "教室维护",
              "新增与维护教室",
              List.of("教室管理"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "PERSONNEL_READ",
              "员工与人事",
              "人事档案查看",
              "查看合同、证件等人事档案",
              List.of("员工管理"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "PERSONNEL_MAINTAIN",
              "员工与人事",
              "人事档案维护",
              "维护人事档案与附件",
              List.of("员工管理"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 学员档案
          new Action(
              "STUDENT_READ",
              "学员档案",
              "学员查看",
              "查看学员档案，敏感字段按字段权限脱敏",
              List.of("学员档案"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "STUDENT_MAINTAIN",
              "学员档案",
              "学员维护",
              "新增与修改学员档案",
              List.of("学员档案"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "STUDENT_SENSITIVE_READ",
              "学员档案",
              "学员敏感字段查看",
              "查看证件号、电话、邮箱等敏感字段明文",
              List.of("学员档案"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "STUDENT_ASSIGN",
              "学员档案",
              "学员负责人分派",
              "变更学员负责人",
              List.of("学员档案"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "STUDENT_EXPORT",
              "学员档案",
              "学员导出",
              "导出学员名单与联系方式",
              List.of("学员档案"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 课程与产品
          new Action(
              "PRODUCT_READ",
              "课程与产品",
              "课程产品查看",
              "查看课程与产品目录",
              List.of("课程与产品"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "PRODUCT_MAINTAIN",
              "课程与产品",
              "课程产品维护",
              "新增与修改课程、产品",
              List.of("课程与产品"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "PRICE_PUBLISH",
              "课程与产品",
              "定价发布",
              "发布与停用价格版本",
              List.of("课程与产品"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 招生
          new Action(
              "CHANNEL_READ",
              "招生",
              "招生渠道查看",
              "查看招生渠道与地址",
              List.of("招生渠道"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "CHANNEL_MAINTAIN",
              "招生",
              "招生渠道维护",
              "新增与维护招生渠道",
              List.of("招生渠道"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "CUSTOMER_READ",
              "招生",
              "本人客户查看",
              "查看本人负责的客户",
              List.of("本人客户"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "CUSTOMER_MAINTAIN",
              "招生",
              "本人客户录入",
              "录入与跟进本人客户",
              List.of("本人客户"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "CUSTOMER_PHONE_READ",
              "招生",
              "客户完整电话查看",
              "查看客户完整手机号",
              List.of("本人客户"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "CUSTOMER_POOL_READ",
              "招生",
              "客户公海查看",
              "查看客户公海",
              List.of("本人客户"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "CUSTOMER_POOL_MANAGE",
              "招生",
              "客户公海维护",
              "回收与维护公海客户",
              List.of("本人客户"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "CUSTOMER_ASSIGN",
              "招生",
              "客户分派",
              "把客户分派给员工",
              List.of("本人客户"),
              Scope.SCHOOL,
              Risk.NORMAL),
          // 报名与收款
          new Action(
              "ENROLLMENT_READ",
              "报名与收款",
              "报名查看",
              "查看报名记录",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "ENROLLMENT_MAINTAIN",
              "报名与收款",
              "报名维护",
              "登记报名与修改报名信息",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "ENROLLMENT_APPROVE",
              "报名与收款",
              "报名审核",
              "审核报名记录",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "ENROLLMENT_DISCOUNT",
              "报名与收款",
              "报名优惠减免",
              "登记报名优惠与减款",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "FUNDS_READ",
              "报名与收款",
              "收款查看",
              "查看收款记录与账户余额",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "FUNDS_RECEIPT",
              "报名与收款",
              "收款登记",
              "登记收款",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "FUNDS_ALLOCATE",
              "报名与收款",
              "收款分配",
              "把收款分配到报名",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 退款
          new Action(
              "REFUND_READ",
              "退款",
              "退款查看",
              "查看退款申请与记录",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "REFUND_APPLY",
              "退款",
              "退款申请",
              "申请退课时与现金退款",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "REFUND_PAY",
              "退款",
              "退款付款",
              "确认现金退款已付款",
              List.of("报名与收款"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 教务
          new Action(
              "TEACHING_READ",
              "教务",
              "班级课次查看",
              "查看班级、课次与课表",
              List.of("教务与考勤"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "TEACHING_MAINTAIN",
              "教务",
              "班级课次维护",
              "新建班级、排课与调整课次",
              List.of("教务与考勤"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "ATTENDANCE_RECORD",
              "教务",
              "考勤登记",
              "登记上课考勤",
              List.of("教务与考勤"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "LESSON_CLOSE",
              "教务",
              "课次关课",
              "关闭课次并结算课时消耗",
              List.of("教务与考勤"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 审批
          new Action(
              "APPROVAL_READ",
              "审批",
              "审批查看",
              "查看审批单与处理进度",
              List.of("审批"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "APPROVAL_MANAGE",
              "审批",
              "审批策略维护",
              "维护审批策略与免批额度",
              List.of("审批"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          new Action(
              "APPROVAL_DECIDE",
              "审批",
              "审批决定",
              "批准或驳回审批单",
              List.of("审批"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 财务期间
          new Action(
              "PERIOD_READ",
              "财务期间",
              "财务期间查看",
              "查看财务期间与锁定状态",
              List.of("财务期间"),
              Scope.SCHOOL,
              Risk.NORMAL),
          new Action(
              "PERIOD_LOCK", "财务期间", "锁月", "锁定财务月份", List.of("财务期间"), Scope.SCHOOL, Risk.SENSITIVE),
          new Action(
              "PERIOD_UNLOCK",
              "财务期间",
              "解锁",
              "解锁已锁定的财务月份",
              List.of("财务期间"),
              Scope.SCHOOL,
              Risk.SENSITIVE),
          // 数据迁移
          new Action(
              "MIGRATION_MANAGE",
              "数据迁移",
              "数据迁移管理",
              "装载预检与确认旧库迁移数据",
              List.of("数据迁移"),
              Scope.SCHOOL,
              Risk.SENSITIVE));

  /** 全部动作码；授权校验只接受这里的取值。 */
  public static final Set<String> CODES =
      ACTIONS.stream().map(Action::code).collect(Collectors.toUnmodifiableSet());

  /**
   * 新建学校时按该校范围铺给创建者的动作集合。
   *
   * <p>为什么不从 {@link #ACTIONS} 里按 {@code scope == SCHOOL} 自动派生：新增一个敏感动作
   * （退款付款、锁月解锁、数据迁移）是否随建校自动授出去，必须逐次评审；隐式派生等于悄悄扩权。 这里显式列举，并由 {@code PermissionCatalogTest}
   * 卡住“目录新增了按学校生效的动作， 但既没进本表、也没进 {@link #SCHOOL_PROVISION_EXCLUDED}”的情况。
   *
   * <p>为什么都放进来：建校人就是本机构管理员，且本就持有 {@code AUTHORIZATION_MANAGE}，
   * 这些动作他可以自己授给自己；自动铺开只是省掉一遍手工授权，不扩大他的有效权限。 只授“能看组织”会让建校后学员、报名、教务、财务全部 403（2026-11-10 本地反馈）。
   */
  public static final List<String> SCHOOL_PROVISION_ACTIONS =
      List.of(
          "ORGANIZATION_READ",
          "ORGANIZATION_MAINTAIN",
          "AUTHORIZATION_MANAGE",
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
          "MIGRATION_MANAGE");

  /** 明确不随建校铺开的按学校动作；租户级动作不随学校生效，不在此列。 */
  public static final Set<String> SCHOOL_PROVISION_EXCLUDED = Set.of();
}
