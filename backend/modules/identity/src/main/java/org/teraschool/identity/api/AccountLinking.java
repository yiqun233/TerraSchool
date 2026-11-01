package org.teraschool.identity.api;

import java.util.UUID;
import java.util.function.Function;

/** 在授权锁内验证账号委派范围，供员工关系用例使用。 */
public interface AccountLinking {
  <T> T withAccount(UUID accountId, Function<Actor, T> work);
}
