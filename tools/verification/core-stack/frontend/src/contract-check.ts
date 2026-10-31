import createClient from "openapi-fetch";
import type { components, paths } from "./generated/api";

// 客户端类型完全由实际运行的 Java DTO/OpenAPI 派生，不手写第二份接口。
export const client = createClient<paths>({ baseUrl: "/", credentials: "include" });
export type DraftView = components["schemas"]["DraftView"];
export type DraftCommand = components["schemas"]["DraftCommand"];

const exactMoney: DraftView["amount"] = "123456789012345678.123456";
// 反向编译断言：金额若意外生成 number，这个检查必须失败。
// @ts-expect-error 金额必须保持十进制字符串。
const invalidMoney: DraftView["amount"] = 123;
const nullableNote: DraftView["note"] = null;
void [exactMoney, invalidMoney, nullableNote];

export function createDraft(body: DraftCommand, csrfToken: string) {
  return client.POST("/probe/drafts", { body, headers: { "X-CSRF-TOKEN": csrfToken } });
}
