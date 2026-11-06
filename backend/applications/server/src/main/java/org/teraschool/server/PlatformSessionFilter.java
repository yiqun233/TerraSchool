package org.teraschool.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 平台会话认证：只认 {@code TS_PLATFORM_SESSION} Cookie，不读租户会话。
 *
 * <p>这样两个身份体系在过滤层就分开了：租户 Cookie 换不来平台身份，反之亦然。
 */
class PlatformSessionFilter extends OncePerRequestFilter {
  private final PlatformIdentity identity;

  PlatformSessionFilter(PlatformIdentity identity) {
    this.identity = identity;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var token = token(request);
    if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
      identity
          .resolve(token)
          .ifPresent(
              operator -> {
                var authentication =
                    new UsernamePasswordAuthenticationToken(
                        operator, token, List.of(new SimpleGrantedAuthority("ROLE_PLATFORM")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
              });
    }
    chain.doFilter(request, response);
  }

  static String token(HttpServletRequest request) {
    var cookies = request.getCookies();
    if (cookies == null) return null;
    for (var cookie : cookies)
      if (PlatformIdentity.COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank())
        return cookie.getValue();
    return null;
  }
}
