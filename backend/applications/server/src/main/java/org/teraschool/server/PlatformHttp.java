package org.teraschool.server;

import jakarta.servlet.http.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 平台登录入口：与会话相关的四个动作，路径与租户的 /api/session/** 完全分开。 */
@RestController
class PlatformHttp {
  private final PlatformIdentity identity;
  private final TenantProvisioning provisioning;
  private final BrandingService branding;

  PlatformHttp(
      PlatformIdentity identity, TenantProvisioning provisioning, BrandingService branding) {
    this.identity = identity;
    this.provisioning = provisioning;
    this.branding = branding;
  }

  record BrandingUpdate(String displayName, String seed, Integer expectedVersion) {}

  record RollbackBranding(Integer version) {}

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
      provisioning.recordFailure(operator, request, conflict.code());
      return problem(response, 409, conflict.code());
    } catch (IllegalArgumentException invalid) {
      provisioning.recordFailure(operator, request, "INVALID_REQUEST");
      return problem(response, 400, "INVALID_REQUEST", invalid.getMessage());
    }
  }

  /** 租户列表：名称、管理员登录名、状态与开通时间，不含任何业务数据。 */
  @GetMapping("/api/platform/tenants")
  ResponseEntity<?> tenants(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String keyword,
      org.springframework.security.core.Authentication authentication) {
    var operator = operator(authentication);
    if (page < 0 || size < 1 || size > 100) return ResponseEntity.badRequest().build();
    if (!operator.actions().contains("TENANT_READ")) return ResponseEntity.status(403).build();
    try {
      var counts = provisioning.counts();
      return ResponseEntity.ok(
          Map.of(
              "items",
              provisioning.tenants(page, size, status, keyword),
              "page",
              page,
              "size",
              size,
              "total",
              provisioning.countTenants(status, keyword),
              "summary",
              Map.of(
                  "total", counts.total(),
                  "active", counts.active(),
                  "suspended", counts.suspended(),
                  "thisMonth", counts.thisMonth())));
    } catch (IllegalArgumentException invalid) {
      return ResponseEntity.badRequest().build();
    }
  }

  /** 停用租户：只影响登录与访问，数据保留；可恢复。 */
  @PostMapping("/api/platform/tenants/{id}/suspend")
  ResponseEntity<?> suspend(
      @PathVariable UUID id,
      org.springframework.security.core.Authentication authentication,
      HttpServletResponse response) {
    return setActive(authentication, id, false, "TENANT_SUSPEND", response);
  }

  /** 恢复租户。 */
  @PostMapping("/api/platform/tenants/{id}/resume")
  ResponseEntity<?> resume(
      @PathVariable UUID id,
      org.springframework.security.core.Authentication authentication,
      HttpServletResponse response) {
    return setActive(authentication, id, true, "TENANT_RESUME", response);
  }

  private ResponseEntity<?> setActive(
      org.springframework.security.core.Authentication authentication,
      UUID id,
      boolean active,
      String action,
      HttpServletResponse response) {
    var operator = operator(authentication);
    if (!operator.actions().contains(action)) return ResponseEntity.status(403).build();
    try {
      provisioning.setActive(operator, id, active);
      return ResponseEntity.noContent().build();
    } catch (TenantProvisioning.TenantMissing missing) {
      return problem(response, 404, "TENANT_NOT_FOUND");
    }
  }

  /** 租户详情：状态、开通时间与三个计数；不含任何业务明细。 */
  @GetMapping("/api/platform/tenants/{id}")
  ResponseEntity<?> tenant(
      @PathVariable UUID id, org.springframework.security.core.Authentication authentication) {
    var operator = operator(authentication);
    if (!operator.actions().contains("TENANT_READ")) return ResponseEntity.status(403).build();
    var found = provisioning.tenant(id);
    if (found.isEmpty()) return ResponseEntity.status(404).build();
    var detail = new java.util.LinkedHashMap<String, Object>(found.get());
    detail.putAll(provisioning.summary(id));
    return ResponseEntity.ok(detail);
  }

  /** 某租户的租户管理员列表：只有登录名、状态与创建时间。 */
  @GetMapping("/api/platform/tenants/{id}/administrators")
  ResponseEntity<?> administrators(
      @PathVariable UUID id, org.springframework.security.core.Authentication authentication) {
    var operator = operator(authentication);
    if (!operator.actions().contains("TENANT_READ")) return ResponseEntity.status(403).build();
    return ResponseEntity.ok(Map.of("items", provisioning.administrators(id)));
  }

  /** 重置租户管理员密码：不传 password 时由服务端生成 16 位强口令。 明文只在这一次响应里返回——离开即无法再次查看。 */
  @PostMapping("/api/platform/tenants/{id}/administrators/{login}/reset-password")
  ResponseEntity<?> resetPassword(
      @PathVariable UUID id,
      @PathVariable String login,
      @RequestBody(required = false) ResetPassword body,
      org.springframework.security.core.Authentication authentication,
      HttpServletResponse response) {
    var operator = operator(authentication);
    if (!operator.actions().contains("TENANT_CREATE")) return ResponseEntity.status(403).build();
    var password =
        body != null && body.password() != null && !body.password().isBlank()
            ? body.password()
            : randomPassword();
    try {
      if (!provisioning.resetAdministratorPassword(operator, id, login, password))
        return problem(response, 404, "ADMINISTRATOR_NOT_FOUND");
    } catch (IllegalArgumentException invalid) {
      return problem(response, 400, "INVALID_REQUEST", invalid.getMessage());
    }
    return ResponseEntity.ok(Map.of("login", login, "password", password));
  }

  record ResetPassword(String password) {}

  /** 平台查看租户品牌：只读呈现信息，不读业务数据。 */
  @GetMapping("/api/platform/tenants/{id}/branding")
  ResponseEntity<?> tenantBranding(
      @PathVariable UUID id, org.springframework.security.core.Authentication authentication) {
    var operator = operator(authentication);
    if (!operator.actions().contains("TENANT_READ")) return ResponseEntity.status(403).build();
    if (provisioning.tenant(id).isEmpty()) return ResponseEntity.status(404).build();
    return ResponseEntity.ok(
        branding.platformTenant(id, operator.actions().contains("TENANT_BRANDING_WRITE")));
  }

  /** 平台查看最近 20 个品牌版本；回滚入口据此选择目标版本。 */
  @GetMapping("/api/platform/tenants/{id}/branding/history")
  ResponseEntity<?> tenantBrandingHistory(
      @PathVariable UUID id, org.springframework.security.core.Authentication authentication) {
    var operator = operator(authentication);
    if (!operator.actions().contains("TENANT_READ")) return ResponseEntity.status(403).build();
    if (provisioning.tenant(id).isEmpty()) return ResponseEntity.status(404).build();
    return ResponseEntity.ok(branding.historyPlatform(id));
  }

  /** 平台代改品牌：写平台审计，租户侧立即生效。 */
  @PutMapping(
      value = "/api/platform/tenants/{id}/branding",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> updateTenantBranding(
      @PathVariable UUID id,
      @RequestBody BrandingUpdate body,
      org.springframework.security.core.Authentication authentication,
      HttpServletResponse response) {
    var operator = operator(authentication);
    if (!operator.actions().contains("TENANT_BRANDING_WRITE"))
      return ResponseEntity.status(403).build();
    if (provisioning.tenant(id).isEmpty()) return problem(response, 404, "TENANT_NOT_FOUND");
    try {
      return ResponseEntity.ok(
          branding.updatePlatform(
              id,
              operator.id(),
              new BrandingService.Update(body.displayName(), body.seed(), body.expectedVersion())));
    } catch (BrandingService.VersionConflict conflict) {
      return problem(response, 409, "BRANDING_VERSION_CONFLICT");
    } catch (BrandingService.InvalidBranding invalid) {
      return problem(response, 400, "INVALID_BRANDING", invalid.getMessage());
    }
  }

  /** 平台按历史版本回滚：复制成新版本，不改写旧版本。 */
  @PostMapping("/api/platform/tenants/{id}/branding/rollback")
  ResponseEntity<?> rollbackTenantBranding(
      @PathVariable UUID id,
      @RequestBody RollbackBranding body,
      org.springframework.security.core.Authentication authentication,
      HttpServletResponse response) {
    var operator = operator(authentication);
    if (!operator.actions().contains("TENANT_BRANDING_WRITE"))
      return ResponseEntity.status(403).build();
    if (provisioning.tenant(id).isEmpty()) return problem(response, 404, "TENANT_NOT_FOUND");
    if (body == null || body.version() == null) return problem(response, 400, "INVALID_VERSION");
    try {
      return ResponseEntity.ok(branding.rollbackPlatform(id, operator.id(), body.version()));
    } catch (BrandingService.InvalidBranding invalid) {
      return problem(response, 400, "INVALID_BRANDING", invalid.getMessage());
    }
  }

  private static String randomPassword() {
    var alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    var random = new java.security.SecureRandom();
    var text = new StringBuilder(16);
    for (int index = 0; index < 16; index += 1)
      text.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return text.toString();
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
