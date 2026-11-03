package org.teraschool.migration.api;

import java.util.List;

/** 只读预检契约；来源身份由调用方提供，摘要只绑定请求内容，不能证明来源真实性。 */
public final class LegacyPreflight {
  private LegacyPreflight() {}

  public record PreflightRequest(
      String sourceId,
      String snapshotId,
      String mappingVersion,
      List<StageStudent> students,
      List<StageReceipt> receipts) {}

  /** kind 与 rowNumber 仅定位输入行，code 为稳定问题码，不回显个人信息。 */
  public record Issue(String kind, int rowNumber, String code) {}

  public record PreflightReport(
      String sourceId,
      String snapshotId,
      String mappingVersion,
      String inputDigest,
      int studentCount,
      int receiptCount,
      boolean canCommit,
      List<Issue> issues,
      List<String> unchecked) {
    public PreflightReport {
      issues = List.copyOf(issues);
      unchecked = List.copyOf(unchecked);
    }
  }
}
