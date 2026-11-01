package org.teraschool.server;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.teraschool.platform.api.RequestTrace;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws java.io.IOException, ServletException {
    try (var scope = RequestTrace.open(RequestTrace.Source.HTTP)) {
      response.setHeader("X-Trace-Id", RequestTrace.id().toString());
      chain.doFilter(request, response);
    }
  }
}
