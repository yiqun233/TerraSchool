import { expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { SchedulePreviewCard } from "./SchedulePlanner";

vi.mock("../../shared/http", () => ({
  api: { GET: vi.fn(), POST: vi.fn() },
  csrf: vi.fn(),
}));

it("预览卡片展示可排与跳过数量，并把原因码翻译成中文", () => {
  render(
    <SchedulePreviewCard
      preview={{
        classId: "class-1",
        total: 4,
        available: 2,
        skipped: 2,
        slots: [
          {
            lessonDate: "2026-05-02",
            startsAt: "09:00",
            endsAt: "10:00",
            available: true,
          },
          {
            lessonDate: "2026-05-09",
            startsAt: "09:00",
            endsAt: "10:00",
            available: false,
            reason: "TEACHER_CLASH",
          },
          {
            lessonDate: "2026-05-16",
            startsAt: "09:00",
            endsAt: "10:00",
            available: false,
            reason: "DUPLICATE",
          },
          {
            lessonDate: "2026-05-23",
            startsAt: "09:00",
            endsAt: "10:00",
            available: true,
          },
        ],
      }}
    />,
  );
  expect(screen.getByText(/可排\s*2/)).toBeTruthy();
  expect(screen.getByText(/跳过\s*2/)).toBeTruthy();
  expect(screen.getByText("教师已有课")).toBeTruthy();
  expect(screen.getByText("该时段已存在课次")).toBeTruthy();
  expect(screen.getByText("2026-05-02")).toBeTruthy();
});
