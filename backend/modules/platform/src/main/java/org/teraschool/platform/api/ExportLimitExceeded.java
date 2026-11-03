package org.teraschool.platform.api;

/** 同步导出超限时明确失败，不能返回被静默截断的“完整文件”。 */
public class ExportLimitExceeded extends RuntimeException {
  public ExportLimitExceeded() {
    super("EXPORT_LIMIT_EXCEEDED");
  }
}
