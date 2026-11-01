package org.teraschool.server;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.teraschool.identity.api.AuthorizationManagement;
import org.teraschool.identity.api.AuthorizationManagement.*;

/** 传入的账号只是目标；操作者与机构始终由服务端当前会话确定。 */
@RestController
@RequestMapping(value = "/api/authorization", produces = "application/json")
class AuthorizationHttp {
  private final AuthorizationManagement authorization;

  AuthorizationHttp(AuthorizationManagement authorization) {
    this.authorization = authorization;
  }

  @GetMapping("/accounts")
  List<Option> accounts(@RequestParam(defaultValue = "") String search) {
    return authorization.accounts(search);
  }

  @GetMapping("/schools")
  List<Option> schools(@RequestParam(defaultValue = "") String search) {
    return authorization.schools(search);
  }

  @GetMapping("/roles")
  List<RoleOption> roles(@RequestParam(defaultValue = "") String search) {
    return authorization.roles(search);
  }

  @GetMapping("/accounts/{id}")
  AccountAuthorization get(@PathVariable UUID id) {
    return authorization.get(id);
  }

  @PostMapping(value = "/accounts/{id}/replace", consumes = "application/json")
  AccountAuthorization replace(@PathVariable UUID id, @RequestBody Replacement command) {
    return authorization.replace(id, command);
  }
}
