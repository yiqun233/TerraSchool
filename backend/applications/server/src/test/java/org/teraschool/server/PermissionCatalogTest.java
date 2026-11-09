package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.teraschool.identity.api.PermissionCatalog;

/**
 * 动作目录一致性门禁。
 *
 * <p>过去目录靠手写字符串，已经三次与业务校验脱节：REFUND_*、ENROLLMENT_DISCOUNT、PERIOD_UNLOCK。
 * 脱节的后果不是“少一个选项”，而是业务代码照常校验、权限却永远授不出去，界面上表现为功能对所有人不可用。 这里把目录与代码的实际校验点绑在一起，任何一侧单独变化都会失败。
 */
class PermissionCatalogTest {
  /** 以字面量传入动作的校验入口。 */
  static final Pattern ACTION_CALL =
      Pattern.compile(
          "\\b(?:requireAction|requireActionInTransaction|hasAction|schoolsOf)\\s*\\(\\s*\"([A-Z][A-Z0-9_]+)\"");

  /** 以字面量传入资源种类的入口；资源动作由 `<KIND>_READ`/`_MAINTAIN`/`_SENSITIVE_READ` 派生。 */
  static final Pattern RESOURCE_CALL =
      Pattern.compile(
          "\\b(?:requireResource|requireResourceInTransaction|requireResourceScope|requireResourceScopeInTransaction)"
              + "\\s*\\(\\s*\"([A-Z][A-Z0-9_]+)\"");

  /**
   * 没有字面量调用点、但确实生效的动作，逐条写明原因。
   *
   * <p>新增条目必须同时说明为什么不经过字符串校验，否则应改为在业务侧按动作校验。
   */
  static final Map<String, String> NO_LITERAL_CALL =
      Map.ofEntries(
          Map.entry("ORGANIZATION_READ", "由 PermissionRules 的四个布尔统一计算，供组织查询使用"),
          Map.entry("ORGANIZATION_MAINTAIN", "由 PermissionRules 的四个布尔统一计算，供组织写入使用"),
          Map.entry("AUTHORIZATION_MANAGE", "由 PermissionRules 的四个布尔统一计算，供授权管理入口使用"),
          Map.entry("TENANT_BRANDING_WRITE", "由 CanWriteBranding 计算，供品牌写入使用"),
          Map.entry("ROOM_READ", "资源枚举 Kind.ROOM 通过 kind.name() 传入，没有字面量调用点"),
          Map.entry("ROOM_MAINTAIN", "资源枚举 Kind.ROOM 通过 kind.name() 传入，没有字面量调用点"));

  /**
   * 目录里存在、但当前没有任何校验入口的动作。
   *
   * <p>这些动作在界面上可配置，却不会影响任何操作；出现或消失都必须同步业务决策与文档， 不能悄悄新增。
   */
  static final Set<String> UNENFORCED = Set.of("REFUND_READ");

  static Path backendRoot() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isDirectory(path.resolve("modules/platform/src")))
      path = path.getParent();
    assertThat(path).as("定位正式后端模块").isNotNull();
    return path;
  }

  @Test
  void catalogEntriesAreCompleteAndUnique() {
    var codes = new TreeSet<String>();
    for (var action : PermissionCatalog.ACTIONS) {
      assertThat(codes.add(action.code())).as("动作码重复：%s", action.code()).isTrue();
      assertThat(action.code()).as("动作码格式").matches("[A-Z][A-Z0-9_]{3,60}");
      assertThat(action.group()).as("%s 缺少分组", action.code()).isNotBlank();
      assertThat(action.label()).as("%s 缺少中文名", action.code()).isNotBlank();
      assertThat(action.description()).as("%s 缺少说明", action.code()).isNotBlank();
      assertThat(action.pages()).as("%s 缺少对应页面", action.code()).isNotEmpty();
      assertThat(action.scope()).as("%s 缺少范围类型", action.code()).isNotNull();
      assertThat(action.risk()).as("%s 缺少风险级别", action.code()).isNotNull();
    }
    assertThat(PermissionCatalog.CODES).isEqualTo(codes);
    // 分组顺序即界面顺序；同一分组必须连续，否则界面会重复出现同名分组。
    var groups = new java.util.LinkedHashSet<String>();
    for (var action : PermissionCatalog.ACTIONS) groups.add(action.group());
    var seen = new java.util.HashSet<String>();
    String previous = null;
    for (var action : PermissionCatalog.ACTIONS) {
      if (!action.group().equals(previous)) {
        assertThat(seen.add(action.group())).as("分组 %s 不连续", action.group()).isTrue();
        previous = action.group();
      }
    }
  }

  @Test
  void businessCodeOnlyChecksCatalogActions() throws Exception {
    var unknown = new TreeSet<String>();
    for (var action : literalActions(backendRoot()))
      if (!PermissionCatalog.CODES.contains(action)) unknown.add(action);
    assertThat(unknown).as("业务代码校验了目录外的动作；授权接口会以“无效权限规则”拒绝，导致该权限永远授不出去").isEmpty();
  }

  @Test
  void everyCatalogActionHasAnEnforcementPoint() throws Exception {
    var root = backendRoot();
    var enforced = new TreeSet<>(literalActions(root));
    for (var kind : literalResourceKinds(root))
      for (var suffix : List.of("_READ", "_MAINTAIN", "_SENSITIVE_READ"))
        enforced.add(kind + suffix);
    var unenforced = new TreeSet<String>();
    for (var action : PermissionCatalog.ACTIONS) {
      if (enforced.contains(action.code()) || NO_LITERAL_CALL.containsKey(action.code())) continue;
      unenforced.add(action.code());
    }
    assertThat(unenforced)
        .as("目录里存在没有校验入口的动作；要么接入校验，要么登记到 NO_LITERAL_CALL/UNENFORCED")
        .isEqualTo(new TreeSet<>(UNENFORCED));
  }

  static Set<String> literalActions(Path backend) throws Exception {
    var result = new TreeSet<String>();
    for (var source : sources(backend)) {
      var matcher = ACTION_CALL.matcher(Files.readString(source));
      while (matcher.find()) result.add(matcher.group(1));
    }
    return result;
  }

  static Set<String> literalResourceKinds(Path backend) throws Exception {
    var result = new TreeSet<String>();
    for (var source : sources(backend)) {
      var matcher = RESOURCE_CALL.matcher(Files.readString(source));
      while (matcher.find()) result.add(matcher.group(1));
    }
    return result;
  }

  static List<Path> sources(Path backend) throws Exception {
    var result = new java.util.ArrayList<Path>();
    for (var base :
        List.of(backend.resolve("modules"), backend.resolve("applications/server/src/main/java"))) {
      if (!Files.isDirectory(base)) continue;
      try (var files = Files.walk(base)) {
        files
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> !path.toString().contains("target"))
            .filter(path -> !path.toString().contains("src" + java.io.File.separator + "test"))
            .forEach(result::add);
      }
    }
    assertThat(result).as("未找到业务源码").isNotEmpty();
    return result;
  }
}
