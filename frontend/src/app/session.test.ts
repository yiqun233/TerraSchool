import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { expect, it } from "vitest";
import { endSession } from "./session";

/** 查询通知默认走下一个宏任务，等一拍再断言，而不是断言内部实现。 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 20));

/**
 * 回归：退出登录 / 会话失效后，已挂载的会话查询必须立刻读到 null。
 *
 * 曾经的写法是“先 queryClient.clear() 再写 null”：clear 会把 Query 实例从缓存里摘掉，
 * 而页面上的 observer 仍绑在旧实例上，写 null 只是新建了一个实例 —— 界面停在原页面，
 * 要刷新才回登录页（2026-11-10 本地测试反馈）。
 */
it("结束会话后挂载中的会话查询立即读到 null，业务缓存被清空", async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const sessionOptions = {
    queryKey: ["session-context"],
    queryFn: async () => ({ accountId: "account-1" }),
  };
  const businessOptions = {
    queryKey: ["student-list"],
    queryFn: async () => [{ id: "student-1" }],
  };

  // 模拟 TenantApp 里已经挂载的 useQuery：内部就是一个 QueryObserver。
  const observer = new QueryObserver(client, sessionOptions);
  const seen: Array<{ accountId: string } | null | undefined> = [];
  const unsubscribe = observer.subscribe((result) => {
    seen.push(result.data ?? null);
  });

  await client.fetchQuery(sessionOptions);
  await client.fetchQuery(businessOptions);
  await flush();
  expect(seen.at(-1)).toEqual({ accountId: "account-1" });

  endSession(client, "tenant-1");
  await flush();

  // 关键断言：同一个已挂载的观察者必须看到 null，而不是停留在旧会话上。
  expect(seen.at(-1)).toBeNull();
  expect(client.getQueryData(["student-list"])).toBeUndefined();
  unsubscribe();
});
