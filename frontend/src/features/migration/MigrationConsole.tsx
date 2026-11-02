import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Input,
  Select,
  Space,
  Switch,
  Table,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type MigrationReport = components["schemas"]["MigrationReport"];
type MigrationIssue = components["schemas"]["MigrationIssue"];
type OpeningReport = components["schemas"]["Report"];
type Reconciliation = components["schemas"]["OpeningReconciliation"];

const STUDENT_HELP =
  "每行：旧主键,学员号,姓名,性别(MALE/FEMALE/UNKNOWN),生日,证件号,电话,联系人,联系人电话";
const RECEIPT_HELP = "每行：旧主键,学员号,金额,方式,收款日期";

/**
 * 旧库迁移控制台：粘贴 CSV → 装载暂存 → 干跑对账 → 确认导入。
 *
 * 干跑不写业务表，只报告"将导入多少、金额合计、有哪些差异"；确认按旧主键记账，重复确认幂等。
 */
export function MigrationConsole({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const schools = useQuery({
    queryKey: ["migration-schools"],
    queryFn: async ({ signal }) => {
      // 迁移页复用学员能力里的学校列表；真正的写权限由服务端 MIGRATION_MANAGE 校验。
      const { data, response } = await api.GET("/api/archive/capabilities", {
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取学校范围");
      return data;
    },
  });
  const options = schools.data?.maintainableSchools ?? [];
  const current = schoolId ?? options[0]?.id;
  const canManage = (schools.data?.maintainableSchools?.length ?? 0) > 0;
  const [studentCsv, setStudentCsv] = useState("");
  const [receiptCsv, setReceiptCsv] = useState("");
  const [report, setReport] = useState<MigrationReport | null>(null);
  const [openingCsv, setOpeningCsv] = useState("");
  const [batchId, setBatchId] = useState(() => `open-${Date.now()}`);
  const [dryRun, setDryRun] = useState(true);
  const [openingReport, setOpeningReport] = useState<OpeningReport | null>(
    null,
  );
  const [reconciliation, setReconciliation] = useState<Reconciliation | null>(
    null,
  );
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  function parse(text: string): string[][] {
    return text
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0 && !line.startsWith("#"))
      .map((line) => line.split(",").map((cell) => cell.trim()));
  }

  /** 把 CSV 转成暂存契约；列数不足时留空，由服务端校验。 */
  function payload() {
    const students = parse(studentCsv).map((cells) => ({
      legacyId: cells[0],
      studentNo: cells[1],
      name: cells[2],
      sex: cells[3] || "UNKNOWN",
      birthday: cells[4] || undefined,
      certCode: cells[5] || undefined,
      phone: cells[6] || undefined,
      contactName: cells[7] || undefined,
      contactPhone: cells[8] || undefined,
    }));
    const receipts = parse(receiptCsv).map((cells) => ({
      legacyId: cells[0],
      studentNo: cells[1],
      amount: cells[2],
      method: cells[3] || "CASH",
      occurredOn: cells[4],
    }));
    return { students, receipts };
  }

  /** 期初导入：学员号,金额,课时,已消耗金额；逐行返回结果。 */
  async function importOpenings() {
    if (!current) {
      setError("请先选择学校");
      return;
    }
    const rows = parse(openingCsv).map((cells) => ({
      studentNo: cells[0],
      amount: cells[1] || "0",
      hours: cells[2] || "0",
      consumedAmount: cells[3] || "0",
    }));
    if (rows.length === 0) {
      setError("请先填写期初导入行");
      return;
    }
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const token = await csrf();
      const { data, response } = await api.POST("/api/migration/openings", {
        params: { query: { schoolId: current! } },
        headers: { [token.headerName!]: token.token! },
        body: { batchId, occurredOn: "", reason: "旧库期初迁移", rows, dryRun },
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data) {
        setError("期初导入失败：请核对权限、学员号与金额格式。");
        return;
      }
      const imported = data as OpeningReport;
      setOpeningReport(imported);
      setMessage(
        `期初导入${imported.mode === "DRY_RUN" ? "干跑" : "完成"}：成功 ${imported.ok}、跳过 ${imported.skipped}、失败 ${imported.failed}`,
      );
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  /** 按批次对账：应有值 vs 系统当前实际值。 */
  async function reconcile() {
    if (!current) {
      setError("请先选择学校");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const { data, response } = await api.GET(
        "/api/migration/openings/reconciliation",
        {
          params: { query: { schoolId: current!, batchId } },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data) {
        setError("对账失败：请核对批次号与权限。");
        return;
      }
      setReconciliation(data);
    } catch {
      setError("对账请求失败，请重试。");
    } finally {
      setBusy(false);
    }
  }

  async function call(path: string, body?: unknown) {
    if (!current) {
      setError("请先选择学校");
      return;
    }
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const token = await csrf();
      const request =
        path === "/api/migration/staging"
          ? api.POST("/api/migration/staging", {
              params: { query: { schoolId: current! } },
              headers: { [token.headerName!]: token.token! },
              body: body as never,
            })
          : api.POST("/api/migration/commit", {
              params: { query: { schoolId: current! } },
              headers: { [token.headerName!]: token.token! },
            });
      const { data, response } = await request;
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data) {
        setError("操作失败：请核对迁移权限与 CSV 格式。");
        return;
      }
      setReport(data);
      setMessage(
        path === "/api/migration/commit" ? "已确认导入" : "已装载暂存并干跑",
      );
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Select
        aria-label="迁移学校"
        style={{ width: 220, marginBottom: 8 }}
        value={current}
        options={options.map((item) => ({
          value: item.id!,
          label: item.name!,
        }))}
        onChange={(value) => setSchoolId(value)}
        placeholder="选择学校"
      />
      {!canManage ? (
        <Alert
          title="当前账号只能查看"
          description="装载与确认需要迁移权限（MIGRATION_MANAGE）。"
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {message ? <Alert title={message} type="success" showIcon /> : null}
      <Card size="small" title="学员暂存" style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">{STUDENT_HELP}</Typography.Text>
        <Input.TextArea
          aria-label="学员 CSV"
          rows={4}
          value={studentCsv}
          onChange={(event) => setStudentCsv(event.target.value)}
          disabled={!canManage || busy}
          placeholder="S-1,L0001,张三,FEMALE,2014-05-06,110101201405061234,13900001111,张妈妈,13900002222"
        />
      </Card>
      <Card size="small" title="收款暂存" style={{ marginTop: 12 }}>
        <Typography.Text type="secondary">{RECEIPT_HELP}</Typography.Text>
        <Input.TextArea
          aria-label="收款 CSV"
          rows={3}
          value={receiptCsv}
          onChange={(event) => setReceiptCsv(event.target.value)}
          disabled={!canManage || busy}
          placeholder="R-1,L0001,1000.00,CASH,2026-01-10"
        />
      </Card>
      <Space wrap style={{ marginTop: 12 }}>
        <Button
          onClick={() => void call("/api/migration/staging", payload())}
          loading={busy}
          disabled={!canManage || busy}
        >
          装载并干跑
        </Button>
        <Button
          type="primary"
          onClick={() => void call("/api/migration/commit")}
          loading={busy}
          disabled={!canManage || busy || !report}
        >
          确认导入
        </Button>
        <Typography.Text type="secondary">
          干跑不写业务表；确认按旧主键记账，重复确认不会重复导入
        </Typography.Text>
      </Space>
      <Card
        size="small"
        title="期初导入（历史余额与已消耗课时）"
        style={{ marginTop: 12 }}
      >
        <Typography.Text type="secondary">
          每行：学员号,金额,课时,已消耗金额（金额可空，课时可空；批次号相同重跑不会重复导入）
        </Typography.Text>
        <Space wrap style={{ marginTop: 8 }}>
          <Input
            aria-label="批次号"
            style={{ width: 200 }}
            value={batchId}
            onChange={(event) => setBatchId(event.target.value)}
            disabled={!canManage || busy}
          />
          <Space>
            <Switch
              checked={dryRun}
              onChange={setDryRun}
              disabled={!canManage || busy}
              aria-label="干跑"
            />
            <Typography.Text>
              {dryRun ? "干跑（不写库）" : "实际导入"}
            </Typography.Text>
          </Space>
        </Space>
        <Input.TextArea
          aria-label="期初 CSV"
          rows={3}
          style={{ marginTop: 8 }}
          value={openingCsv}
          onChange={(event) => setOpeningCsv(event.target.value)}
          disabled={!canManage || busy}
          placeholder="L0001,500.00,4.00,400.00"
        />
        <Button
          style={{ marginTop: 8 }}
          type="primary"
          onClick={() => void importOpenings()}
          loading={busy}
          disabled={!canManage || busy}
        >
          导入期初
        </Button>
        <Button
          style={{ marginLeft: 8, marginTop: 8 }}
          onClick={() => void reconcile()}
          loading={busy}
        >
          按批次对账
        </Button>
        {openingReport ? (
          <Table
            style={{ marginTop: 8 }}
            rowKey={(row: { studentNo?: string }) =>
              row.studentNo ?? Math.random().toString()
            }
            size="small"
            dataSource={openingReport.rows ?? []}
            pagination={false}
            columns={[
              { title: "学员号", dataIndex: "studentNo" },
              { title: "结果", dataIndex: "status" },
              { title: "说明", dataIndex: "detail" },
            ]}
          />
        ) : null}
      </Card>
      {reconciliation ? (
        <Card
          size="small"
          title={`对账结果（批次 ${reconciliation.batchId}）`}
          style={{ marginTop: 12 }}
        >
          <Space wrap>
            <span>
              行数 {reconciliation.rows}，对上 {reconciliation.matched}，差异{" "}
              {reconciliation.mismatched}
            </span>
            <span>
              金额：应有 {reconciliation.expectedAmount} / 实际{" "}
              {reconciliation.actualAmount}
            </span>
            <span>
              课时：应有 {reconciliation.expectedHours} / 实际{" "}
              {reconciliation.actualHours}
            </span>
          </Space>
          <Table
            rowKey={(row: { studentNo?: string }) => row.studentNo ?? ""}
            size="small"
            style={{ marginTop: 8 }}
            dataSource={reconciliation.details ?? []}
            pagination={false}
            columns={[
              { title: "学员号", dataIndex: "studentNo" },
              { title: "应有金额", dataIndex: "expectedAmount" },
              { title: "实际金额", dataIndex: "actualAmount" },
              { title: "金额差", dataIndex: "amountDiff" },
              { title: "应有课时", dataIndex: "expectedHours" },
              { title: "实际课时", dataIndex: "actualHours" },
              { title: "结果", dataIndex: "status" },
            ]}
          />
        </Card>
      ) : null}
      {report ? (
        <Card
          size="small"
          title={`对账结果（${report.mode === "COMMIT" ? "已确认" : "干跑"}）`}
          style={{ marginTop: 12 }}
        >
          <Space wrap>
            <span>
              学员：暂存 {report.studentsStaged}，已导入{" "}
              {report.studentsImported}，待导入 {report.studentsPending}
            </span>
            <span>
              收款金额：暂存 {report.stagedReceiptTotal}，已导入{" "}
              {report.importedReceiptTotal}
            </span>
          </Space>
          <Table
            rowKey={(issue: MigrationIssue) =>
              `${issue.kind}-${issue.key}-${issue.detail}`
            }
            size="small"
            style={{ marginTop: 8 }}
            dataSource={report.issues ?? []}
            pagination={false}
            columns={[
              { title: "类型", dataIndex: "kind" },
              { title: "旧主键", dataIndex: "key" },
              { title: "说明", dataIndex: "detail" },
            ]}
            locale={{ emptyText: "没有差异" }}
          />
        </Card>
      ) : null}
    </>
  );
}
