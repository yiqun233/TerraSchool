package org.teraschool.server;

import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class Errors {
  @ExceptionHandler(org.teraschool.organization.api.OrganizationConflict.class)
  ResponseEntity<ProblemDetail> conflict(
      org.teraschool.organization.api.OrganizationConflict error) {
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
  ResponseEntity<ProblemDetail> invalid() {
    return problem(400, "INVALID_INPUT");
  }

  private ResponseEntity<ProblemDetail> problem(int status, String code) {
    var p = ProblemDetail.forStatus(status);
    p.setType(java.net.URI.create("urn:teraschool:error:" + code.toLowerCase()));
    p.setProperty("code", code);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
  }
}
