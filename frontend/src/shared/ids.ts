/**
 * 生成一次业务请求的身份（UUID v4 字符串）。
 *
 * <p>为什么不用 `crypto.randomUUID()`：它只在**安全上下文**（HTTPS 或 localhost）存在。
 * 客户服务器用 HTTP 部署时它是 undefined，页面会在首次渲染（状态初始化里的请求身份）
 * 直接抛 `crypto.randomUUID is not a function`，整页白屏——2026-11-10 平台运营端实测。
 *
 * <p>兜底用 `crypto.getRandomValues()`：它不受安全上下文限制，随机性等同，只是要自己拼
 * v4 的版本位与变体位。两者都拿不到时直接报错，而不是悄悄生成可预测的“伪随机”身份。
 */
export function newRequestId(): string {
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    return crypto.randomUUID();
  }
  if (
    typeof crypto === "undefined" ||
    typeof crypto.getRandomValues !== "function"
  )
    throw new Error(
      "当前浏览器无法生成安全随机数，请升级浏览器或改用 HTTPS 访问",
    );
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) =>
    value.toString(16).padStart(2, "0"),
  ).join("");
  return (
    hex.slice(0, 8) +
    "-" +
    hex.slice(8, 12) +
    "-" +
    hex.slice(12, 16) +
    "-" +
    hex.slice(16, 20) +
    "-" +
    hex.slice(20)
  );
}
