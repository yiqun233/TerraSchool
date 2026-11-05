import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { PersistentCsvExportButton } from "./PersistentCsvExportButton";

vi.mock("../../shared/http", () => ({
  csrf: vi.fn(async () => ({ headerName: "X-Test-CSRF", token: "test-token" })),
}));

afterEach(() => vi.unstubAllGlobals());

it("creates a persistent export with a stable request id and displays its status", async () => {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
    new Response(
      JSON.stringify({
        id: "export-1",
        schoolId: "school-1",
        exportType: "ENROLLMENT",
        status: "PENDING",
        attemptCount: 0,
      }),
      { status: 202, headers: { "Content-Type": "application/json" } },
    ),
  );
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();

  render(
    <PersistentCsvExportButton exportType="ENROLLMENT" schoolId="school-1" />,
  );
  await user.click(screen.getByRole("button", { name: "创建报名导出任务" }));

  expect(await screen.findByText("报名导出：PENDING")).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledOnce();
  expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/exports");
  const options = fetchMock.mock.calls[0]?.[1] as RequestInit;
  expect(options.headers).toMatchObject({ "X-Test-CSRF": "test-token" });
  const requestBody = JSON.parse(String(options.body)) as {
    exportType: string;
    schoolId: string;
    requestId: string;
  };
  expect(requestBody).toMatchObject({
    exportType: "ENROLLMENT",
    schoolId: "school-1",
  });
  expect(requestBody.requestId).toMatch(/^[0-9a-f-]{36}$/i);
});
