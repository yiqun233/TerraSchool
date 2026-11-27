package org.teraschool.enrollment.api;

import java.util.List;
import java.util.UUID;

public interface EnrollmentQueries {
  record ConsumptionBase(
      UUID enrollmentId,
      UUID studentId,
      String status,
      int settlementMode,
      String price,
      String purchasedHours,
      String cancelledHours) {}

  record ConsumptionBasePage(List<ConsumptionBase> items, int page, int size, long total) {
    public ConsumptionBasePage {
      items = List.copyOf(items);
    }
  }

  EnrollmentCapabilities capabilities();

  EnrollmentPage list(UUID schoolId, String status, int page, int size);

  /** 报名消费对账的最小无姓名数据基底，按报名权限分页。 */
  ConsumptionBasePage consumptionBase(UUID schoolId, int page, int size);

  EnrollmentView detail(UUID id);

  /** 报名 CSV 导出（学校），导出写审计。 */
  String exportCsv(UUID schoolId);

  /** 课程顾问业绩：按顾问汇总报名数、合同金额与课时；未指定顾问的归入“未指定顾问”。 */
  record ConsultantPerformance(
      UUID consultantId, String consultantName, int enrollments, String amount, String hours) {}

  List<ConsultantPerformance> consultantPerformance(UUID schoolId, String from, String to);

  /** 报名可选的校区。 */
  record CampusOption(UUID id, String name) {}

  /** 可作为课程顾问的在校员工。 */
  record ConsultantOption(UUID id, String name) {}

  List<CampusOption> campuses(UUID schoolId);

  List<ConsultantOption> consultants(UUID schoolId);
}
