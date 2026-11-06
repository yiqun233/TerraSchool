import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { PersistentCsvExportButton } from "./PersistentCsvExportButton";

vi.mock("../../shared/http", () => ({
  csrf: vi.fn(async () => ({ headerName: "X-Test-CSRF", token: "test-token" })),
}));

afterEach(() => vi.unstubAllGlobals());

it("creates a persistent export with a stable request id and displays its status", async () => {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    if (String(input).startsWith("/api/exports?")) return Response.json([]);
    return Response.json(
      {
        id: "export-1",
        schoolId: "school-1",
        exportType: "ENROLLMENT",
        format: "CSV",
        status: "PENDING",
        attemptCount: 0,
      },
      { status: 202 },
    );
  });
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();

  render(
    <PersistentCsvExportButton exportType="ENROLLMENT" schoolId="school-1" />,
  );
  await user.click(
    screen.getByRole("button", { name: "创建报名 CSV 导出任务" }),
  );

  expect(await screen.findByText("报名 CSV 导出：PENDING")).toBeTruthy();
  const createCall = fetchMock.mock.calls.find(
    (call) => call[0] === "/api/exports",
  );
  expect(createCall).toBeTruthy();
  const options = createCall?.[1] as RequestInit;
  expect(options.headers).toMatchObject({ "X-Test-CSRF": "test-token" });
  const requestBody = JSON.parse(String(options.body)) as {
    exportType: string;
    schoolId: string;
    requestId: string;
    format: string;
  };
  expect(requestBody).toMatchObject({
    exportType: "ENROLLMENT",
    schoolId: "school-1",
    format: "CSV",
  });
  expect(requestBody.requestId).toMatch(/^[0-9a-f-]{36}$/i);
});

it("restores the most recent completed task after the workspace is reopened", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn<typeof fetch>().mockResolvedValue(
      Response.json([
        {
          id: "export-history-1",
          schoolId: "school-1",
          exportType: "LESSONS",
          format: "CSV",
          status: "COMPLETED",
          attemptCount: 1,
          rowCount: 12,
        },
      ]),
    ),
  );

  render(
    <PersistentCsvExportButton exportType="LESSONS" schoolId="school-1" />,
  );

  expect(
    await screen.findByText("课次 CSV 导出：COMPLETED · 12 行"),
  ).toBeTruthy();
  expect(screen.getByRole("button", { name: "下载 CSV" })).toBeTruthy();
});

it("creates an XLSX task with an XLSX-specific request fingerprint", async () => {
  const fetchMock = vi
    .fn<typeof fetch>()
    .mockImplementation(async (input, init) => {
      if (String(input).startsWith("/api/exports?")) return Response.json([]);
      const body = JSON.parse(String(init?.body)) as { format: string };
      return Response.json(
        {
          id: "export-xlsx",
          schoolId: "school-1",
          exportType: "RECEIPTS",
          format: body.format,
          status: "PENDING",
          attemptCount: 0,
        },
        { status: 202 },
      );
    });
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();

  render(
    <PersistentCsvExportButton exportType="RECEIPTS" schoolId="school-1" />,
  );
  await user.click(
    screen.getByRole("button", { name: "创建收款 XLSX 导出任务" }),
  );

  expect(await screen.findByText("收款 XLSX 导出：PENDING")).toBeTruthy();
  const options = fetchMock.mock.calls.find(
    (call) => call[0] === "/api/exports",
  )?.[1] as RequestInit;
  expect(JSON.parse(String(options.body))).toMatchObject({ format: "XLSX" });
});

it("creates a PDF task with a PDF-specific request fingerprint", async () => {
  const fetchMock = vi
    .fn<typeof fetch>()
    .mockImplementation(async (input, init) => {
      if (String(input).startsWith("/api/exports?")) return Response.json([]);
      const body = JSON.parse(String(init?.body)) as { format: string };
      return Response.json(
        {
          id: "export-pdf",
          schoolId: "school-1",
          exportType: "LESSONS",
          format: body.format,
          status: "PENDING",
          attemptCount: 0,
        },
        { status: 202 },
      );
    });
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();

  render(
    <PersistentCsvExportButton exportType="LESSONS" schoolId="school-1" />,
  );
  await user.click(
    screen.getByRole("button", { name: "创建课次 PDF 导出任务" }),
  );

  expect(await screen.findByText("课次 PDF 导出：PENDING")).toBeTruthy();
  const options = fetchMock.mock.calls.find(
    (call) => call[0] === "/api/exports",
  )?.[1] as RequestInit;
  expect(JSON.parse(String(options.body))).toMatchObject({ format: "PDF" });
});
