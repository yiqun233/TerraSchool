package org.teraschool.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import java.nio.file.Path;
import java.util.List;

/** 使用内置、无外部资源的 HTML 表格和匹配版本 Chromium 生成 PDF。 */
final class ExportPdf {
  private ExportPdf() {}

  static byte[] fromCsv(String csv) {
    return fromCsv(csv, null);
  }

  static byte[] fromCsv(String csv, String executablePath) {
    String html = html(csv);
    try (Playwright playwright = Playwright.create()) {
      var launchOptions = new BrowserType.LaunchOptions();
      if (executablePath != null && !executablePath.isBlank())
        launchOptions.setExecutablePath(Path.of(executablePath));
      try (Browser browser = playwright.chromium().launch(launchOptions);
          BrowserContext context =
              browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false));
          Page page = context.newPage()) {
        page.setDefaultTimeout(15_000);
        page.setContent(
            html,
            new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(15_000));
        return page.pdf(
            new Page.PdfOptions()
                .setFormat("A4")
                .setPrintBackground(true)
                .setPreferCSSPageSize(true));
      }
    } catch (Exception failure) {
      throw new IllegalStateException("PDF 渲染失败，请检查 Playwright Chromium 与运行依赖已安装", failure);
    }
  }

  static String html(String csv) {
    List<List<String>> rows = ExportWorkbook.parseRows(csv);
    var html = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">");
    html.append(
        "<style>@page{size:A4;margin:12mm}body{font:10pt sans-serif;color:#111}table{border-collapse:collapse;width:100%;table-layout:auto}thead{display:table-header-group}tr{break-inside:avoid}th,td{border:1px solid #bbb;padding:4px 6px;overflow-wrap:anywhere;text-align:left}th{background:#eee}</style><table><thead>");
    if (!rows.isEmpty()) {
      html.append("<tr>");
      for (String value : rows.getFirst())
        html.append("<th>").append(escape(value)).append("</th>");
      html.append("</tr></thead><tbody>");
      for (int index = 1; index < rows.size(); index++) {
        html.append("<tr>");
        for (String value : rows.get(index))
          html.append("<td>").append(escape(value)).append("</td>");
        html.append("</tr>");
      }
    } else html.append("</thead><tbody>");
    return html.append("</tbody></table></html>").toString();
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
