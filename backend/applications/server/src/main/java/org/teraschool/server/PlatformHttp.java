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

  PlatformHttp(PlatformIdentity identity) {
    this.identity = identity;
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
