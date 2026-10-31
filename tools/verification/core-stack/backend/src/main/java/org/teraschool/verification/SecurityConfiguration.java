package org.teraschool.verification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** 固定测试身份只验证框架边界，不作为正式账号或权限模型实现。 */
@Configuration
class SecurityConfiguration {
    @Bean
    UserDetailsService users() {
        String password = "{noop}" + DataSourceConfiguration.required("PROBE_FIXTURE_PASSWORD");
        return new InMemoryUserDetailsManager(
            User.withUsername("writer-a").password(password).roles("WRITER").build(),
            User.withUsername("reader-a").password(password).roles("READER").build(),
            User.withUsername("writer-b").password(password).roles("WRITER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(r -> r.requestMatchers("/probe/csrf", "/error").permitAll().anyRequest().authenticated())
            .formLogin(Customizer.withDefaults())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
            // 保持默认 CSRF 防护；测试登录和写请求必须携带实际 token。
            .build();
    }
}
