package org.teraschool.server;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 本人改密入口：首次登录强制改密也走这里。 */
@RestController
class SessionPasswordHttp {
  record ChangePassword(String currentPassword, String newPassword) {}

  private final AccountPassword password;

  SessionPasswordHttp(AccountPassword password) {
    this.password = password;
  }

  @PostMapping("/api/session/password")
  ResponseEntity<Void> change(@RequestBody ChangePassword body) {
    if (body == null) throw new IllegalArgumentException("需要当前密码与新密码");
    password.change(body.currentPassword(), body.newPassword());
    // 改密会递增凭据版本，当前会话因此失效；客户端应跳回登录页用新密码登录。
    return ResponseEntity.noContent().build();
  }
}
