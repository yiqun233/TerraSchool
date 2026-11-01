package org.teraschool.server;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.enrollment.api.*;

/** 报名与应收的 HTTP 适配：授权、快照、幂等与审核规则都在应用服务内。 */
@RestController
@RequestMapping(produces = "application/json")
class EnrollmentHttp {
  private final EnrollmentQueries queries;
  private final EnrollmentCommands commands;

  EnrollmentHttp(EnrollmentQueries queries, EnrollmentCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @GetMapping("/api/enrollment/capabilities")
  EnrollmentCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/enrollment/enrollments")
  EnrollmentPage list(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return queries.list(schoolId, status, page, size);
  }

  @GetMapping("/api/enrollment/enrollments/{id}")
  EnrollmentView detail(@PathVariable UUID id) {
    return queries.detail(id);
  }

  /** 报名只提交学员与价格版本；单价与课时由服务端从价格快照读取。 */
  @PostMapping(value = "/api/enrollment/enrollments", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  EnrollmentView create(@RequestBody CreateEnrollment command) {
    return commands.create(command);
  }

  /** 登记一次性减免：只写金额与原因，不承担优惠券/阶梯折扣等规则计算。 */
  @PostMapping(value = "/api/enrollment/enrollments/{id}/discounts", consumes = "application/json")
  EnrollmentView discount(@PathVariable UUID id, @RequestBody ApplyDiscount command) {
    return commands.applyDiscount(id, command);
  }

  /** 审核是独立动作与独立权限，不能靠修改接口设置状态。 */
  @PostMapping(value = "/api/enrollment/enrollments/{id}/approve", consumes = "application/json")
  EnrollmentView approve(@PathVariable UUID id, @RequestBody EnrollmentVersion command) {
    return commands.approve(id, command);
  }
}
