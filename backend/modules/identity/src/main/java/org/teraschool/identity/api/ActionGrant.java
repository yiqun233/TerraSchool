package org.teraschool.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * 当前账号对某个动作的有效结果，用于“我的权限”只读呈现。
 *
 * <p>只列出功能已开启的动作；未开启的动作由目录补“未开通”，不在此重复。 {@code schoolIds} 是学校 ALLOW 减去学校 DENY 之后的结果，{@code
 * functionalOnly} 表示只有功能级 ALLOW、没有学校范围， 界面必须显示为“仅功能（无学校范围）”，不能呈现成“有数据权限”。
 */
public record ActionGrant(String action, boolean functionalOnly, Set<UUID> schoolIds) {
  public ActionGrant {
    schoolIds = Set.copyOf(schoolIds);
  }
}
