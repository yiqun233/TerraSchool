package org.teraschool.verification;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 只根据已认证测试身份映射租户；请求体、请求头不能自行指定租户或权限。 */
@Component
class TrustedActor {
    static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    UUID tenant(boolean writing) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("缺少可信身份");
        UUID tenant = switch (auth.getName()) {
            case "writer-a", "reader-a" -> TENANT_A;
            case "writer-b" -> TENANT_B;
            default -> throw new AccessDeniedException("未知测试身份");
        };
        if (writing && auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_WRITER")))
            throw new AccessDeniedException("无写入权限");
        return tenant;
    }
}
