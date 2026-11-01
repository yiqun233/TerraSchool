package org.teraschool.archive.internal;

/** 敏感字段脱敏规则；只用于返回展示，不改变数据库中的权威值。 */
final class Masking {
  private Masking() {}

  static String phone(String value) {
    if (value == null || value.isBlank()) return value;
    String digits = value.strip();
    if (digits.length() <= 4) return "***";
    if (digits.length() <= 8)
      return digits.charAt(0) + "***" + digits.substring(digits.length() - 2);
    return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
  }

  /** 证件号只保留后四位，位数本身也做遮蔽，避免据此推算发证地。 */
  static String certificate(String value) {
    if (value == null || value.isBlank()) return value;
    String text = value.strip();
    if (text.length() <= 4) return "****";
    return "****" + text.substring(text.length() - 4);
  }

  static String email(String value) {
    if (value == null || value.isBlank()) return value;
    String text = value.strip();
    int at = text.indexOf('@');
    if (at <= 0) return "***";
    return text.charAt(0) + "***" + text.substring(at);
  }

  /** 生日只到年月，避免用于身份核验。 */
  static String birthday(String value) {
    if (value == null || value.isBlank()) return value;
    String text = value.strip();
    return text.length() >= 7 ? text.substring(0, 7) : "****";
  }
}
