package org.teraschool.server;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.migration.api.*;

/** 旧库迁移 HTTP 适配；旧主体确认导入暂时停用，授权与对账由应用服务执行。 */
@RestController
@RequestMapping(value = "/api/migration", produces = "application/json")
class MigrationHttp {
  record StageRequest(List<StageStudent> students, List<StageReceipt> receipts) {}

  private final LegacyMigration migration;

  MigrationHttp(LegacyMigration migration) {
    this.migration = migration;
  }

  @PostMapping(value = "/staging", consumes = "application/json")
  MigrationReport stage(@RequestParam UUID schoolId, @RequestBody StageRequest request) {
    return migration.stage(schoolId, request.students(), request.receipts());
  }

  /** 期初导入：逐行导入历史余额与已消耗课时，逐行返回结果。 */
  @PostMapping(value = "/openings", consumes = "application/json")
  OpeningImport.Report importOpenings(
      @RequestParam UUID schoolId, @RequestBody OpeningImport request) {
    return migration.importOpenings(schoolId, request);
  }

  /** 期初对账：按批次对比应有值与系统当前实际值。 */
  @GetMapping("/openings/reconciliation")
  OpeningReconciliation reconcileOpenings(
      @RequestParam UUID schoolId, @RequestParam String batchId) {
    return migration.reconcileOpenings(schoolId, batchId);
  }

  @GetMapping("/dry-run")
  MigrationReport dryRun(@RequestParam UUID schoolId) {
    return migration.dryRun(schoolId);
  }

  /** 兼容保留旧入口；服务端返回停用冲突，不写业务数据。 */
  @PostMapping("/commit")
  MigrationReport commit(@RequestParam UUID schoolId) {
    return migration.commit(schoolId);
  }
}
