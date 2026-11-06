package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** SQL 字面量补充门禁：已登记的跨域只读查询不得扩张，也不得变成直接写表。动态 SQL 仍需评审与数据库验证。 */
class SqlOwnershipTest {
  static final Map<String, String> SHARED_OWNERS =
      Map.ofEntries(
          Map.entry("audit.student_event", "archive"),
          Map.entry("audit.product_event", "product"),
          Map.entry("audit.enrollment_event", "enrollment"),
          Map.entry("audit.funds_event", "funds"),
          Map.entry("audit.teaching_event", "teaching"),
          Map.entry("audit.approval_event", "approval"),
          Map.entry("audit.period_event", "finance"),
          Map.entry("audit.channel_event", "admissions"),
          Map.entry("audit.customer_event", "admissions"),
          Map.entry("audit.authorization_event", "identity"),
          Map.entry("audit.role_template_event", "identity"),
          Map.entry("audit.account_lifecycle_event", "identity"),
          Map.entry("audit.resource_event", "organization"),
          Map.entry("audit.business_event", "platform"),
          Map.entry("audit.operation_failure", "platform"),
          Map.entry("infra.mail_key_verifier", "organization"));
  static final Map<String, Set<String>> READ_EXCEPTIONS =
      Map.ofEntries(
          Map.entry(
              "enrollment/EnrollmentService.java", Set.of("archive.student", "product.curriculum")),
          Map.entry(
              "funds/FundsService.java",
              Set.of("archive.student", "enrollment.hours_cancellation")),
          Map.entry(
              "finance/FundsReconciliationService.java",
              Set.of(
                  "archive.student",
                  "funds.account_entry",
                  "funds.allocation",
                  "funds.receipt",
                  // 每日概况只读聚合；入口和机构锁内均复检报名/资金/教务的同校交集。
                  "enrollment.enrollment",
                  "teaching.class",
                  "teaching.lesson",
                  "teaching.attendance")),
          Map.entry("identity/AccountAuthorizations.java", Set.of("organization.unit")),
          Map.entry("identity/SchoolProvisioner.java", Set.of("organization.unit")),
          Map.entry(
              "migration/LegacyMigrationService.java",
              Set.of(
                  "archive.student",
                  "enrollment.enrollment",
                  "funds.account_entry",
                  "funds.creation_request",
                  "funds.receipt",
                  "teaching.consumption")),
          Map.entry("organization/EmployeeAccountLinkService.java", Set.of("identity.account")),
          Map.entry(
              "teaching/SchedulingService.java",
              Set.of("organization.room", "organization.teacher")),
          Map.entry(
              "teaching/TeachingLookups.java",
              Set.of(
                  // 班级行读取时联表取课程名；与拆分前 TeachingService 读的是同一张表。
                  "product.curriculum")),
          Map.entry(
              "teaching/TeachingReporting.java",
              Set.of(
                  // 教师课表/资源日报沿用与写路径相同的资源与课程只读投影。
                  "organization.employee",
                  "organization.room",
                  "organization.teacher",
                  "product.curriculum")),
          Map.entry(
              "teaching/TeachingService.java",
              // organization.employee 的读取随教师课表搬到 TeachingReporting；
              // 跨域读取的总集不变，只是分散到两个文件。
              Set.of(
                  "enrollment.hours_cancellation",
                  "organization.room",
                  "organization.teacher",
                  "product.curriculum")));
  static final Pattern LITERAL = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"");

  /**
   * 按模块聚合的跨域只读集合。
   *
   * <p>拆分文件会让“逐文件快照”（{@link #READ_EXCEPTIONS}）变化——同一个表换个文件读，两张快照都要改，
   * 只看逐文件清单等于允许“新增文件 + 复制白名单”这种无感扩张。这里把模块能读到的外部表显式登记一次，
   * 拆分可以动文件归属，但模块的可读范围不能增加。
   */
  static final Map<String, Set<String>> MODULE_READ_UNION =
      Map.ofEntries(
          Map.entry("enrollment", Set.of("archive.student", "product.curriculum")),
          Map.entry("funds", Set.of("archive.student", "enrollment.hours_cancellation")),
          Map.entry(
              "finance",
              Set.of(
                  "archive.student",
                  "funds.account_entry",
                  "funds.allocation",
                  "funds.receipt",
                  "enrollment.enrollment",
                  "teaching.class",
                  "teaching.lesson",
                  "teaching.attendance")),
          Map.entry("identity", Set.of("organization.unit")),
          Map.entry(
              "migration",
              Set.of(
                  "archive.student",
                  "enrollment.enrollment",
                  "funds.account_entry",
                  "funds.creation_request",
                  "funds.receipt",
                  "teaching.consumption")),
          Map.entry("organization", Set.of("identity.account")),
          Map.entry(
              "teaching",
              Set.of(
                  "enrollment.hours_cancellation",
                  "organization.employee",
                  "organization.room",
                  "organization.teacher",
                  "product.curriculum")));

  @Test
  void literalSqlUsesOwnedTablesAndRegisteredReadExceptions() throws Exception {
    Path backend = Path.of("").toAbsolutePath();
    while (backend != null && !Files.isDirectory(backend.resolve("modules/platform/src")))
      backend = backend.getParent();
    assertThat(backend).as("定位正式后端模块").isNotNull();
    var modules = backend.resolve("modules");
    var moduleNames = new TreeSet<String>();
    try (var children = Files.list(modules)) {
      children
          .filter(path -> Files.isRegularFile(path.resolve("pom.xml")))
          .forEach(path -> moduleNames.add(path.getFileName().toString()));
    }
    assertThat(moduleNames).isNotEmpty();
    var schemas = new TreeSet<>(moduleNames);
    schemas.addAll(List.of("audit", "infra"));
    var reference = tablePattern(schemas, false);
    var mutation = tablePattern(schemas, true);
    var observed = new TreeMap<String, Set<String>>();
    for (var module : moduleNames) {
      try (var files = Files.walk(modules.resolve(module).resolve("src/main/java"))) {
        for (var path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          var sql = literalSql(Files.readString(path));
          var key = module + "/" + path.getFileName();
          var writes = mutation.matcher(sql);
          while (writes.find()) {
            var table = (writes.group(1) + "." + writes.group(2)).toLowerCase(Locale.ROOT);
            assertThat(owner(table)).as("%s 不得直接写 %s", key, table).isEqualTo(module);
          }
          var reads = reference.matcher(sql);
          while (reads.find()) {
            var table = (reads.group(1) + "." + reads.group(2)).toLowerCase(Locale.ROOT);
            if (!module.equals(owner(table)))
              observed.computeIfAbsent(key, ignored -> new TreeSet<>()).add(table);
          }
        }
      }
    }
    assertThat(observed).as("跨域 SQL 清单变化必须同步公开契约或只读例外评审及架构文档").isEqualTo(READ_EXCEPTIONS);

    var moduleUnion = new TreeMap<String, Set<String>>();
    observed.forEach(
        (key, tables) ->
            moduleUnion
                .computeIfAbsent(key.substring(0, key.indexOf('/')), ignored -> new TreeSet<>())
                .addAll(tables));
    assertThat(moduleUnion).as("模块可读的跨域表不得因拆分或新增文件而扩张").isEqualTo(MODULE_READ_UNION);
  }

  static String owner(String table) {
    var schema = table.substring(0, table.indexOf('.'));
    if (schema.equals("audit") || schema.equals("infra")) {
      assertThat(SHARED_OWNERS).as("共享 schema 的每张引用表必须登记唯一所有者：%s", table).containsKey(table);
      return SHARED_OWNERS.get(table);
    }
    return schema;
  }

  /** 折叠相邻常量拼接；不同表达式用分号隔开，含变量/反射的 SQL 不伪装成已静态解析。 */
  static String literalSql(String source) {
    // Java Unicode 转义先于字符串词法处理；translateEscapes 本身不接受该类转义。
    source =
        Pattern.compile("\\\\u+([0-9a-fA-F]{4})")
            .matcher(source)
            .replaceAll(
                match ->
                    java.util.regex.Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(match.group(1), 16))));
    var literals = LITERAL.matcher(source);
    var result = new StringBuilder();
    int previousEnd = -1;
    while (literals.find()) {
      if (previousEnd < 0
          || !source.substring(previousEnd, literals.start()).matches("\\s*\\+\\s*"))
        result.append("\n;\n");
      var value = literals.group();
      int quotes = value.startsWith("\"\"\"") ? 3 : 1;
      result.append(value.substring(quotes, value.length() - quotes).translateEscapes());
      previousEnd = literals.end();
    }
    return result.toString();
  }

  static Pattern tablePattern(Set<String> schemas, boolean write) {
    var qualified = "(" + String.join("|", schemas) + ")\\.([a-z_]+)";
    var verb =
        write
            ? "(?:insert\\s+into|update|delete\\s+from|merge\\s+into|truncate(?:\\s+table)?)\\s+"
            : "";
    return Pattern.compile("(?i)\\b" + verb + qualified);
  }

  @Test
  void constantConcatenationCannotHideCrossDomainMutation() {
    for (var source :
        List.of(
            "sql.execute(\"update archive.student set name=?\")",
            "sql.execute(\"update \" + \"archive.\" + \"student set name=?\")",
            "sql.execute(\"DELETE FROM \" + \"ARCHIVE.STUDENT where id=?\")",
            "sql.execute(\"upda\\u0074e archive.student set name=?\")",
            "sql.execute(\"\"\"\ninsert into archive.student(id) values (?)\n\"\"\")")) {
      var sql = literalSql(source);
      assertThat(tablePattern(Set.of("archive"), true).matcher(sql).find()).as(source).isTrue();
      var reference = tablePattern(Set.of("archive"), false).matcher(sql);
      assertThat(reference.find()).as(source).isTrue();
      assertThat((reference.group(1) + "." + reference.group(2)).toLowerCase(Locale.ROOT))
          .isEqualTo("archive.student");
    }
    assertThat(literalSql("String separator = \"\\u001f\";")).contains("\u001f");
    assertThat(literalSql("sql.execute(\"update \" + dynamicTable); other(\"archive.student\");"))
        .doesNotContain("update archive.student");
  }
}
