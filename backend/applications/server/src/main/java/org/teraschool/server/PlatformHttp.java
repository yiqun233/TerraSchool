package org.teraschool.server;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 平台登录入口：与会话相关的四个动作，路径与租户的 /api/session/** 完全分开。 */
@RestController
class PlatformHttp {
  private final PlatformIdentity identity;
  private final TenantProvisioning provisioning;

  PlatformHttp(PlatformIdentity identity, TenantProvisioning provisioning) {
    this.identity = identity;
    this.provisioning = provisioning;
  }

  @GetMapping("/api/platform/session/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping(
      value = "/api/platform/session/login",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  ResponseEntity<?> login(
      @RequestParam String login, @RequestParam String password, HttpServletResponse response) {
    var operator = identity.authenticate(login, password).orElse(null);
    if (operator == null) {
      // 不区分账号不存在与口令错误，避免枚举平台账号。
      try {
        PlatformSecurity.write(response, 401, "PLATFORM_AUTHENTICATION_FAILED");
      } catch (java.io.IOException io) {
        return ResponseEntity.status(500).build();
      }
      return null;
    }
    var token = identity.open(operator.id());
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, identity.sessionTtl()).toString());
    identity.audit(operator.id(), "PLATFORM_LOGIN", "");
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/platform/session/logout")
  ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    identity.close(PlatformSessionFilter.token(request));
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", java.time.Duration.ZERO).toString());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/platform/session/me")
  ResponseEntity<?> me(org.springframework.security.core.Authentication authentication) {
    var operator =
        authentication != null
                && authentication.getPrincipal() instanceof PlatformIdentity.Operator o
            ? o
            : null;
    if (operator == null) return ResponseEntity.status(401).build();
    return ResponseEntity.ok(
        Map.of(
            "login", operator.login(),
            "displayName", operator.displayName(),
            "actions", operator.actions()));
  }

  /** 开通新租户：只建租户与管理员，不建学校。 */
  @PostMapping("/api/platform/tenants")
  ResponseEntity<?> provision(
      @RequestBody TenantProvisioning.Request request,
      org.springframework.security.core.Authentication authentication,
      HttpServletResponse response) {
    var operator = operator(authentication);
    try {
      var provisioned = provisioning.provision(operator, request);
      identity.audit(operator.id(), "TENANT_PROVISION", "");
      return ResponseEntity.status(201)
          .body(
              Map.of(
                  "tenantId", provisioned.tenantId(),
                  "accountId", provisioned.accountId()));
    } catch (TenantProvisioning.ProvisioningConflict conflict) {
      return problem(response, 409, conflict.code());
    } catch (IllegalArgumentException invalid) {
      return problem(response, 400, "INVALID_REQUEST", invalid.getMessage());
    }
  }

  /** 租户列表：只有名称、状态与开通时间，不含任何业务数据。 */
  @GetMapping("/api/platform/tenants")
  ResponseEntity<?> tenants(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      org.springframework.security.core.Authentication authentication) {
    var operator = operator(authentication);
    if (page < 0 || size < 1 || size > 100) return ResponseEntity.badRequest().build();
    if (!operator.actions().contains("TENANT_READ")) return ResponseEntity.status(403).build();
    return ResponseEntity.ok(Map.of("items", provisioning.tenants(page, size), "page", page));
  }

  private PlatformIdentity.Operator operator(
      org.springframework.security.core.Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof PlatformIdentity.Operator operator)
      return operator;
    throw new IllegalStateException("平台会话缺失");
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpServletResponse response, int status, String code) {
    return problem(response, status, code, null);
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpServletResponse response, int status, String code, String detail) {
    var problem = ProblemDetail.forStatus(status);
    problem.setTitle(code);
    problem.setDetail(detail);
    problem.setProperty("code", code);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  private ResponseCookie cookie(String value, java.time.Duration maxAge) {
    return ResponseCookie.from(PlatformIdentity.COOKIE, value)
        .httpOnly(true)
        .secure(identity.secureCookie())
        .sameSite("Lax")
        .path("/api/platform")
        .maxAge(maxAge)
        .build();
  }
}
