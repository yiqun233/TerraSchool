package org.teraschool.server;

import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class Errors {
  private final RejectedRequestAudit audit;

  Errors(RejectedRequestAudit audit) {
    this.audit = audit;
  }

  @ExceptionHandler(org.teraschool.platform.api.AuditUnavailable.class)
  ResponseEntity<ProblemDetail> auditUnavailable() {
    return problem(503, "AUDIT_UNAVAILABLE");
  }

  @ExceptionHandler(org.teraschool.platform.api.ExportLimitExceeded.class)
  ResponseEntity<ProblemDetail> exportLimitExceeded() {
    return problem(409, "EXPORT_LIMIT_EXCEEDED");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> unsupported(jakarta.servlet.http.HttpServletRequest request) {
    return contractRejected(request, 415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ProblemDetail> unacceptable(jakarta.servlet.http.HttpServletRequest request) {
    return contractRejected(request, 406, "NOT_ACCEPTABLE");
  }

  private ResponseEntity<ProblemDetail> contractRejected(
      jakarta.servlet.http.HttpServletRequest request, int status, String code) {
    try {
      audit.record(request, code);
    } catch (org.teraschool.platform.api.AuditUnavailable unavailable) {
      return problem(503, "AUDIT_UNAVAILABLE");
    }
    return problem(status, code);
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<ProblemDetail> unexpected(RuntimeException error) {
    if (error instanceof org.springframework.web.ErrorResponse response)
      return problem(response.getStatusCode().value(), "REQUEST_REJECTED");
    return problem(500, "OPERATION_FAILED");
  }

  @ExceptionHandler(org.teraschool.organization.api.OrganizationConflict.class)
  ResponseEntity<ProblemDetail> conflict(
      org.teraschool.organization.api.OrganizationConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.identity.api.AuthorizationConflict.class)
  ResponseEntity<ProblemDetail> authorizationConflict(
      org.teraschool.identity.api.AuthorizationConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.archive.api.StudentConflict.class)
  ResponseEntity<ProblemDetail> studentConflict(org.teraschool.archive.api.StudentConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.product.api.ProductConflict.class)
  ResponseEntity<ProblemDetail> productConflict(org.teraschool.product.api.ProductConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.enrollment.api.EnrollmentConflict.class)
  ResponseEntity<ProblemDetail> enrollmentConflict(
      org.teraschool.enrollment.api.EnrollmentConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.funds.api.FundsConflict.class)
  ResponseEntity<ProblemDetail> fundsConflict(org.teraschool.funds.api.FundsConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.finance.api.PeriodConflict.class)
  ResponseEntity<ProblemDetail> periodConflict(org.teraschool.finance.api.PeriodConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.approval.api.ApprovalConflict.class)
  ResponseEntity<ProblemDetail> approvalConflict(
      org.teraschool.approval.api.ApprovalConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.teaching.api.TeachingConflict.class)
  ResponseEntity<ProblemDetail> teachingConflict(
      org.teraschool.teaching.api.TeachingConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.migration.api.MigrationConflict.class)
  ResponseEntity<ProblemDetail> migrationConflict(
      org.teraschool.migration.api.MigrationConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.admissions.api.ChannelConflict.class)
  ResponseEntity<ProblemDetail> channelConflict(
      org.teraschool.admissions.api.ChannelConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(org.teraschool.admissions.api.CustomerConflict.class)
  ResponseEntity<ProblemDetail> customerConflict(
      org.teraschool.admissions.api.CustomerConflict error) {
    return problem(409, error.code());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> denied() {
    return problem(403, "ACCESS_DENIED");
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ProblemDetail> invalid(
      Exception error, jakarta.servlet.http.HttpServletRequest request) {
    if (!(error instanceof IllegalArgumentException)) {
      try {
        audit.record(request, "INVALID_INPUT");
      } catch (org.teraschool.platform.api.AuditUnavailable unavailable) {
        return problem(503, "AUDIT_UNAVAILABLE");
      }
    }
    return problem(400, "INVALID_INPUT");
  }

  private ResponseEntity<ProblemDetail> problem(int status, String code) {
    var p = ProblemDetail.forStatus(status);
    p.setType(java.net.URI.create("urn:teraschool:error:" + code.toLowerCase()));
    p.setProperty("code", code);
    p.setProperty("traceId", org.teraschool.platform.api.RequestTrace.id());
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
  }
}
