import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
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

type Capabilities = components["schemas"]["ProductCapabilities"];
type Curriculum = components["schemas"]["Curriculum"];
type CourseProduct = components["schemas"]["CourseProduct"];
type PriceVersion = components["schemas"]["PriceVersion"];
type CreateCurriculum = components["schemas"]["CreateCurriculum"];
type DraftPriceVersion = components["schemas"]["DraftPriceVersion"];

const MODE_LABEL: Record<number, string> = {
  0: "自动选择",
  1: "平均价格",
  2: "课程匹配",
  3: "完全匹配",
  4: "平均价格·退课时后实际费用",
  5: "完全匹配·退课时后实际费用",
};
const STATUS_LABEL: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  RETIRED: "已停用",
};

/**
 * 课程与产品工作台。
 *
 * 金额与课时由服务端以十进制字符串返回与校验，页面只展示、不参与权威计算；发布使用独立权限，
 * 已发布的价格版本不可改写，改价需要起草新版本再发布。
 */
export function ProductCatalogWorkspace({
  onUnauthorized,
}: {
  onUnauthorized: () => void;
}) {
  const [schoolId, setSchoolId] = useState<string>();
  const [creatingCurriculum, setCreatingCurriculum] = useState(false);
  const [creatingProduct, setCreatingProduct] = useState(false);
  const [draftingFor, setDraftingFor] = useState<CourseProduct | null>(null);
  const [historyFor, setHistoryFor] = useState<CourseProduct | null>(null);
  const [error, setError] = useState("");

  const capabilities = useQuery({
    queryKey: ["product-capabilities"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/product/capabilities", {
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取课程产品权限");
      return data;
    },
  });
  const current = schoolId ?? capabilities.data?.schools?.[0]?.id;
  const school = capabilities.data?.schools?.find(
    (item) => item.id === current,
  );
  const catalog = useQuery({
    queryKey: ["product-catalog", current],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/product/catalog", {
        params: { query: { schoolId: current! } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data)
        throw new Error("无法读取课程与产品，请核对当前权限");
      return data;
    },
  });

  if (capabilities.error)
    return <Alert title={capabilities.error.message} type="error" />;
  if (capabilities.isPending) return <p role="status">正在读取课程产品权限…</p>;
  if (!capabilities.data?.canRead)
    return <Alert title="账号当前无课程产品读取权限" type="error" />;
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
                setCreatingCurriculum(true);
              }}
            >
              新建课程
            </Button>
            <Button
              onClick={() => {
                setError("");
                setCreatingProduct(true);
              }}
            >
              新建产品
            </Button>
          </>
        ) : null}
        <Button
          onClick={() => void catalog.refetch()}
          loading={catalog.isFetching}
        >
          刷新
        </Button>
        <Typography.Text type="secondary">
          价格与课时由服务端校验，发布后不可改写
        </Typography.Text>
      </Space>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      {catalog.error ? (
        <Alert title={catalog.error.message} type="error" />
      ) : null}
      <Card title="课程" size="small">
        <Table
          rowKey="id"
          size="small"
          loading={catalog.isPending}
          dataSource={catalog.data?.curricula ?? []}
          pagination={false}
          columns={[
            { title: "名称", dataIndex: "name" },
            { title: "类型", dataIndex: "category" },
            { title: "科目", dataIndex: "subject" },
            {
              title: "状态",
              dataIndex: "active",
              render: (active: boolean) =>
                active ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
            },
          ]}
          locale={{ emptyText: "该学校还没有课程" }}
        />
      </Card>
      <Card title="产品与价格" size="small" style={{ marginTop: 16 }}>
        <Table
          rowKey="id"
          size="small"
          loading={catalog.isPending}
          dataSource={catalog.data?.products ?? []}
          pagination={false}
          columns={[
            { title: "产品", dataIndex: "name" },
            { title: "课程", dataIndex: "curriculumName" },
            {
              title: "结算模式",
              dataIndex: "settlementMode",
              render: (mode: number) => MODE_LABEL[mode] ?? mode,
            },
            {
              title: "当前价格",
              render: (_: unknown, product: CourseProduct) =>
                product.publishedPrice
                  ? `${product.publishedPrice.price} 元 / ${product.publishedPrice.hours} 课时`
                  : "未发布",
            },
            {
              title: "状态",
              dataIndex: "active",
              render: (active: boolean) =>
                active ? <Tag color="green">在售</Tag> : <Tag>停售</Tag>,
            },
            {
              title: "操作",
              key: "actions",
              render: (_: unknown, product: CourseProduct) => (
                <Space>
                  {school?.maintainable ? (
                    <Button
                      size="small"
                      onClick={() => setDraftingFor(product)}
                    >
                      起草价格
                    </Button>
                  ) : null}
                  <Button size="small" onClick={() => setHistoryFor(product)}>
                    价格版本
                  </Button>
                </Space>
              ),
            },
          ]}
          locale={{ emptyText: "该学校还没有产品" }}
        />
      </Card>
      {creatingCurriculum ? (
        <CurriculumCreator
          schools={schools.filter((item) => item.maintainable)}
          schoolId={current!}
          onCancel={() => setCreatingCurriculum(false)}
          onCreated={() => {
            setCreatingCurriculum(false);
            void catalog.refetch();
          }}
        />
      ) : null}
      {creatingProduct && catalog.data ? (
        <ProductCreator
          schoolId={current!}
          curricula={catalog.data.curricula ?? []}
          onCancel={() => setCreatingProduct(false)}
          onCreated={() => {
            setCreatingProduct(false);
            void catalog.refetch();
          }}
        />
      ) : null}
      {draftingFor ? (
        <PriceDrafter
          product={draftingFor}
          onCancel={() => setDraftingFor(null)}
          onDrafted={() => {
            setDraftingFor(null);
            void catalog.refetch();
            setHistoryFor(draftingFor);
          }}
        />
      ) : null}
      {historyFor ? (
        <PriceHistory
          product={historyFor}
          canPublish={!!capabilities.data?.canPublish}
          onClose={() => {
            setHistoryFor(null);
            void catalog.refetch();
          }}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
    </>
  );
}

/** 新建课程；结果不确定时保留同一请求身份重试。 */
export function CurriculumCreator({
  schools,
  schoolId,
  onCancel,
  onCreated,
}: {
  schools: Capabilities["schools"];
  schoolId: string;
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();
  const pending = useRef<CreateCurriculum | null>(null);

  async function save() {
    const values = await form.validateFields();
    const body: CreateCurriculum = {
      requestId: pending.current?.requestId ?? crypto.randomUUID(),
      schoolId,
      name: values.name,
      category: values.category || undefined,
      subject: values.subject || undefined,
    };
    if (!pending.current) pending.current = body;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/product/curricula", {
        headers: { [token.headerName!]: token.token! },
        body: pending.current,
      });
      if (!response.ok) {
        if (response.status >= 500 || response.status === 408) {
          setError("结果尚未确认，请使用相同内容重试。");
        } else if (response.status === 409) {
          pending.current = null;
          setError("该学校已有同名课程，或请求号与内容不一致。");
        } else {
          pending.current = null;
          setError("课程创建失败，请核对权限与必填项。");
        }
        return;
      }
      pending.current = null;
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="新建课程"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical">
        <Form.Item
          label="课程名称"
          name="name"
          rules={[
            { required: true, whitespace: true, message: "请输入课程名称" },
          ]}
        >
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Form.Item label="课程类型" name="category">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Form.Item label="科目" name="subject">
          <Input maxLength={50} disabled={busy} />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          创建课程
        </Button>
        <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
          可选学校：
          {(schools ?? []).map((item) => item.name).join("、") || "无"}
        </Typography.Text>
      </Form>
    </Modal>
  );
}

/** 新建产品；课程必须属于同一学校。 */
export function ProductCreator({
  schoolId,
  curricula,
  onCancel,
  onCreated,
}: {
  schoolId: string;
  curricula: Curriculum[];
  onCancel: () => void;
  onCreated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();
  const pending = useRef<components["schemas"]["CreateProduct"] | null>(null);

  async function save() {
    const values = await form.validateFields();
    const body: components["schemas"]["CreateProduct"] = {
      requestId: pending.current?.requestId ?? crypto.randomUUID(),
      schoolId,
      curriculumId: values.curriculumId,
      name: values.name,
      settlementMode: values.settlementMode ?? 1,
    };
    if (!pending.current) pending.current = body;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/product/products", {
        headers: { [token.headerName!]: token.token! },
        body: pending.current,
      });
      if (!response.ok) {
        if (response.status >= 500 || response.status === 408) {
          setError("结果尚未确认，请使用相同内容重试。");
        } else if (response.status === 409) {
          pending.current = null;
          setError("该学校已有同名产品，或请求号与内容不一致。");
        } else {
          pending.current = null;
          setError("产品创建失败，请核对课程归属与权限。");
        }
        return;
      }
      pending.current = null;
      onCreated();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="新建产品"
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical" initialValues={{ settlementMode: 1 }}>
        <Form.Item
          label="所属课程"
          name="curriculumId"
          rules={[{ required: true, message: "请选择课程" }]}
        >
          <Select
            options={curricula.map((item) => ({
              value: item.id,
              label: item.name,
            }))}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item
          label="产品名称"
          name="name"
          rules={[
            { required: true, whitespace: true, message: "请输入产品名称" },
          ]}
        >
          <Input maxLength={100} disabled={busy} />
        </Form.Item>
        <Form.Item label="结算模式" name="settlementMode">
          <Select
            options={Object.entries(MODE_LABEL).map(([value, label]) => ({
              value: Number(value),
              label,
            }))}
            disabled={busy}
          />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          创建产品
        </Button>
      </Form>
    </Modal>
  );
}

/** 起草价格版本：金额与课时以字符串提交，服务端校验精度。 */
export function PriceDrafter({
  product,
  onCancel,
  onDrafted,
}: {
  product: CourseProduct;
  onCancel: () => void;
  onDrafted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm();
  const pending = useRef<DraftPriceVersion | null>(null);

  async function save() {
    const values = await form.validateFields();
    const body: DraftPriceVersion = {
      requestId: pending.current?.requestId ?? crypto.randomUUID(),
      productId: product.id!,
      price: String(values.price),
      hours: String(values.hours),
      serviceFee: String(values.serviceFee ?? 0),
      settlementMode: values.settlementMode ?? product.settlementMode ?? 1,
      studyDays: values.studyDays ?? undefined,
      effectiveFrom: values.effectiveFrom,
      effectiveTo: values.effectiveTo || undefined,
    };
    if (!pending.current) pending.current = body;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const { response } = await api.POST("/api/product/price-versions", {
        headers: { [token.headerName!]: token.token! },
        body: pending.current,
      });
      if (!response.ok) {
        if (response.status >= 500 || response.status === 408) {
          setError("结果尚未确认，请使用相同内容重试。");
        } else {
          pending.current = null;
          setError("价格草稿创建失败，请核对金额、课时与日期。");
        }
        return;
      }
      pending.current = null;
      onDrafted();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`起草价格：${product.name}`}
      open
      onCancel={onCancel}
      mask={{ closable: !busy }}
      footer={null}
    >
      {error ? <Alert title={error} type="error" /> : null}
      <Form form={form} layout="vertical">
        <Form.Item
          label="价格（元）"
          name="price"
          rules={[{ required: true, message: "请输入价格" }]}
        >
          <Input placeholder="1000.00" maxLength={16} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="购买课时"
          name="hours"
          rules={[{ required: true, message: "请输入课时" }]}
        >
          <Input placeholder="10.00" maxLength={12} disabled={busy} />
        </Form.Item>
        <Form.Item label="服务费（元）" name="serviceFee">
          <Input placeholder="0.00" maxLength={16} disabled={busy} />
        </Form.Item>
        <Form.Item
          label="结算模式"
          name="settlementMode"
          extra="当前只实现了模式 1（平均价格）；发布其他模式会被拒绝，避免按错误公式计算消费与退费。"
        >
          <Select
            options={Object.entries(MODE_LABEL)
              .filter(([value]) => Number(value) === 1)
              .map(([value, label]) => ({ value: Number(value), label }))}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item label="学习期限（天）" name="studyDays">
          <InputNumber
            min={1}
            max={3650}
            disabled={busy}
            style={{ width: "100%" }}
          />
        </Form.Item>
        <Form.Item
          label="生效日期"
          name="effectiveFrom"
          rules={[{ required: true, message: "请输入生效日期" }]}
        >
          <Input placeholder="YYYY-MM-DD" maxLength={10} disabled={busy} />
        </Form.Item>
        <Form.Item label="失效日期" name="effectiveTo">
          <Input
            placeholder="留空表示长期有效"
            maxLength={10}
            disabled={busy}
          />
        </Form.Item>
        <Button
          type="primary"
          onClick={() => void save()}
          loading={busy}
          disabled={busy}
        >
          保存草稿
        </Button>
      </Form>
    </Modal>
  );
}

/** 价格版本历史；发布与停用需要独立 PRICE_PUBLISH 权限。 */
export function PriceHistory({
  product,
  canPublish,
  onClose,
  onUnauthorized,
}: {
  product: CourseProduct;
  canPublish: boolean;
  onClose: () => void;
  onUnauthorized: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const versions = useQuery({
    queryKey: ["price-versions", product.id],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/product/price-versions", {
        params: { query: { productId: product.id! } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok || !data) throw new Error("无法读取价格版本");
      return data;
    },
  });

  async function action(
    version: PriceVersion,
    operation: "publish" | "retire",
  ) {
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const request = {
        params: { path: { id: version.id! } },
        headers: { [token.headerName!]: token.token! },
        body: { expectedVersion: version.version },
      };
      const { response } =
        operation === "publish"
          ? await api.POST("/api/product/price-versions/{id}/publish", request)
          : await api.POST("/api/product/price-versions/{id}/retire", request);
      if (response.status === 409) {
        setError("版本已变化，请重新读取后再操作。");
        await versions.refetch();
        return;
      }
      if (!response.ok) {
        setError("操作失败，请核对定价发布权限。");
        return;
      }
      await versions.refetch();
    } catch {
      setError("无法获取安全令牌或网络中断，请重试。");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Drawer
      title={`价格版本：${product.name}`}
      open
      size="large"
      onClose={onClose}
    >
      {error ? <Alert title={error} type="error" /> : null}
      {versions.error ? (
        <Alert title={versions.error.message} type="error" />
      ) : null}
      <Table
        rowKey="id"
        size="small"
        loading={versions.isPending}
        dataSource={versions.data ?? []}
        pagination={false}
        columns={[
          { title: "版本", dataIndex: "versionNo" },
          {
            title: "价格/课时",
            render: (_: unknown, version: PriceVersion) =>
              `${version.price} 元 / ${version.hours} 课时`,
          },
          {
            title: "有效期",
            render: (_: unknown, version: PriceVersion) =>
              `${version.effectiveFrom} ~ ${version.effectiveTo ?? "长期"}`,
          },
          {
            title: "状态",
            dataIndex: "status",
            render: (status: string) => (
              <Tag
                color={
                  status === "PUBLISHED"
                    ? "green"
                    : status === "DRAFT"
                      ? "blue"
                      : undefined
                }
              >
                {STATUS_LABEL[status] ?? status}
              </Tag>
            ),
          },
          {
            title: "操作",
            key: "actions",
            render: (_: unknown, version: PriceVersion) =>
              canPublish ? (
                version.status === "DRAFT" ? (
                  <Button
                    size="small"
                    disabled={busy}
                    onClick={() => void action(version, "publish")}
                  >
                    发布
                  </Button>
                ) : version.status === "PUBLISHED" ? (
                  <Button
                    size="small"
                    danger
                    disabled={busy}
                    onClick={() => void action(version, "retire")}
                  >
                    停用
                  </Button>
                ) : null
              ) : (
                <Typography.Text type="secondary">无发布权限</Typography.Text>
              ),
          },
        ]}
        locale={{ emptyText: "该产品还没有价格版本" }}
      />
    </Drawer>
  );
}
