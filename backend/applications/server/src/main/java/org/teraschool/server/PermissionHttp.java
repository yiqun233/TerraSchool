package org.teraschool.server;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.teraschool.identity.api.ActionGrant;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.CurrentPermissions;
import org.teraschool.identity.api.PermissionCatalog;

/**
 * 动作目录与本人有效权限的只读入口。
 *
 * <p>目录不含任何账号数据，登录即可读；本人结果只返回当前会话账号，不接受目标账号参数。 两个接口都不授予操作能力，业务写入仍按动作逐次校验。
 */
@RestController
@RequestMapping(value = "/api/permissions", produces = "application/json")
class PermissionHttp {
  private final CurrentActor actors;

  PermissionHttp(CurrentActor actors) {
    this.actors = actors;
  }

  public record Mine(CurrentPermissions current, List<ActionGrant> actions) {}

  @GetMapping("/catalog")
  List<PermissionCatalog.Action> catalog() {
    return PermissionCatalog.ACTIONS;
  }

  @GetMapping("/mine")
  Mine mine() {
    return new Mine(actors.permissions(), actors.actionGrants());
  }
}
