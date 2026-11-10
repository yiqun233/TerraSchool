/**
 * 浏览器本地的“输入摘要”，用于重试时判断参数有没有变。
 *
 * <p>结果只写在本机 localStorage 并与自己比较，不参与服务端校验，因此不要求密码学强度。
 * 优先用 SHA-256（`crypto.subtle`），但它同样只在安全上下文存在；HTTP 部署下它是 undefined，
 * 直接调用会让页面抛错（2026-11-10 与 randomUUID 同一批问题）。此时退回 FNV-1a 64 位，
 * 确定性足够判断“参数是否相同”。
 */
export async function digestText(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const subtle = globalThis.crypto?.subtle;
  if (subtle) {
    const hash = await subtle.digest("SHA-256", bytes);
    return Array.from(new Uint8Array(hash), (value) =>
      value.toString(16).padStart(2, "0"),
    ).join("");
  }
  const prime = 1099511628211n;
  const mask = 0xffffffffffffffffn;
  let hash = 14695981039346656037n;
  for (const byte of bytes) {
    hash = ((hash ^ BigInt(byte)) * prime) & mask;
  }
  return hash.toString(16).padStart(16, "0");
}
