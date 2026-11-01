package org.teraschool.identity.internal;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.AccountLinking;
import org.teraschool.identity.api.Actor;

@Service
public class AccountLinkCoordinator implements AccountLinking {
  private final AccountAuthorizations authorizations;

  public AccountLinkCoordinator(AccountAuthorizations authorizations) {
    this.authorizations = authorizations;
  }

  public <T> T withAccount(UUID accountId, Function<Actor, T> work) {
    return authorizations.managed(
        actor -> {
          if (accountId != null) authorizations.boundary(actor, authorizations.load(accountId));
          return work.apply(
              new Actor(actor.accountId(), actor.tenantId(), actor.manageableSchoolIds()));
        });
  }
}
