package org.teraschool.server;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.funds.api.*;

/** 收款与分配的 HTTP 适配：金额为十进制字符串，记账与并发控制在应用服务内。 */
@RestController
@RequestMapping(produces = "application/json")
class FundsHttp {
  private final FundsQueries queries;
  private final FundsCommands commands;

  FundsHttp(FundsQueries queries, FundsCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @GetMapping("/api/funds/capabilities")
  FundsCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/funds/balance")
  StudentBalance balance(@RequestParam UUID studentId) {
    return queries.balance(studentId);
  }

  @GetMapping("/api/funds/receipts")
  List<Receipt> receipts(@RequestParam UUID studentId) {
    return queries.receipts(studentId);
  }

  @GetMapping("/api/funds/allocations")
  List<Allocation> allocations(@RequestParam UUID enrollmentId) {
    return queries.allocations(enrollmentId);
  }

  @GetMapping("/api/funds/outstanding")
  String outstanding(@RequestParam UUID enrollmentId) {
    return queries.outstanding(enrollmentId);
  }

  /** 期初余额（迁移用）：只写 OPENING 分录，不产生实收。 */
  @PostMapping(value = "/api/funds/openings", consumes = "application/json")
  StudentBalance openBalance(@RequestBody OpenBalance command) {
    return commands.openBalance(command);
  }

  /** 收款 CSV 导出：按学校与日期范围，导出写审计。 */
  @GetMapping(value = "/api/funds/receipts/export", produces = "text/csv; charset=UTF-8")
  org.springframework.http.ResponseEntity<String> exportReceipts(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String to) {
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"receipts.csv\"")
        .body(queries.exportReceiptsCsv(schoolId, from, to));
  }

  @PostMapping(value = "/api/funds/receipts", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  Receipt createReceipt(@RequestBody CreateReceipt command) {
    return commands.createReceipt(command);
  }

  @PostMapping(value = "/api/funds/receipts/{id}/void", consumes = "application/json")
  Receipt voidReceipt(@PathVariable UUID id, @RequestBody FundsVersion command) {
    return commands.voidReceipt(id, command);
  }

  @PostMapping(value = "/api/funds/allocations", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  Allocation allocate(@RequestBody AllocateReceipt command) {
    return commands.allocate(command);
  }

  @PostMapping("/api/funds/allocations/{id}/release")
  Allocation release(@PathVariable UUID id) {
    return commands.releaseAllocation(id);
  }

  /** 退课时并退到账户：本次退量 + 应收减项 + 分配调整，差额留在账户。 */
  @PostMapping(value = "/api/funds/enrollments/{id}/cancel-hours", consumes = "application/json")
  RefundResult cancelHours(
      @PathVariable UUID id, @RequestBody CancelHoursAndReturnCommand command) {
    return commands.cancelHoursAndReturnToAccount(id, command);
  }

  @GetMapping("/api/funds/refunds")
  List<CashRefundView> refunds(@RequestParam UUID enrollmentId) {
    return queries.cashRefunds(enrollmentId);
  }

  /** 准备现金退款并冻结资金；尚未实际支付。 */
  @PostMapping(value = "/api/funds/refunds", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  CashRefundView prepareRefund(@RequestBody PrepareCashRefund command) {
    return commands.prepareCashRefund(command);
  }

  /** 确认已向家长实际支付，必须提供凭据。 */
  @PostMapping(value = "/api/funds/refunds/{id}/confirm", consumes = "application/json")
  CashRefundView confirmRefund(@PathVariable UUID id, @RequestBody ConfirmCashRefund command) {
    return commands.confirmCashRefundPaid(id, command);
  }

  @PostMapping(value = "/api/funds/refunds/{id}/cancel", consumes = "application/json")
  CashRefundView cancelRefund(@PathVariable UUID id, @RequestBody FundsVersion command) {
    return commands.cancelCashRefund(id, command);
  }
}
