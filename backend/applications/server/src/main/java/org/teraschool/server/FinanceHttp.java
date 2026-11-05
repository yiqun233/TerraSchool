package org.teraschool.server;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.finance.api.*;

/** 财务期间的 HTTP 适配：锁月与解锁都在应用服务内做权限、状态与审计处理。 */
@RestController
@RequestMapping(produces = "application/json")
class FinanceHttp {
  private final PeriodQueries queries;
  private final PeriodCommands commands;
  private final FinancialReportQueries reports;

  FinanceHttp(PeriodQueries queries, PeriodCommands commands, FinancialReportQueries reports) {
    this.queries = queries;
    this.commands = commands;
    this.reports = reports;
  }

  @GetMapping("/api/finance/capabilities")
  FinanceCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/finance/reports/funds-reconciliation")
  FundsReconciliation fundsReconciliation(@RequestParam UUID schoolId) {
    return reports.fundsReconciliation(schoolId);
  }

  @GetMapping("/api/finance/reports/receipt-daily")
  List<ReceiptDailyTotal> receiptDailyTotals(
      @RequestParam UUID schoolId, @RequestParam String from, @RequestParam String to) {
    return reports.receiptDailyTotals(schoolId, from, to);
  }

  @GetMapping("/api/finance/reports/daily-overview")
  DailyOverview dailyOverview(@RequestParam UUID schoolId, @RequestParam String day) {
    return reports.dailyOverview(schoolId, day);
  }

  @GetMapping("/api/finance/periods")
  List<PeriodView> periods(
      @RequestParam UUID schoolId, @RequestParam(defaultValue = "") String year) {
    return queries.periods(schoolId, year);
  }

  /** 锁定某月：之后该月的收款、分配、退费与课次消费一律拒绝写入。 */
  @PostMapping(value = "/api/finance/periods/{period}/lock", consumes = "application/json")
  PeriodView lock(@PathVariable String period, @RequestBody LockPeriod command) {
    return commands.lock(
        command.schoolId(),
        new LockPeriod(command.schoolId(), period, command.reason(), command.expectedVersion()));
  }

  /** 解锁需要版本与原因，并写审计。 */
  @PostMapping(value = "/api/finance/periods/{period}/unlock", consumes = "application/json")
  PeriodView unlock(@PathVariable String period, @RequestBody LockPeriod command) {
    return commands.unlock(
        command.schoolId(),
        new LockPeriod(command.schoolId(), period, command.reason(), command.expectedVersion()));
  }
}
