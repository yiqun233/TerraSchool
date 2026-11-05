import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import { PersistentCsvExportButton } from "../shared/PersistentCsvExportButton";
import { RefundPanel } from "./RefundPanel";

type EnrollmentView = components["schemas"]["EnrollmentView"];
type StudentSummary = components["schemas"]["StudentSummary"];
type Receipt = components["schemas"]["Receipt"];
type Allocation = components["schemas"]["Allocation"];
type FundsCapabilities = components["schemas"]["FundsCapabilities"];

const STATUS_LABEL: Record<string, string> = {
  PENDING_AUDIT: "待审核",
  ACTIVE: "已生效",
};

/**
 * 报名与收款工作台。
 *
 * 报名只提交学员与价格版本，单价与课时由服务端从价格快照冻结；收款、分配与余额都由服务端以十进制字符串返回，
 * 页面不做权威金额计算。
 */
export function EnrollmentWorkspace({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [creatingBatch, setCreatingBatch] = useState(false);
  const [receivingPrepayment, setReceivingPrepayment] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const queryClient = useQueryClient();

  const capabilities = useQuery({
    queryKey: ["enrollment-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/enrollment/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取报名权限");
      return data;
    },
  });
  const current = schoolId ?? capabilities.data?.schools?.[0]?.id;
  const school = capabilities.data?.schools?.find(
    (item) => item.id === current,
  );
  const fundsCapabilities = useQuery({
    queryKey: ["enrollment-funds-capabilities"],
    queryFn: async ({ signal }): Promise<FundsCapabilities> => {
      const { data, response } = await api.GET("/api/funds/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取收款权限");
      return data;
    },
  });
  const enrollments = useQuery({
    queryKey: ["enrollments", current],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/enrollment/enrollments", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data)
        throw new Error("无法读取报名列表，请核对当前权限");
      return data;
    },
  });

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取报名权限…</p>;
  if (!capabilities.data?.canRead)
    return <Alert title="账号当前无报名读取权限" type="error" />;
  const schools = capabilities.data.schools ?? [];
  if (schools.length === 0)
    return <Alert title="当前范围内没有可查看的学校" type="warning" />;

  return (
    <>
      <Space className="toolbar" wrap>
        <Select
          aria-label="归属学校"
          style={{ width: 220 }}
          value={current}
          options={schools.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
          onChange={(value) => setSchoolId(value)}
        />
        {school?.maintainable ? (
          <>
            <Button
              onClick={() => {
                setError("");
                setCreating(true);
              }}
            >
              登记报名
            </Button>
            <Button
              onClick={() => {
                setError("");
                setCreatingBatch(true);
              }}
            >
              多产品报名
            </Button>
          </>
        ) : null}
        {fundsCapabilities.data?.schools?.some(
          (item) => item.id === current && item.canReceipt,
        ) ? (
          <Button
            onClick={() => {
              setError("");
              setReceivingPrepayment(true);
            }}
          >
            登记学员预收款
          </Button>
        ) : null}
        <Button
          onClick={() => void enrollments.refetch()}
          loading={enrollments.isFetching}
        >
          刷新
        </Button>
        <PersistentCsvExportButton exportType="ENROLLMENT" schoolId={current} />
        <PersistentCsvExportButton exportType="RECEIPTS" schoolId={current} />
        <Typography.Text type="secondary">
          报名冻结价格与课时；收款与分配分开记账
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {enrollments.error ? (
        <Alert title={enrollments.error.message} type="error" />
      ) : null}
      <Table
        rowKey="id"
        loading={enrollments.isPending}
        dataSource={enrollments.data?.items ?? []}
        onRow={(row) => ({
          onClick: () => setDetailId(row.id ?? null),
          style: { cursor: "pointer" },
        })}
        columns={[
          { title: "学员", dataIndex: "studentName" },
          { title: "产品", dataIndex: "productName" },
          {
            title: "冻结价格/课时",
            render: (_: unknown, row: EnrollmentView) =>
              row.price === "0.00" && row.hours === "0.00"
                ? `仅服务费 ${row.serviceFee} 元`
                : `${row.price} 元 / ${row.hours} 课时`,
          },
          { title: "净应收", dataIndex: "netAmount" },
          {
            title: "状态",
            dataIndex: "status",
            render: (status: string) => (
              <Tag color={status === "ACTIVE" ? "green" : "blue"}>
                {STATUS_LABEL[status] ?? status}
              </Tag>
            ),
          },
          {
            title: "操作",
            key: "actions",
            render: (_: unknown, row: EnrollmentView) =>
              row.status === "PENDING_AUDIT" &&
              capabilities.data?.canApprove ? (
                <ApproveButton
                  enrollment={row}
                  onDone={() => void enrollments.refetch()}
                  onError={setError}
                />
              ) : null,
          },
        ]}
        locale={{ emptyText: "该学校还没有报名记录" }}
        pagination={false}
      />
      {creating && current ? (
        <EnrollmentCreator
          schoolId={current}
          onCancel={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            void enrollments.refetch();
          }}
        />
      ) : null}
      {creatingBatch && current ? (
        <EnrollmentBatchCreator
          schoolId={current}
          onCancel={() => setCreatingBatch(false)}
          onCreated={() => {
            setCreatingBatch(false);
            void enrollments.refetch();
          }}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
      {current &&
      fundsCapabilities.data?.schools?.some(
        (item) => item.id === current && item.canReceipt,
      ) ? (
        <PrepaymentCreator
          schoolId={current}
          open={receivingPrepayment}
          onCancel={() => setReceivingPrepayment(false)}
          onUnauthorized={onUnauthorized}
          onCreated={(studentId) => {
            setReceivingPrepayment(false);
            void queryClient.invalidateQueries({
              queryKey: ["receipts", studentId],
            });
            void queryClient.invalidateQueries({
              queryKey: ["balance", studentId],
            });
          }}
        />
      ) : null}
      {detailId ? (
        <EnrollmentDetail
          id={detailId}
          canDiscount={Boolean(capabilities.data?.canDiscount)}
          onClose={() => {
            setDetailId(null);
            void enrollments.refetch();
          }}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
    </>
  );
}

/** 先记录学员名下的未分配实收；报名成立后可在报名详情中分配抵扣应收。 */
function PrepaymentCreator({
  schoolId,
  open,
  onCancel,
  onCreated,
  onUnauthorized,
}: {
  schoolId: string;
  open: boolean;
  onCancel: () => void;
  onCreated: (studentId: string) => void;
  onUnauthorized: () => void;
}) {
  const [keyword, setKeyword] = useState("");
  const [studentId, setStudentId] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();
  const requestRef = useRef<{ fingerprint: string; requestId: string } | null>(
    null,
  );
  const students = useQuery({
    queryKey: ["prepayment-students", schoolId, keyword.trim()],
    enabled: open && keyword.trim().length >= 2,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/archive/students", {
        params: { query: { search: keyword.trim(), page: 0, size: 20 } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data)
        throw new Error("无法检索学员档案，请确认学员读取权限");
      return (data.items ?? []).filter((item) => item.schoolId === schoolId);
    },
  });

  async function save() {
    let values: { amount: number; method: string; occurredOn: string };
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (!studentId || busy) {
      setError("请选择本校学员");
      return;
    }
    const body = {
      schoolId,
      studentId,
      amount: String(values.amount),
      method: values.method ?? "CASH",
      occurredOn: values.occurredOn,
    };
    const fingerprint = JSON.stringify(body);
    if (requestRef.current?.fingerprint !== fingerprint) {
      requestRef.current = { fingerprint, requestId: crypto.randomUUID() };
    }
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/funds/receipts", {
        headers: { [token.headerName!]: token.token! },
        body: { ...body, requestId: requestRef.current.requestId },
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setError(
          response.status >= 500
            ? "收款结果暂时未知；保持学员、金额、方式和日期不变后重试，系统将使用同一请求身份防止重复入账。"
            : response.status === 409
              ? "收款条件或财务期间已变化；核对后修改表单再提交。"
              : "预收款登记失败，请核对收款权限、财务期间和金额。",
        );
        return;
      }
      requestRef.current = null;
      onCreated(studentId);
    } catch {
      setError(
        "收款结果暂时未知；保持学员、金额、方式和日期不变后重试，系统将使用同一请求身份防止重复入账。",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="登记学员预收款"
      open={open}
      onCancel={onCancel}
      onOk={() => void save()}
      okText="确认收款"
      cancelText="取消"
      confirmLoading={busy}
      okButtonProps={{ disabled: !studentId || busy }}
      mask={{ closable: !busy }}
    >
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {students.error ? (
        <Alert title={students.error.message} type="error" showIcon />
      ) : null}
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          method: "CASH",
          occurredOn: new Date().toISOString().slice(0, 10),
        }}
      >
        <Form.Item label="学员（输入姓名、学员号或电话检索）" required>
          <Select
            aria-label="学员（输入姓名、学员号或电话检索）"
            showSearch
            filterOption={false}
            placeholder="输入至少 2 个字符检索本校学员"
            searchValue={keyword}
            onSearch={setKeyword}
            onChange={setStudentId}
            value={studentId}
            loading={students.isFetching}
            options={(students.data ?? []).map((item: StudentSummary) => ({
              value: item.id!,
              label: `${item.studentNo ?? ""} ${item.name ?? "未命名学员"}${item.phone ? ` · ${item.phone}` : ""}`,
            }))}
            notFoundContent={
              keyword.trim().length < 2
                ? "请输入至少 2 个字符"
                : students.isFetching
                  ? "正在检索…"
                  : "没有匹配的本校学员"
            }
          />
        </Form.Item>
        <Form.Item
          label="预收金额（元）"
          name="amount"
          rules={[{ required: true, message: "请输入金额" }]}
        >
          <InputNumber min={0.01} precision={2} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item label="收款方式" name="method">
          <Select
            options={[
              { value: "CASH", label: "现金" },
              { value: "BANK", label: "银行转账" },
              { value: "OTHER", label: "其他" },
            ]}
          />
        </Form.Item>
        <Form.Item
          label="收款日期"
          name="occurredOn"
          rules={[{ required: true, message: "请输入日期" }]}
        >
          <Input maxLength={10} />
        </Form.Item>
      </Form>
      <Alert
        type="info"
        showIcon
        title="该笔款项先进入学员可用余额；报名成立后，在报名详情中分配到对应报名抵扣未收金额。"
      />
    </Modal>
  );
}

/** 审核：单独权限、单独动作，携带读取时的版本。 */
export function ApproveButton({
  enrollment,
  onDone,
  onError,
}: {
  enrollment: EnrollmentView;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function approve() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/enrollment/enrollments/{id}/approve",
        {
          params: { path: { id: enrollment.id! } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: enrollment.version },
        },
      );
      if (response.status === 409) {
        onError("报名已被他人处理，请刷新后再审核。");
        onDone();
        return;
      }
      if (!response.ok) {
        onError("审核失败，请核对报名审核权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button size="small" disabled={busy} onClick={() => void approve()}>
      审核通过
    </Button>
  );
}

/** 课程明细合计校验：课时 0.001 与金额 0.005 的容差对应两位小数的显示精度。 */
export function validateCourseLines(
  lines: { curriculumId?: string; hours?: string; amount?: string }[],
  selected: { hours?: string; price?: string } | undefined,
): string | null {
  if (!selected || lines.length === 0) return null;
  const hours = lines.reduce(
    (total, line) => total + Number(line.hours ?? 0),
    0,
  );
  const amount = lines.reduce(
    (total, line) => total + Number(line.amount ?? 0),
    0,
  );
  if (Math.abs(hours - Number(selected.hours ?? 0)) > 0.001)
    return `课程课时合计 ${hours} 与价格版本 ${selected.hours} 不一致`;
  if (Math.abs(amount - Number(selected.price ?? 0)) > 0.005)
    return `课程费用合计 ${amount} 与价格版本 ${selected.price} 不一致`;
  if (lines.some((line) => !line.curriculumId)) return "每行明细都要选择课程";
  const ids = lines.map((line) => line.curriculumId);
  if (new Set(ids).size !== ids.length) return "同一课程不能重复出现";
  return null;
}

/** 一名学员的一次多产品报名；每个产品独立冻结价格快照，服务端原子提交。 */
export function EnrollmentBatchCreator({
  schoolId,
  onCancel,
  onCreated,
  onUnauthorized,
}: {
  schoolId: string;
  onCancel: () => void;
  onCreated: () => void;
  onUnauthorized: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState("");
  const [keyword, setKeyword] = useState("");
  const [students, setStudents] = useState<StudentSummary[]>([]);
  const [studentId, setStudentId] = useState<string>();
  const [priceVersionIds, setPriceVersionIds] = useState<string[]>([]);
  const [form] = Form.useForm();
  const requestRef = useRef<{
    fingerprint: string;
    body: components["schemas"]["CreateEnrollmentBatch"];
  } | null>(null);

  const sellable = useQuery({
    queryKey: ["sellable", schoolId],
    queryFn: async ({ signal }) => {
      const on = new Date().toISOString().slice(0, 10);
      const { data, response } = await api.GET("/api/product/sellable", {
        params: { query: { schoolId, on } },
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取可售价格");
      return data;
    },
  });

  async function search(value: string) {
    setKeyword(value);
    if (!value.trim()) {
      setStudents([]);
      return;
    }
    const { data, response } = await api.GET("/api/archive/students", {
      params: { query: { search: value.trim(), size: 10 } },
    });
    if (!response.ok || !data) {
      setError("无法检索学员档案，请确认读取权限。");
      return;
    }
    setStudents(data.items ?? []);
  }

  async function save() {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (!studentId || priceVersionIds.length < 2) {
      setError("请选择学员和至少两个不同产品。");
      return;
    }
    const fingerprint = JSON.stringify({
      studentId,
      priceVersionIds,
      enrolledOn: values.enrolledOn,
    });
    if (requestRef.current?.fingerprint !== fingerprint) {
      requestRef.current = {
        fingerprint,
        body: {
          items: priceVersionIds.map((priceVersionId) => ({
            requestId: crypto.randomUUID(),
            studentId,
            priceVersionId,
            enrolledOn: values.enrolledOn,
          })),
        },
      };
    }
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/enrollment/enrollments/batch", {
        headers: { [token.headerName!]: token.token! },
        body: requestRef.current!.body,
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        if (response.status >= 500) {
          setUncertain(true);
          setError("服务器结果未确认；请保持表单不变并使用相同请求重试。");
        } else {
          requestRef.current = null;
          setUncertain(false);
          setError(
            response.status === 409
              ? "批量报名条件已变化，请检查价格和报名状态。"
              : "多产品报名失败，请核对权限与价格有效期。",
          );
        }
        return;
      }
      requestRef.current = null;
      onCreated();
    } catch {
      setUncertain(true);
      setError("网络中断，结果未确认；请使用相同内容重试以安全核对。");
    } finally {
      setBusy(false);
    }
  }

  function resetRequest() {
    if (!uncertain) requestRef.current = null;
  }

  return (
    <Modal
      title="多产品报名"
      open
      onCancel={uncertain ? undefined : onCancel}
      closable={!busy && !uncertain}
      keyboard={!busy && !uncertain}
      mask={{ closable: !busy && !uncertain }}
      footer={null}
    >
      {error ? (
        <Alert title={error} type={uncertain ? "warning" : "error"} showIcon />
      ) : null}
      <Input.Search
        placeholder="按姓名或学员号检索学员"
        allowClear
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        onSearch={(value) => void search(value)}
        disabled={busy || uncertain}
      />
      <Select
        aria-label="选择学员"
        style={{ width: "100%", marginTop: 8 }}
        value={studentId}
        onChange={(value) => {
          setStudentId(value);
          resetRequest();
        }}
        options={students.map((item) => ({
          value: item.id,
          label: `${item.studentNo} ${item.name}`,
        }))}
        placeholder="请先检索并选择学员"
        disabled={busy || uncertain}
      />
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 12 }}
        initialValues={{ enrolledOn: new Date().toISOString().slice(0, 10) }}
        onValuesChange={resetRequest}
      >
        <Form.Item
          label="报名日期"
          name="enrolledOn"
          rules={[{ required: true, message: "请输入报名日期" }]}
        >
          <Input placeholder="YYYY-MM-DD" disabled={busy || uncertain} />
        </Form.Item>
        <Form.Item
          label="可售产品（至少两个）"
          name="priceVersionIds"
          rules={[
            { required: true, message: "请选择至少两个产品" },
            {
              validator: async (_, value: string[]) => {
                if (!value || value.length >= 2) return;
                throw new Error("请选择至少两个产品");
              },
            },
          ]}
        >
          <Select
            mode="multiple"
            showSearch
            optionFilterProp="label"
            value={priceVersionIds}
            onChange={(values) => {
              setPriceVersionIds(values);
              resetRequest();
            }}
            options={(sellable.data ?? []).map((item) => ({
              value: item.priceVersionId,
              label: `${item.productName}：${item.price} 元 / ${item.hours} 课时`,
            }))}
            placeholder="选择不同产品的已发布价格"
            disabled={busy || uncertain}
          />
        </Form.Item>
      </Form>
      <Typography.Text type="secondary">
        系统会为每个产品分别冻结价格与课时；任一产品校验失败时，本批报名整体回滚。
        {uncertain ? "请勿更改表单，使用同一请求重试。" : ""}
      </Typography.Text>
      <div style={{ marginTop: 12 }}>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          提交多产品报名
        </Button>
        <Button
          style={{ marginLeft: 8 }}
          onClick={onCancel}
          disabled={busy || uncertain}
        >
          取消
        </Button>
      </div>
    </Modal>
  );
}

/** 登记报名：学员来自档案检索，商品来自可售快照。 */
export function EnrollmentCreator({
  schoolId,
  onCancel,
  onCreated,
}: {
  schoolId: string;
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [keyword, setKeyword] = useState("");
  const [students, setStudents] = useState<StudentSummary[]>([]);
  const [studentId, setStudentId] = useState<string>();
  const [priceVersionId, setPriceVersionId] = useState<string>();
  const [lines, setLines] = useState<
    { curriculumId?: string; hours?: string; amount?: string }[]
  >([]);
  const [form] = Form.useForm();

  const sellable = useQuery({
    queryKey: ["sellable", schoolId],
    queryFn: async ({ signal }) => {
      const on = new Date().toISOString().slice(0, 10);
      const { data, response } = await api.GET("/api/product/sellable", {
        params: { query: { schoolId, on } },
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取可售价格");
      return data;
    },
  });

  const catalog = useQuery({
    queryKey: ["enrollment-curricula", schoolId],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/product/catalog", {
        params: { query: { schoolId } },
        signal,
      });
      if (!response.ok || !data) return [];
      return data.curricula ?? [];
    },
  });

  const selected = (sellable.data ?? []).find(
    (item) => item.priceVersionId === priceVersionId,
  );

  /** 选中价格版本后给出默认的一条课程明细（课程/课时/费用取快照），再按需要拆分。 */
  function pickPrice(value: string) {
    setPriceVersionId(value);
    const item = (sellable.data ?? []).find(
      (entry) => entry.priceVersionId === value,
    );
    setLines(
      item
        ? [
            {
              curriculumId: item.curriculumId,
              hours: item.hours,
              amount: item.price,
            },
          ]
        : [],
    );
  }

  /** 客户端先校验合计，避免把不符合快照的明细发到服务端。 */

  async function search(value: string) {
    setKeyword(value);
    if (!value.trim()) {
      setStudents([]);
      return;
    }
    const { data, response } = await api.GET("/api/archive/students", {
      params: { query: { search: value.trim(), size: 10 } },
    });
    if (!response.ok || !data) {
      setError("无法检索学员档案，请确认账号具备学员读取权限。");
      return;
    }
    setStudents(data.items ?? []);
  }

  async function save() {
    const values = await form.validateFields();
    if (!studentId) {
      setError("请先选择学员");
      return;
    }
    const problem = validateCourseLines(lines, selected);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const split = lines.length > 1;
      const { response } = await api.POST("/api/enrollment/enrollments", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: crypto.randomUUID(),
          studentId,
          priceVersionId: values.priceVersionId,
          enrolledOn: values.enrolledOn,
          courses: split
            ? lines.map((line) => ({
                curriculumId: line.curriculumId!,
                hours: Number(line.hours).toFixed(2),
                amount: Number(line.amount).toFixed(2),
              }))
            : undefined,
        },
      });
      if (!response.ok) {
        setError(
          response.status === 409
            ? "该价格版本当前不可售，或请求重复。"
            : "报名登记失败，请核对权限与价格有效期。",
        );
        return;
      }
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="登记报名"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Input.Search
        placeholder="按姓名或学员号检索学员"
        allowClear
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        onSearch={(value) => void search(value)}
      />
      <Select
        aria-label="选择学员"
        style={{ width: "100%", marginTop: 8 }}
        value={studentId}
        onChange={(value) => setStudentId(value)}
        options={students.map((item) => ({
          value: item.id,
          label: `${item.studentNo} ${item.name}`,
        }))}
        placeholder="请先检索并选择学员"
        notFoundContent="先输入姓名或学员号检索"
        disabled={busy}
      />
      <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
        <Form.Item
          label="可售产品"
          name="priceVersionId"
          rules={[{ required: true, message: "请选择产品价格" }]}
        >
          <Select
            value={priceVersionId}
            onChange={pickPrice}
            options={(sellable.data ?? []).map((item) => ({
              value: item.priceVersionId,
              label: `${item.productName}：${item.price} 元 / ${item.hours} 课时`,
            }))}
            disabled={busy}
            notFoundContent="该学校当前没有可售价格"
          />
        </Form.Item>
        {selected ? (
          <Card size="small" title="课程明细" style={{ marginBottom: 12 }}>
            {lines.map((line, index) => (
              <Space
                key={index}
                align="baseline"
                wrap
                style={{ marginBottom: 8 }}
              >
                <Select
                  aria-label={`第 ${index + 1} 行课程`}
                  style={{ width: 180 }}
                  value={line.curriculumId}
                  options={(catalog.data ?? []).map((item) => ({
                    value: item.id,
                    label: item.name,
                  }))}
                  onChange={(value) =>
                    setLines((current) =>
                      current.map((entry, position) =>
                        position === index
                          ? { ...entry, curriculumId: value }
                          : entry,
                      ),
                    )
                  }
                  disabled={busy}
                />
                <InputNumber
                  aria-label={`第 ${index + 1} 行课时`}
                  min={0.01}
                  precision={2}
                  value={line.hours ? Number(line.hours) : undefined}
                  onChange={(value) =>
                    setLines((current) =>
                      current.map((entry, position) =>
                        position === index
                          ? { ...entry, hours: String(value ?? "") }
                          : entry,
                      ),
                    )
                  }
                  disabled={busy}
                />
                <InputNumber
                  aria-label={`第 ${index + 1} 行费用`}
                  min={0}
                  precision={2}
                  value={line.amount ? Number(line.amount) : undefined}
                  onChange={(value) =>
                    setLines((current) =>
                      current.map((entry, position) =>
                        position === index
                          ? { ...entry, amount: String(value ?? "") }
                          : entry,
                      ),
                    )
                  }
                  disabled={busy}
                />
                <Button
                  size="small"
                  danger
                  disabled={busy || lines.length === 1}
                  onClick={() =>
                    setLines((current) => current.filter((_, i) => i !== index))
                  }
                >
                  删除
                </Button>
              </Space>
            ))}
            <Space>
              <Button
                size="small"
                disabled={busy}
                onClick={() => setLines((current) => [...current, {}])}
              >
                添加课程
              </Button>
              <Typography.Text type="secondary">
                合计需等于 {selected.hours} 课时 / {selected.price} 元
              </Typography.Text>
            </Space>
          </Card>
        ) : null}
        <Form.Item
          label="报名日期"
          name="enrolledOn"
          rules={[{ required: true, message: "请输入报名日期" }]}
          initialValue={new Date().toISOString().slice(0, 10)}
        >
          <Input maxLength={10} disabled={busy} />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          确认登记
        </Button>
      </Form>
    </Modal>
  );
}

/** 报名详情：商品快照、未收金额、收款与分配。 */
export function EnrollmentDetail({
  id,
  canDiscount,
  onClose,
  onUnauthorized,
}: {
  id: string;
  canDiscount: boolean;
  onClose: () => void;
  onUnauthorized: () => void;
}) {
  const [error, setError] = useState("");
  const [receipting, setReceipting] = useState(false);
  const [discounting, setDiscounting] = useState(false);
  const [reducing, setReducing] = useState(false);
  const detail = useQuery({
    queryKey: ["enrollment", id],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/enrollment/enrollments/{id}",
        {
          params: { path: { id } },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取报名详情");
      return data;
    },
  });
  const outstanding = useQuery({
    queryKey: ["outstanding", id],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/funds/outstanding", {
        params: { query: { enrollmentId: id } },
        signal,
      });
      if (!response.ok) return null;
      return data;
    },
  });
  const allocations = useQuery({
    queryKey: ["allocations", id],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/funds/allocations", {
        params: { query: { enrollmentId: id } },
        signal,
      });
      if (!response.ok) return [];
      return data;
    },
  });
  const studentId = detail.data?.studentId;
  const receipts = useQuery({
    queryKey: ["receipts", studentId],
    enabled: !!studentId,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/funds/receipts", {
        params: { query: { studentId: studentId! } },
        signal,
      });
      if (!response.ok) return [];
      return data;
    },
  });
  const balance = useQuery({
    queryKey: ["balance", studentId],
    enabled: !!studentId,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/funds/balance", {
        params: { query: { studentId: studentId! } },
        signal,
      });
      if (!response.ok) return null;
      return data;
    },
  });

  const available = (receipts.data ?? []).filter(
    (item) => item.status === "CONFIRMED" && Number(item.availableAmount) > 0,
  );

  return (
    <Drawer title="报名详情" open size="large" onClose={onClose}>
      {error ? <Alert title={error} type="error" /> : null}
      {detail.error ? (
        <Alert title={detail.error.message} type="error" />
      ) : null}
      {detail.data ? (
        <>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="学员">
              {detail.data.studentName}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              {STATUS_LABEL[detail.data.status ?? ""] ?? detail.data.status}
            </Descriptions.Item>
            <Descriptions.Item label="产品">
              {detail.data.productName}
            </Descriptions.Item>
            <Descriptions.Item label="课程">
              {detail.data.curriculumName}
            </Descriptions.Item>
            <Descriptions.Item label="冻结价格">
              {detail.data.price}
            </Descriptions.Item>
            <Descriptions.Item label="冻结课时">
              {detail.data.hours}
            </Descriptions.Item>
            <Descriptions.Item label="净应收">
              {detail.data.netAmount}
            </Descriptions.Item>
            <Descriptions.Item label="未收金额">
              {outstanding.data ?? "—"}
            </Descriptions.Item>
          </Descriptions>
          <Card
            size="small"
            title={`账户余额：${balance.data?.total ?? "—"}（已分配 ${balance.data?.allocated ?? "—"}）`}
            style={{ marginTop: 16 }}
          >
            <Space wrap>
              <Button onClick={() => setReceipting(true)}>确认收款</Button>
              {canDiscount ? (
                <>
                  <Button onClick={() => setDiscounting(true)}>登记减免</Button>
                  <Button onClick={() => setReducing(true)}>批准减款</Button>
                </>
              ) : null}
              <AllocateAction
                enrollmentId={id}
                outstanding={outstanding.data ?? null}
                receipts={available}
                onDone={() => {
                  void outstanding.refetch();
                  void receipts.refetch();
                  void balance.refetch();
                  void allocations.refetch();
                }}
                onError={setError}
              />
            </Space>
            <Table
              rowKey="id"
              size="small"
              style={{ marginTop: 12 }}
              dataSource={receipts.data ?? []}
              pagination={false}
              columns={[
                { title: "收款", dataIndex: "amount" },
                { title: "可用", dataIndex: "availableAmount" },
                { title: "方式", dataIndex: "method" },
                { title: "日期", dataIndex: "occurredOn" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (status: string) =>
                    status === "CONFIRMED" ? (
                      <Tag color="green">已确认</Tag>
                    ) : (
                      <Tag>已撤销</Tag>
                    ),
                },
                {
                  title: "操作",
                  key: "actions",
                  render: (_: unknown, receipt: Receipt) => (
                    <VoidReceiptButton
                      receipt={receipt}
                      onDone={() => {
                        void receipts.refetch();
                        void balance.refetch();
                        void outstanding.refetch();
                      }}
                      onError={setError}
                    />
                  ),
                },
              ]}
              locale={{ emptyText: "该学员还没有收款记录" }}
            />
          </Card>
          <Card size="small" title="课程明细" style={{ marginTop: 16 }}>
            <Table
              rowKey="curriculumId"
              size="small"
              pagination={false}
              dataSource={detail.data.courses ?? []}
              columns={[
                { title: "课程", dataIndex: "curriculumName" },
                { title: "课时", dataIndex: "hours" },
                { title: "费用", dataIndex: "amount" },
                { title: "已退课时", dataIndex: "cancelledHours" },
                { title: "已退费用", dataIndex: "cancelledAmount" },
              ]}
              locale={{ emptyText: "按价格版本所属课程自动生成一条明细" }}
            />
          </Card>
          <Card size="small" title="退款与现金退出" style={{ marginTop: 16 }}>
            <RefundPanel
              enrollmentId={id}
              studentId={studentId!}
              schoolId={detail.data.schoolId!}
              courses={detail.data.courses ?? []}
              onUnauthorized={onUnauthorized}
              onChanged={() => {
                void outstanding.refetch();
                void balance.refetch();
                void allocations.refetch();
                void receipts.refetch();
              }}
            />
          </Card>
          <Card size="small" title="分配记录" style={{ marginTop: 16 }}>
            <Table
              rowKey="id"
              size="small"
              dataSource={allocations.data ?? []}
              pagination={false}
              columns={[
                { title: "金额", dataIndex: "amount" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (status: string) =>
                    status === "ACTIVE" ? (
                      <Tag color="green">占用中</Tag>
                    ) : (
                      <Tag>已释放</Tag>
                    ),
                },
                {
                  title: "操作",
                  key: "actions",
                  render: (_: unknown, allocation: Allocation) =>
                    allocation.status === "ACTIVE" ? (
                      <ReleaseButton
                        allocation={allocation}
                        onDone={() => {
                          void allocations.refetch();
                          void outstanding.refetch();
                          void receipts.refetch();
                          void balance.refetch();
                        }}
                        onError={setError}
                      />
                    ) : null,
                },
              ]}
              locale={{ emptyText: "还没有分配" }}
            />
          </Card>
        </>
      ) : null}
      {discounting ? (
        <DiscountModal
          enrollmentId={id}
          expectedVersion={detail.data?.version ?? 0}
          onCancel={() => setDiscounting(false)}
          onDone={() => {
            setDiscounting(false);
            void detail.refetch();
            void outstanding.refetch();
          }}
          onError={setError}
        />
      ) : null}
      {reducing ? (
        <DiscountModal
          enrollmentId={id}
          expectedVersion={detail.data?.version ?? 0}
          reduction
          onCancel={() => setReducing(false)}
          onDone={() => {
            setReducing(false);
            void detail.refetch();
            void outstanding.refetch();
          }}
          onError={setError}
        />
      ) : null}
      {receipting && studentId ? (
        <ReceiptCreator
          schoolId={detail.data!.schoolId!}
          studentId={studentId}
          onCancel={() => setReceipting(false)}
          onCreated={() => {
            setReceipting(false);
            void receipts.refetch();
            void balance.refetch();
            void outstanding.refetch();
          }}
        />
      ) : null}
    </Drawer>
  );
}

export function ReceiptCreator({
  schoolId,
  studentId,
  onCancel,
  onCreated,
}: {
  schoolId: string;
  studentId: string;
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/funds/receipts", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId: crypto.randomUUID(),
          schoolId,
          studentId,
          amount: String(values.amount),
          method: values.method ?? "CASH",
          occurredOn: values.occurredOn,
        },
      });
      if (!response.ok) {
        setError(
          response.status === 409
            ? "同一请求已处理过。"
            : "收款确认失败，请核对权限与金额。",
        );
        return;
      }
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="确认收款"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical" initialValues={{ method: "CASH" }}>
        <Form.Item
          label="收款金额（元）"
          name="amount"
          rules={[{ required: true, message: "请输入金额" }]}
        >
          <InputNumber
            min={0.01}
            precision={2}
            style={{ width: "100%" }}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item label="收款方式" name="method">
          <Select
            options={[
              { value: "CASH", label: "现金" },
              { value: "BANK", label: "银行转账" },
              { value: "OTHER", label: "其他" },
            ]}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item
          label="收款日期"
          name="occurredOn"
          rules={[{ required: true, message: "请输入日期" }]}
          initialValue={new Date().toISOString().slice(0, 10)}
        >
          <Input maxLength={10} disabled={busy} />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          确认收款
        </Button>
      </Form>
    </Modal>
  );
}

export function AllocateAction({
  enrollmentId,
  outstanding,
  receipts,
  onDone,
  onError,
}: {
  enrollmentId: string;
  outstanding: string | null;
  receipts: Receipt[];
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/funds/allocations", {
        headers: { [token.headerName!]: token.token! },
        body: {
          receiptId: values.receiptId,
          enrollmentId,
          amount: String(values.amount),
        },
      });
      if (response.status === 409) {
        onError("超出可用收款或报名未收金额，请核对后重试。");
        return;
      }
      if (!response.ok) {
        onError("分配失败，请核对分配权限。");
        return;
      }
      setOpen(false);
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)} disabled={receipts.length === 0}>
        分配收款
      </Button>
      <Modal
        title="分配收款到本报名"
        open={open}
        onCancel={() => setOpen(false)}
        mask={{ closable: !busy }}
        footer={null}
      >
        <Typography.Text type="secondary">
          未收金额：{outstanding ?? "—"}
        </Typography.Text>
        <Form form={form} layout="vertical">
          <Form.Item
            label="收款记录"
            name="receiptId"
            rules={[{ required: true, message: "请选择收款" }]}
          >
            <Select
              options={receipts.map((item) => ({
                value: item.id,
                label: `${item.occurredOn} 可用 ${item.availableAmount} 元`,
              }))}
              disabled={busy}
            />
          </Form.Item>
          <Form.Item
            label="分配金额（元）"
            name="amount"
            rules={[{ required: true, message: "请输入金额" }]}
          >
            <InputNumber
              min={0.01}
              precision={2}
              style={{ width: "100%" }}
              disabled={busy}
            />
          </Form.Item>
          <Button
            type="primary"
            onClick={() => void save()}
            loading={busy}
            disabled={busy}
          >
            确认分配
          </Button>
        </Form>
      </Modal>
    </>
  );
}

export function ReleaseButton({
  allocation,
  onDone,
  onError,
}: {
  allocation: Allocation;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function release() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/funds/allocations/{id}/release",
        {
          params: { path: { id: allocation.id! } },
          headers: { [token.headerName!]: token.token! },
        },
      );
      if (!response.ok) {
        onError("释放失败，请核对分配权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button size="small" disabled={busy} onClick={() => void release()}>
      释放
    </Button>
  );
}

export function VoidReceiptButton({
  receipt,
  onDone,
  onError,
}: {
  receipt: Receipt;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function voidIt() {
    setBusy(true);
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/funds/receipts/{id}/void", {
        params: { path: { id: receipt.id! } },
        headers: { [token.headerName!]: token.token! },
        body: { expectedVersion: receipt.version },
      });
      if (response.status === 409) {
        onError("该收款已被分配或已变化，无法撤销。");
        return;
      }
      if (!response.ok) {
        onError("撤销失败，请核对权限。");
        return;
      }
      onDone();
    } catch {
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button
      size="small"
      danger
      disabled={busy || receipt.status !== "CONFIRMED"}
      onClick={() => void voidIt()}
    >
      撤销
    </Button>
  );
}

/** 登记一次性减免：独立授权、审批单（策略要求时）和报名版本共同防止并发改价。 */
export function DiscountModal({
  enrollmentId,
  expectedVersion,
  reduction = false,
  onCancel,
  onDone,
  onError,
}: {
  enrollmentId: string;
  expectedVersion: number;
  reduction?: boolean;
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const request = useRef<{
    requestId: string;
    amount: string;
    reason?: string;
    expectedVersion: number;
    approvalId?: string;
    action?: string;
  } | null>(null);
  const [form] = Form.useForm();

  async function save() {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (busy) return;
    setBusy(true);
    try {
      const token = await csrf();
      const base = {
        requestId: crypto.randomUUID(),
        amount: String(values.amount),
        reason: values.reason || undefined,
        expectedVersion,
        approvalId: values.approvalId || undefined,
      };
      let response;
      if (reduction) {
        const payload = (request.current ??= {
          ...base,
          action: values.action,
        });
        ({ response } = await api.POST(
          "/api/enrollment/enrollments/{id}/reductions",
          {
            params: { path: { id: enrollmentId } },
            headers: { [token.headerName!]: token.token! },
            body: payload as typeof base & {
              action: string;
              requestId: string;
            },
          },
        ));
      } else {
        const payload = (request.current ??= {
          ...base,
          requestId: base.requestId,
        });
        ({ response } = await api.POST(
          "/api/enrollment/enrollments/{id}/discounts",
          {
            params: { path: { id: enrollmentId } },
            headers: { [token.headerName!]: token.token! },
            body: payload,
          },
        ));
      }
      if (response.status === 409) {
        request.current = null;
        setUncertain(false);
        onError("当前状态不允许减免，请检查分配、消费、退款及财务期间。");
        return;
      }
      if (!response.ok) {
        if (response.status >= 500) setUncertain(true);
        else {
          request.current = null;
          setUncertain(false);
        }
        onError("登记减免失败，请核对优惠权限、审批单和报名版本。");
        return;
      }
      onDone();
    } catch {
      setUncertain(true);
      onError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={reduction ? "登记批准减款" : "登记减免"}
      open
      onCancel={uncertain ? undefined : onCancel}
      closable={!busy && !uncertain}
      keyboard={!busy && !uncertain}
      mask={{ closable: !busy && !uncertain }}
      footer={null}
    >
      <Form form={form} layout="vertical">
        {reduction ? (
          <Form.Item
            label="减款类别"
            name="action"
            initialValue="ENROLLMENT_REDUCTION"
            rules={[{ required: true, message: "请选择减款类别" }]}
          >
            <Select
              disabled={busy || uncertain}
              options={[
                { value: "ENROLLMENT_REDUCTION", label: "报名金额减款" },
                { value: "SERVICE_FEE_REDUCTION", label: "服务费减款" },
              ]}
            />
          </Form.Item>
        ) : null}
        <Form.Item
          label="减免金额（元）"
          name="amount"
          rules={[{ required: true, message: "请输入金额" }]}
        >
          <InputNumber
            min={0.01}
            precision={2}
            style={{ width: "100%" }}
            disabled={busy || uncertain}
          />
        </Form.Item>
        <Form.Item label="原因" name="reason">
          <Input maxLength={200} disabled={busy || uncertain} />
        </Form.Item>
        <Form.Item label="已通过的审批单号（如策略要求）" name="approvalId">
          <Input disabled={busy || uncertain} />
        </Form.Item>
        <Typography.Text type="secondary">
          已发生消费或退课后暂不支持直接减款；多分配金额会原子释放回学员账户。
          {uncertain ? "上次结果未确认，请使用相同内容重试。" : ""}
        </Typography.Text>
        <div style={{ marginTop: 12 }}>
          <Button
            type="primary"
            onClick={() => void save()}
            loading={busy}
            disabled={busy}
          >
            {reduction ? "确认减款" : "确认减免"}
          </Button>
        </div>
      </Form>
    </Modal>
  );
}
