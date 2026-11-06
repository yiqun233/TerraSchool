package org.teraschool.server;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * 平台入口的独立安全链：先于租户链匹配 {@code /api/platform/**}。
 *
 * <p>关键点是“两条链互不认对方的身份”：平台链只接受平台 Cookie，租户链完全不匹配平台路径。 因此租户会话访问平台接口、平台会话访问业务接口都会得到 401，而不是靠控制器里再判断一次。
 */
@Configuration
class PlatformSecurity {
  private final PlatformIdentity identity;

  PlatformSecurity(PlatformIdentity identity) {
    this.identity = identity;
  }

  @Bean
  @Order(1)
  SecurityFilterChain platformChain(HttpSecurity http) throws Exception {
    // 与租户链分开的 CSRF Cookie/请求头，避免两套会话混用同一个 token。
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieName("XSRF-PLATFORM");
    csrf.setHeaderName("X-XSRF-PLATFORM");
    return http.securityMatcher("/api/platform/**")
        .csrf(c -> c.csrfTokenRepository(csrf))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/platform/session/csrf", "/api/platform/session/login")
                    .permitAll()
                    .anyRequest()
                    .hasRole("PLATFORM"))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, s, x) -> write(s, 401, "PLATFORM_AUTHENTICATION_REQUIRED"))
                    .accessDeniedHandler((q, s, x) -> write(s, 403, "PLATFORM_ACCESS_DENIED")))
        .addFilterBefore(
            new PlatformSessionFilter(identity), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /** 平台侧失败同样用 RFC 9457；不写租户审计表（那是租户域的数据）。 */
  static void write(HttpServletResponse response, int status, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"type\":\"urn:teraschool:error:"
                + code.toLowerCase()
                + "\",\"title\":\""
                + code
                + "\",\"status\":"
                + status
                + ",\"code\":\""
                + code
                + "\",\"traceId\":\""
                + org.teraschool.platform.api.RequestTrace.id()
                + "\"}");
  }
}
