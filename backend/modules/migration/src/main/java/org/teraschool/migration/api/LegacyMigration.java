package org.teraschool.migration.api;

import java.util.List;
import java.util.UUID;

/** 旧库迁移：装载暂存 → 干跑对账 → 确认导入（幂等）。 */
public interface LegacyMigration {
  MigrationReport stage(UUID schoolId, List<StageStudent> students, List<StageReceipt> receipts);

  MigrationReport dryRun(UUID schoolId);

  MigrationReport commit(UUID schoolId);
}
