import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ProductCatalogWorkspace, PriceHistory } from "./ProductCatalog";

const { get, post, csrf } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  csrf: vi.fn(),
}));
vi.mock("../../shared/http", () => ({ api: { GET: get, POST: post }, csrf }));

const capabilities = {
  canRead: true,
  canMaintain: true,
  canPublish: true,
  schools: [{ id: "school-1", name: "测试学校", maintainable: true }],
};

const catalog = {
  schoolId: "school-1",
  curricula: [
    {
      id: "curriculum-1",
      name: "少儿英语",
      category: "常规",
      subject: "英语",
      active: true,
      version: 0,
    },
  ],
  products: [
    {
      id: "product-1",
      name: "少儿英语年卡",
      curriculumId: "curriculum-1",
      curriculumName: "少儿英语",
      settlementMode: 1,
      active: true,
      version: 0,
      publishedPrice: {
        id: "price-1",
        productId: "product-1",
        versionNo: 1,
        price: "1000.00",
        hours: "10.00",
        serviceFee: "0.00",
        settlementMode: 1,
        effectiveFrom: "2026-01-01",
        effectiveTo: null,
        status: "PUBLISHED",
        version: 1,
      },
    },
  ],
};

function renderWithClient(ui: React.ReactElement) {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      {ui}
    </QueryClientProvider>,
  );
}

it("目录展示服务端返回的字符串价格，不参与本地计算", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/product/capabilities")
      return { response: { ok: true, status: 200 }, data: capabilities };
    if (path === "/api/product/catalog")
      return { response: { ok: true, status: 200 }, data: catalog };
    throw new Error("unexpected path " + path);
  });
  renderWithClient(<ProductCatalogWorkspace onUnauthorized={vi.fn()} />);
  expect(await screen.findByText("少儿英语年卡")).toBeTruthy();
  expect(screen.getByText("1000.00 元 / 10.00 课时")).toBeTruthy();
  expect(screen.getByText("平均价格")).toBeTruthy();
});

it("发布价格携带版本，已发布版本不可再次发布", async () => {
  get.mockImplementation(async (path: string) => {
    if (path === "/api/product/price-versions")
      return {
        response: { ok: true, status: 200 },
        data: [
          { ...catalog.products[0].publishedPrice },
          {
            id: "price-2",
            productId: "product-1",
            versionNo: 2,
            price: "1100.00",
            hours: "10.00",
            serviceFee: "0.00",
            settlementMode: 1,
            effectiveFrom: "2026-03-01",
            effectiveTo: null,
            status: "DRAFT",
            version: 0,
          },
        ],
      };
    throw new Error("unexpected path " + path);
  });
  csrf.mockResolvedValue({ headerName: "X-CSRF-TOKEN", token: "test" });
  post.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  renderWithClient(
    <PriceHistory
      product={catalog.products[0] as never}
      canPublish
      onClose={vi.fn()}
      onUnauthorized={vi.fn()}
    />,
  );
  expect(await screen.findByText("草稿")).toBeTruthy();
  const publish = screen.getByRole("button", { name: /发\s*布/ });
  await user.click(publish);
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  expect(post.mock.calls[0][0]).toBe(
    "/api/product/price-versions/{id}/publish",
  );
  expect(post.mock.calls[0][1].body).toEqual({ expectedVersion: 0 });
  expect(post.mock.calls[0][1].params.path.id).toBe("price-2");
});

it("无发布权限时只显示提示，不提供发布按钮", async () => {
  get.mockResolvedValue({
    response: { ok: true, status: 200 },
    data: [{ ...catalog.products[0].publishedPrice }],
  });
  renderWithClient(
    <PriceHistory
      product={catalog.products[0] as never}
      canPublish={false}
      onClose={vi.fn()}
      onUnauthorized={vi.fn()}
    />,
  );
  expect(await screen.findByText("无发布权限")).toBeTruthy();
  expect(screen.queryByRole("button", { name: /发\s*布/ })).toBeNull();
});
