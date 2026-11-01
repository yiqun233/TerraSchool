package org.teraschool.server;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.identity.api.AccountLifecycle;
import org.teraschool.identity.api.AccountLifecycle.*;

@RestController
@RequestMapping(value = "/api/authorization/account-lifecycle", produces = "application/json")
class AccountLifecycleHttp {
  private final AccountLifecycle accounts;

  AccountLifecycleHttp(AccountLifecycle accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/{id}")
  AccountView get(@PathVariable UUID id) {
    return accounts.getAccount(id);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  AccountView create(@RequestBody CreateAccount input) {
    return accounts.createAccount(input);
  }

  @PostMapping(value = "/{id}/status", consumes = "application/json")
  AccountView status(@PathVariable UUID id, @RequestBody ChangeAccountStatus input) {
    return accounts.changeStatus(id, input);
  }

  @PostMapping(value = "/{id}/reset-password", consumes = "application/json")
  AccountView password(@PathVariable UUID id, @RequestBody ResetAccountPassword input) {
    return accounts.resetPassword(id, input);
  }

  @PostMapping(value = "/{id}/revoke-sessions", consumes = "application/json")
  AccountView revoke(@PathVariable UUID id, @RequestBody RevokeAccountSessions input) {
    return accounts.revokeSessions(id, input);
  }
}
