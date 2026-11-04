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
}
