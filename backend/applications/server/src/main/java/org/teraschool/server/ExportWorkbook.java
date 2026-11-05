package org.teraschool.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 将受限 CSV 快照转换为纯文本单工作表 XLSX，避免把导出值解释为公式。 */
final class ExportWorkbook {
  private ExportWorkbook() {}

  static byte[] fromCsv(String csv) {
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("导出数据");
      var rows = parse(csv);
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        var row = sheet.createRow(rowIndex);
        var values = rows.get(rowIndex);
        for (int cellIndex = 0; cellIndex < values.size(); cellIndex++) {
          // setCellValue(String) creates string cells; values beginning with '=' stay inert text.
          row.createCell(cellIndex).setCellValue(xmlSafe(values.get(cellIndex)));
        }
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException failure) {
      throw new IllegalStateException("无法生成 XLSX 文件", failure);
    }
  }

  static int dataRowCount(String csv) {
    return Math.max(0, parse(csv).size() - 1);
  }

  private static String xmlSafe(String value) {
    return value
        .codePoints()
        .filter(
            codePoint ->
                codePoint == 0x9
                    || codePoint == 0xA
                    || codePoint == 0xD
                    || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                    || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                    || (codePoint >= 0x10000 && codePoint <= 0x10FFFF))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static List<List<String>> parse(String csv) {
    var rows = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < csv.length(); i++) {
      char current = csv.charAt(i);
      if (quoted) {
        if (current == '"') {
          if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cell.append(current);
        }
      } else if (current == '"' && cell.isEmpty()) {
        quoted = true;
      } else if (current == ',') {
        row.add(cell.toString());
        cell.setLength(0);
      } else if (current == '\n' || current == '\r') {
        row.add(cell.toString());
        cell.setLength(0);
        rows.add(List.copyOf(row));
        row.clear();
        if (current == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
      } else {
        cell.append(current);
      }
    }
    if (quoted) throw new IllegalArgumentException("CSV 存在未闭合引号");
    if (!row.isEmpty() || !cell.isEmpty()) {
      row.add(cell.toString());
      rows.add(List.copyOf(row));
    }
    return rows;
  }
}
