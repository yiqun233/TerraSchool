package org.teraschool.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * 字段级读取授权。
 *
 * <p>功能动作决定"能不能进这个页面"，字段授权决定"这个页面里哪些敏感字段能看见"。 返回的是该账号在该学校被显式允许的字段键集合；未列出即视为无权，调用方据此脱敏。
 */
public interface FieldAccess {
  Set<String> readableFields(UUID accountId, UUID schoolId);

  /** 显式拒绝的字段；DENY 优先于 ALLOW，也优先于"敏感字段全读"的动作。 */
  Set<String> deniedFields(UUID accountId, UUID schoolId);
}
