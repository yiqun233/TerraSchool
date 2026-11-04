package org.teraschool.server;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.approval.api.*;

/** 审批策略与审批单的 HTTP 适配；申请、决定与消耗都在应用服务内完成授权与校验。 */
@RestController
@RequestMapping(produces = "application/json")
class ApprovalHttp {
  private final ApprovalQueries queries;
  private final ApprovalCommands commands;

  ApprovalHttp(ApprovalQueries queries, ApprovalCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @GetMapping("/api/approval/capabilities")
  ApprovalCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/approval/policies")
  List<ApprovalPolicyView> policies(@RequestParam UUID schoolId) {
    return queries.policies(schoolId);
  }

  @GetMapping("/api/approval/requests")
  List<ApprovalRequestView> requests(
      @RequestParam UUID schoolId, @RequestParam(defaultValue = "") String status) {
    return queries.requests(schoolId, status);
  }

  /** 维护某学校某动作的审批策略；金额低于 freeBelow 免审批。 */
  @PostMapping(value = "/api/approval/policies", consumes = "application/json")
  ApprovalPolicyView upsertPolicy(@RequestBody UpsertPolicy command) {
    return commands.upsertPolicy(command);
  }

  @PostMapping(value = "/api/approval/requests", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  ApprovalRequestView request(@RequestBody CreateApprovalRequest command) {
    return commands.request(command);
  }

  /** 审批决定：申请人不能审批自己的单据。 */
  @PostMapping(value = "/api/approval/requests/{id}/decide", consumes = "application/json")
  ApprovalRequestView decide(@PathVariable UUID id, @RequestBody DecideApproval command) {
    return commands.decide(id, command);
  }

  /** 申请人可撤回仍待审且版本匹配的审批单。 */
  @PostMapping(value = "/api/approval/requests/{id}/withdraw", consumes = "application/json")
  ApprovalRequestView withdraw(@PathVariable UUID id, @RequestBody WithdrawApproval command) {
    return commands.withdraw(id, command.expectedVersion());
  }

  record WithdrawApproval(long expectedVersion) {}

  @PostMapping(value = "/api/approval/requests/{id}/expire", consumes = "application/json")
  ApprovalRequestView expire(@PathVariable UUID id, @RequestBody ExpireApproval command) {
    return commands.expire(id, command.expectedVersion(), command.reason());
  }

  record ExpireApproval(long expectedVersion, String reason) {}
}
