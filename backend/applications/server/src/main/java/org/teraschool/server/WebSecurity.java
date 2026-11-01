package org.teraschool.server;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.SecurityFilterChain;

/** Cookie 会话与默认 CSRF；登录失败和受限入口返回明确 HTTP 状态。 */
@Configuration
class WebSecurity {
  private final RejectedRequestAudit audit;
  private final org.teraschool.identity.api.CurrentActor actors;

  WebSecurity(RejectedRequestAudit audit, org.teraschool.identity.api.CurrentActor actors) {
    this.audit = audit;
    this.actors = actors;
  }

  @Bean
  PasswordEncoder encoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/session/csrf", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/session/login")
                    .successHandler((q, s, a) -> s.setStatus(204))
                    .failureHandler((q, s, e) -> problem(q, s, 401, "AUTHENTICATION_FAILED")))
        .logout(
            l ->
                l.logoutUrl("/api/session/logout")
                    .logoutSuccessHandler((q, s, a) -> s.setStatus(204)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, s, x) -> problem(q, s, 401, "AUTHENTICATION_REQUIRED"))
                    .accessDeniedHandler((q, s, x) -> problem(q, s, 403, "ACCESS_DENIED")))
        .addFilterAfter(
            new org.springframework.web.filter.OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  jakarta.servlet.http.HttpServletRequest request,
                  jakarta.servlet.http.HttpServletResponse response,
                  jakarta.servlet.FilterChain chain)
                  throws java.io.IOException, jakarta.servlet.ServletException {
                var authentication =
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
                if (authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication
                        instanceof
                        org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                  try {
                    actors.permissions();
                  } catch (org.springframework.security.access.AccessDeniedException invalid) {
                    problem(request, response, 401, "AUTHENTICATION_REQUIRED");
                    var session = request.getSession(false);
                    if (session != null) session.invalidate();
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    return;
                  }
                }
                chain.doFilter(request, response);
              }
            },
            org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
        .build();
  }

  private void problem(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse res,
      int status,
      String code)
      throws java.io.IOException {
    try {
      audit.record(request, code);
    } catch (org.teraschool.platform.api.AuditUnavailable unavailable) {
      status = 503;
      code = "AUDIT_UNAVAILABLE";
    }
    res.setStatus(status);
    res.setContentType("application/problem+json");
    res.getWriter()
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
