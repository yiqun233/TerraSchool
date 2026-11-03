package org.teraschool.platform.api;

/** CSV 表示和同步行数边界；领域授权、字段脱敏和审计仍由各应用服务负责。 */
public final class CsvExport {
  public static final int MAX_ROWS = 5000;

  private CsvExport() {}

  public static void requireComplete(int fetchedRows) {
    if (fetchedRows > MAX_ROWS) throw new ExportLimitExceeded();
  }

  public static String cell(String value) {
    if (value == null) return "";
    var leading = value.stripLeading();
    // 引号本身不阻止表格软件执行公式。合法十进制负数保留数字表示，其他公式前缀转文本。
    if (!leading.isEmpty()
        && "=+-@".indexOf(leading.charAt(0)) >= 0
        && !value.matches("-?\\d+(\\.\\d+)?")) value = "'" + value;
    var escaped = value.replace("\"", "\"\"");
    return escaped.indexOf(',') >= 0
            || escaped.indexOf('"') >= 0
            || escaped.indexOf('\n') >= 0
            || escaped.indexOf('\r') >= 0
        ? "\"" + escaped + "\""
        : escaped;
  }
}
