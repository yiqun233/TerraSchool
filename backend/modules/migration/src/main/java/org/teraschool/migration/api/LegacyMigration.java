package org.teraschool.migration.api;

import java.util.List;
import java.util.UUID;

/** 旧库迁移：装载暂存 → 干跑对账 → 确认导入（幂等）。 */
public interface LegacyMigration {
  MigrationReport stage(UUID schoolId, List<StageStudent> students, List<StageReceipt> receipts);

  MigrationReport dryRun(UUID schoolId);

  MigrationReport commit(UUID schoolId);

  /** 批量导入期初余额与期初课时；逐行返回结果，按 batchId 幂等。 */
  OpeningImport.Report importOpenings(UUID schoolId, OpeningImport request);

  /** 按批次对账：应有值 vs 系统当前实际值，差额逐行列出。 */
  OpeningReconciliation reconcileOpenings(UUID schoolId, String batchId);
}
