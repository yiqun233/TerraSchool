package org.teraschool.identity.internal;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** principal只含稳定账号及认证版本；V8前会话按版本0兼容。 */
record SessionPrincipal(UUID account, long version) {
  static SessionPrincipal current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
      throw new AccessDeniedException("需要有效身份");
    try {
      String[] parts = auth.getName().split("~", -1);
      if (parts.length < 1 || parts.length > 2) throw new IllegalArgumentException();
      long version = parts.length == 1 ? 0 : Long.parseLong(parts[1]);
      if (version < 0) throw new IllegalArgumentException();
      return new SessionPrincipal(UUID.fromString(parts[0]), version);
    } catch (IllegalArgumentException invalid) {
      throw new AccessDeniedException("需要有效身份");
    }
  }
}
