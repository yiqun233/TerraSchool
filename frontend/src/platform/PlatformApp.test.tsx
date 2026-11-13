import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import PlatformApp from "./PlatformApp";

const fetchMock = vi.fn();

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

type Handler = (
  url: string,
  init?: RequestInit,
) => Response | Promise<Response> | undefined;

function installFetch(handler: Handler) {
  fetchMock.mockImplementation(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = handler(String(input), init);
      if (!response) throw new Error(`未预期的请求：${String(input)}`);
      return response;
    },
  );
}

const operator = {
  login: "ops.zhou",
  displayName: "周明",
  actions: ["TENANT_READ", "TENANT_SUSPEND", "TENANT_RESUME"],
};

const tenants = [
  {
    id: "0190a2b3-0000-7000-8000-000000000001",
    name: "启明教育",
    active: true,
    created_at: "2026-11-07T10:20:00",
    admin_logins: "qiming-admin",
  },
  {
    id: "0190a2b3-0000-7000-8000-000000000002",
    name: "明德学堂",
    active: false,
    created_at: "2026-11-06T09:00:00",
    admin_logins: "mingde-admin（停用）、mingde-ops",
  },
];

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("平台运营端", () => {
  it("未登录时只展示平台登录表单，不暴露租户入口", async () => {
    installFetch((url) =>
      url.endsWith("/api/platform/session/me")
        ? jsonResponse(401, {})
        : undefined,
    );
    render(<PlatformApp />);

    expect(await screen.findByText("登录平台运营端")).toBeTruthy();
    expect(screen.queryByText("租户管理")).toBeNull();
  });

  it("登录成功后展示租户名称、管理员账号、状态与开通时间", async () => {
    let signedIn = false;
    installFetch((url) => {
      if (url.endsWith("/api/platform/session/me"))
        return signedIn ? jsonResponse(200, operator) : jsonResponse(401, {});
      if (url.endsWith("/api/platform/session/csrf"))
        return jsonResponse(200, {
          headerName: "X-XSRF-PLATFORM",
          token: "token-1",
        });
      if (url.endsWith("/api/platform/session/login")) {
        signedIn = true;
        return jsonResponse(204, null);
      }
      if (url.includes("/api/platform/tenants"))
        return jsonResponse(200, { items: tenants, page: 0 });
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);

    await user.type(
      await screen.findByPlaceholderText("平台登录名"),
      "ops.zhou",
    );
    await user.type(screen.getByPlaceholderText("密码"), "secret-pass");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByText("启明教育")).toBeTruthy();
    expect(screen.getByText("明德学堂")).toBeTruthy();
    const activeRow = screen.getByText("启明教育").closest("tr") as HTMLElement;
    const stoppedRow = screen
      .getByText("明德学堂")
      .closest("tr") as HTMLElement;
    expect(activeRow.textContent).toContain("正常");
    expect(activeRow.textContent).toContain("qiming-admin");
    expect(stoppedRow.textContent).toContain("停用");
    expect(stoppedRow.textContent).toContain("mingde-admin（停用）、mingde-ops");
    expect(screen.getByText("2026-11-07 10:20")).toBeTruthy();
    // 平台只读视图不出现业务数据列。
    expect(screen.queryByText("学员")).toBeNull();
  });

  it("口令错误就地提示，不回显服务端细节", async () => {
    installFetch((url) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(401, {});
      if (url.endsWith("/api/platform/session/csrf"))
        return jsonResponse(200, { headerName: "X-XSRF-PLATFORM", token: "t" });
      if (url.endsWith("/api/platform/session/login"))
        return jsonResponse(401, {});
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);
    await user.type(
      await screen.findByPlaceholderText("平台登录名"),
      "ops.zhou",
    );
    await user.type(screen.getByPlaceholderText("密码"), "wrong-pass");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toContain(
      "登录名或密码不正确",
    );
  });

  it("没有租户时给出引导而不是空表", async () => {
    installFetch((url) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, operator);
      if (url.includes("/api/platform/tenants"))
        return jsonResponse(200, { items: [], page: 0 });
      return undefined;
    });

    render(<PlatformApp />);
    expect(await screen.findByText("还没有租户")).toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("缺少 TENANT_READ 时说明缺什么权限", async () => {
    installFetch((url) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, { ...operator, actions: [] });
      if (url.includes("/api/platform/tenants")) return jsonResponse(403, {});
      return undefined;
    });

    render(<PlatformApp />);
    expect(await screen.findByText("没有查看租户的权限")).toBeTruthy();
    expect(screen.getByText(/TENANT_READ/)).toBeTruthy();
  });

  it("加载失败可重试，成功后回到列表", async () => {
    let attempts = 0;
    installFetch((url) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, operator);
      if (url.includes("/api/platform/tenants")) {
        attempts += 1;
        return attempts === 1
          ? jsonResponse(500, {})
          : jsonResponse(200, { items: tenants, page: 0 });
      }
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);
    expect(await screen.findByText("租户列表加载失败")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(screen.getByText("启明教育")).toBeTruthy());
  });

  it("停用需二次确认并写清后果，确认后刷新状态", async () => {
    let active = true;
    let suspended = 0;
    installFetch((url, init) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, operator);
      if (url.endsWith("/api/platform/session/csrf"))
        return jsonResponse(200, {
          headerName: "X-XSRF-PLATFORM",
          token: "token-1",
        });
      if (url.endsWith(`/api/platform/tenants/${tenants[0].id}/suspend`)) {
        expect(init?.method).toBe("POST");
        active = false;
        suspended += 1;
        return jsonResponse(204, null);
      }
      if (url.includes("/api/platform/tenants"))
        return jsonResponse(200, {
          items: tenants.map((row, index) =>
            index === 0 ? { ...row, active } : row,
          ),
          page: 0,
        });
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);
    await screen.findByText("启明教育");

    await user.click(screen.getByRole("button", { name: "停用" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog.textContent).toContain("所有人将无法登录");
    expect(dialog.textContent).toContain("数据保留");

    await user.click(within(dialog).getByRole("button", { name: "停用" }));
    await waitFor(() => expect(suspended).toBe(1));
    // 刷新后该行状态变为停用，操作换成恢复。
    const row = screen.getByText("启明教育").closest("tr") as HTMLElement;
    expect(within(row).getByRole("button", { name: "恢复" })).toBeTruthy();
  });

  it("开通向导提交后就地展示一次性初始密码，并刷新列表", async () => {
    const admin = { ...operator, actions: ["TENANT_READ", "TENANT_CREATE"] };
    let created = false;
    const bodies: unknown[] = [];
    installFetch((url, init) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, admin);
      if (url.endsWith("/api/platform/session/csrf"))
        return jsonResponse(200, { headerName: "X-XSRF-PLATFORM", token: "t" });
      if (url.endsWith("/api/platform/tenants") && init?.method === "POST") {
        bodies.push(JSON.parse(String(init.body)));
        created = true;
        return jsonResponse(201, {
          tenantId: "0190a2b3-0000-7000-8000-000000000009",
          accountId: "0190a2b3-0000-7000-8000-00000000000a",
        });
      }
      if (url.includes("/api/platform/tenants"))
        return jsonResponse(200, {
          items: created ? tenants : [],
          page: 0,
        });
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);

    await user.click(await screen.findByRole("button", { name: "开通租户" }));
    await user.type(
      await screen.findByPlaceholderText("例如：启明教育培训学校"),
      "新开机构",
    );
    await user.type(
      screen.getByPlaceholderText("例如：qiming-admin"),
      "new-admin",
    );
    await user.click(screen.getByRole("button", { name: "开通" }));

    expect(await screen.findByText(/已开通/)).toBeTruthy();
    expect(screen.getAllByText(/新开机构/).length).toBeGreaterThan(0);
    expect(screen.getByText(/只显示这一次/)).toBeTruthy();
    expect(screen.getByText("new-admin")).toBeTruthy();
    // 请求里带上了强口令与请求身份，且没有学校信息。
    expect(Object.keys(bodies[0] as Record<string, unknown>).sort()).toEqual([
      "adminLogin",
      "adminPassword",
      "name",
      "requestId",
    ]);
    expect(
      (bodies[0] as { adminPassword: string }).adminPassword.length,
    ).toBeGreaterThanOrEqual(12);
  });

  it("开通冲突时就地说明原因，不关闭向导", async () => {
    const admin = { ...operator, actions: ["TENANT_READ", "TENANT_CREATE"] };
    installFetch((url, init) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, admin);
      if (url.endsWith("/api/platform/session/csrf"))
        return jsonResponse(200, { headerName: "X-XSRF-PLATFORM", token: "t" });
      if (url.endsWith("/api/platform/tenants") && init?.method === "POST")
        return jsonResponse(409, { code: "ADMIN_LOGIN_TAKEN" });
      if (url.includes("/api/platform/tenants"))
        return jsonResponse(200, { items: tenants, page: 0 });
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);
    await user.click(await screen.findByRole("button", { name: "开通租户" }));
    await user.type(
      await screen.findByPlaceholderText("例如：启明教育培训学校"),
      "冲突机构",
    );
    await user.type(
      screen.getByPlaceholderText("例如：qiming-admin"),
      "taken-admin",
    );
    await user.click(screen.getByRole("button", { name: "开通" }));
    expect(
      await screen.findByText("管理员登录名已被占用，请换一个"),
    ).toBeTruthy();
    expect(screen.getByRole("button", { name: "开通" })).toBeTruthy();
  });

  it("重置管理员密码只展示一次新口令", async () => {
    const admin = { ...operator, actions: ["TENANT_READ", "TENANT_CREATE"] };
    installFetch((url, init) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, admin);
      if (url.endsWith("/api/platform/session/csrf"))
        return jsonResponse(200, { headerName: "X-XSRF-PLATFORM", token: "t" });
      if (url.includes("/reset-password") && init?.method === "POST")
        return jsonResponse(200, {
          login: "qiming-admin",
          password: "Reset-Pass-123456",
        });
      if (url.includes("/administrators"))
        return jsonResponse(200, {
          items: [{ login: "qiming-admin", active: true }],
        });
      if (url.includes("/api/platform/tenants"))
        return jsonResponse(200, { items: tenants, page: 0 });
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);
    const row = (await screen.findByText("启明教育")).closest(
      "tr",
    ) as HTMLElement;
    await user.click(within(row).getByRole("button", { name: "重置密码" }));
    await user.click(await screen.findByRole("button", { name: "重置" }));
    expect(await screen.findByText("Reset-Pass-123456")).toBeTruthy();
    expect(screen.getByText(/只显示这一次/)).toBeTruthy();
  });

  it("品牌弹窗丢弃过期租户的响应，不把甲的配置写进乙", async () => {
    const admin = {
      ...operator,
      actions: ["TENANT_READ", "TENANT_BRANDING_WRITE"],
    };
    const pending = new Map<string, (response: Response) => void>();
    const view = (displayName: string, version: number) => ({
      displayName,
      seed: "#3b4e9b",
      brand: "#3b4e9b",
      brandHover: "#2f3f7e",
      brandActive: "#26336a",
      brandSoft: "#eef1f9",
      brandLine: "#cfd6ea",
      brandLink: "#3b4e9b",
      onBrand: "#ffffff",
      logoWideUrl: null,
      logoMarkUrl: null,
      adjusted: false,
      algoVersion: 1,
      version,
      canWrite: true,
    });

    installFetch((url) => {
      if (url.endsWith("/api/platform/session/me"))
        return jsonResponse(200, admin);
      if (/\/branding\/history$/.test(url)) return jsonResponse(200, []);
      const branding = url.match(
        /\/api\/platform\/tenants\/([^/]+)\/branding$/,
      );
      if (branding)
        return new Promise<Response>((resolve) =>
          pending.set(branding[1], resolve),
        );
      const detail = url.match(/\/api\/platform\/tenants\/([^/]+)$/);
      if (detail) {
        const row = tenants.find((item) => item.id === detail[1]);
        return jsonResponse(200, {
          ...row,
          schools: 2,
          accounts: 5,
          administrators: 1,
        });
      }
      if (url.includes("/api/platform/tenants"))
        return jsonResponse(200, { items: tenants, page: 0 });
      return undefined;
    });

    const user = userEvent.setup();
    render(<PlatformApp />);

    // 打开甲的详情与品牌弹窗，品牌请求先挂起。
    await user.click(await screen.findByRole("button", { name: "启明教育" }));
    await user.click(await screen.findByRole("button", { name: "代改品牌" }));
    // 关掉弹窗与抽屉，再打开乙的品牌弹窗。
    await user.click(screen.getByRole("button", { name: "取消" }));
    await user.keyboard("{Escape}");
    await user.click(await screen.findByRole("button", { name: "明德学堂" }));
    await user.click(await screen.findByRole("button", { name: "代改品牌" }));

    // 乙的响应先到，甲的晚到；表单必须保留乙的值。
    pending.get(tenants[1].id)!(jsonResponse(200, view("明德品牌", 5)));
    expect(await screen.findByText("当前版本 5")).toBeTruthy();
    pending.get(tenants[0].id)!(jsonResponse(200, view("启明品牌", 9)));

    await waitFor(() =>
      expect(
        (screen.getByLabelText("系统显示名") as HTMLInputElement).value,
      ).toBe("明德品牌"),
    );
    expect(screen.getByText("当前版本 5")).toBeTruthy();
    expect(screen.queryByText("当前版本 9")).toBeNull();
  });
});
