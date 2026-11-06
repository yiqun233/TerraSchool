import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { DictionaryWorkspace } from "./DictionaryWorkspace";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

function renderPage() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <DictionaryWorkspace identityKey="tenant:account" />
    </QueryClientProvider>,
  );
}

it("学校维护者可创建稳定代码字典项并刷新列表", async () => {
  const user = userEvent.setup();
  get.mockReset();
  post.mockReset();
  csrf.mockReset();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/organization/capabilities")
      return {
        response: { ok: true, status: 200 },
        data: { editableSchoolIds: ["school-1"] },
      };
    if (path === "/api/organization/school-options")
      return {
        response: { ok: true, status: 200 },
        data: [{ id: "school-1", name: "测试学校" }],
      };
    if (
      path ===
      "/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items"
    )
      return { response: { ok: true, status: 200 }, data: [] };
    throw new Error(`unexpected GET ${path}`);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "token" });
  post.mockResolvedValue({ response: { ok: true, status: 201 }, data: {} });
  renderPage();

  await user.type(screen.getByRole("textbox", { name: "新增代码" }), "active");
  await user.type(screen.getByRole("textbox", { name: "新增名称" }), "有效");
  await user.click(screen.getByRole("button", { name: "新增字典项" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe(
    "/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items",
  );
  expect(post.mock.calls[0][1]).toMatchObject({
    params: {
      path: { schoolId: "school-1", dictionaryCode: "CUSTOMER_STATUS" },
    },
    body: { itemCode: "ACTIVE", label: "有效", sortOrder: 0 },
  });
  expect(post.mock.calls[0][1].body.requestId).toMatch(/^[0-9a-f-]{36}$/i);
});

it("版本化停用字典项并保留停用状态", async () => {
  const user = userEvent.setup();
  get.mockReset();
  post.mockReset();
  csrf.mockReset();
  get.mockImplementation(async (path: string) => {
    if (path === "/api/organization/capabilities")
      return {
        response: { ok: true, status: 200 },
        data: { editableSchoolIds: ["school-1"] },
      };
    if (path === "/api/organization/school-options")
      return {
        response: { ok: true, status: 200 },
        data: [{ id: "school-1", name: "测试学校" }],
      };
    if (
      path ===
      "/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items"
    )
      return {
        response: { ok: true, status: 200 },
        data: [
          {
            id: "item-1",
            schoolId: "school-1",
            dictionaryCode: "CUSTOMER_STATUS",
            itemCode: "ACTIVE",
            label: "有效",
            sortOrder: 1,
            active: true,
            version: 3,
          },
        ],
      };
    throw new Error(`unexpected GET ${path}`);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "token" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  renderPage();

  await waitFor(() =>
    expect(get.mock.calls.map(([path]) => path)).toContain(
      "/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items",
    ),
  );
  await screen.findByText("ACTIVE");
  await user.click(await screen.findByRole("button", { name: /编\s*辑/ }));
  await user.click(screen.getAllByRole("checkbox").at(-1)!);
  await user.click(screen.getByRole("button", { name: "OK" }));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe(
    "/api/organization/dictionary-items/{id}/update",
  );
  expect(post.mock.calls[0][1]).toMatchObject({
    params: { path: { id: "item-1" } },
    body: { expectedVersion: 3, label: "有效", sortOrder: 1, active: false },
  });
});
