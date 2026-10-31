package org.teraschool.server;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.SecurityFilterChain;

/** Cookie 会话与默认 CSRF；登录失败和受限入口返回明确 HTTP 状态。 */
@Configuration
class WebSecurity {
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
                    .failureHandler((q, s, e) -> problem(s, 401, "AUTHENTICATION_FAILED")))
        .logout(
            l ->
                l.logoutUrl("/api/session/logout")
                    .logoutSuccessHandler((q, s, a) -> s.setStatus(204)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((q, s, x) -> problem(s, 401, "AUTHENTICATION_REQUIRED"))
                    .accessDeniedHandler((q, s, x) -> problem(s, 403, "ACCESS_DENIED")))
        .build();
  }

  private void problem(jakarta.servlet.http.HttpServletResponse res, int status, String code)
      throws java.io.IOException {
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
                + "\"}");
  }
}
