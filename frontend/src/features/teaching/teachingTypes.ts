import type { components } from "../../shared/generated/api";

/** 教务接口的真实类型来源；页面与弹窗共用，避免各自重新声明一次。 */
export type LessonView = components["schemas"]["LessonView"];
export type ClassMemberView = components["schemas"]["ClassMemberView"];
export type EnrollmentView = components["schemas"]["EnrollmentView"];
export type Curriculum = components["schemas"]["Curriculum"];
export type TeacherWorkbench = components["schemas"]["TeacherWorkbench"];
export type TeacherClassWorkbench =
  components["schemas"]["TeacherClassWorkbench"];
export type TeacherWorkbenchClass = NonNullable<
  NonNullable<TeacherWorkbench["classes"]>[number]
>;
export type TeacherWorkbenchLesson = NonNullable<
  NonNullable<TeacherWorkbench["lessons"]>[number]
>;
export type DailyResourceSchedule =
  components["schemas"]["DailyResourceSchedule"];
export type StudentConsumptionPage =
  components["schemas"]["StudentConsumptionPage"];
export type AttendanceReport = components["schemas"]["AttendanceReport"];
export type TeachingOverviewReport =
  components["schemas"]["TeachingOverviewReport"];
