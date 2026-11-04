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
        total: 5,
        available: 2,
        skipped: 3,
        teacherName: "教师甲",
        roomName: "教室乙",
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
          {
            lessonDate: "2026-05-30",
            startsAt: "09:00",
            endsAt: "10:00",
            available: false,
            reason: "NO_FEASIBLE_RESOURCE_PAIR",
            severity: "HARD",
          },
        ],
      }}
    />,
  );
  expect(screen.getByText(/可排\s*2/)).toBeTruthy();
  expect(screen.getByText(/跳过\s*3/)).toBeTruthy();
  expect(screen.getByText("教师已有课")).toBeTruthy();
  expect(screen.getByText("该时段已存在课次")).toBeTruthy();
  expect(screen.getByText("所选日期范围内无可行的教师与教室组合")).toBeTruthy();
  expect(screen.getByText("2026-05-02")).toBeTruthy();
  expect(screen.getByText("教师：教师甲；教室：教室乙")).toBeTruthy();
});
