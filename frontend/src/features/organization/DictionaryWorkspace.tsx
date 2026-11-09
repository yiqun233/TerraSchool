import { useRef, useState } from "react";
import {
  Alert,
  Button,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Table,
  Typography,
} from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import {
  Page,
  PageHeader,
  PageState,
  Panel,
  StatusTag,
  Toolbar,
} from "../../shared/ui/Page";

type Item = components["schemas"]["Item"];
type CreateItem = components["schemas"]["CreateItem"];

export function DictionaryWorkspace({ identityKey }: { identityKey: string }) {
  const cache = useQueryClient();
  const [schoolId, setSchoolId] = useState("");
  const [dictionaryCode, setDictionaryCode] = useState("CUSTOMER_STATUS");
  const [includeInactive, setIncludeInactive] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<Item>();
  const [createForm] = Form.useForm<CreateItem>();
  const [editForm] = Form.useForm<{
    label: string;
    sortOrder: number;
    active: boolean;
  }>();
  const pendingCreate = useRef<
    | {
        payload: CreateItem;
        key: string;
        requestId: string;
      }
    | undefined
  >(undefined);
  const inFlight = useRef(false);

  const capabilities = useQuery({
    queryKey: ["organization-capabilities", identityKey],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/capabilities",
        { signal },
      );
      if (!response.ok) throw new Error("当前账号没有组织维护权限");
      return data;
    },
  });
  const schools = useQuery({
    queryKey: ["dictionary-schools", identityKey],
    enabled: !!capabilities.data,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/school-options",
        { signal, params: { query: { search: "" } } },
      );
      if (!response.ok) throw new Error("无法读取学校选项");
      const allowed = new Set(capabilities.data?.editableSchoolIds ?? []);
      return (data ?? []).filter((school) => allowed.has(school.id ?? ""));
    },
  });
  const availableSchools = schools.data ?? [];
  const effectiveSchoolId = schoolId || availableSchools[0]?.id || "";
  const validDictionaryCode = /^[A-Z][A-Z0-9_]{0,63}$/.test(dictionaryCode);
  const items = useQuery({
    queryKey: [
      "organization-dictionary-items",
      identityKey,
      effectiveSchoolId,
      dictionaryCode,
      includeInactive,
    ],
    enabled: !!effectiveSchoolId && validDictionaryCode,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items",
        {
          signal,
          params: {
            path: { schoolId: effectiveSchoolId, dictionaryCode },
            query: { includeInactive },
          },
        },
      );
      if (!response.ok) throw new Error("无法读取字典项，请核对学校权限");
      return data ?? [];
    },
  });

  async function create(values: CreateItem) {
    if (inFlight.current || !effectiveSchoolId || !validDictionaryCode) return;
    inFlight.current = true;
    setBusy(true);
    setError("");
    try {
      const payload = {
        itemCode: values.itemCode?.trim().toUpperCase(),
        label: values.label?.trim(),
        sortOrder: values.sortOrder ?? 0,
      };
      const key = JSON.stringify([effectiveSchoolId, dictionaryCode, payload]);
      if (pendingCreate.current?.key !== key)
        pendingCreate.current = {
          key,
          payload,
          requestId: crypto.randomUUID(),
        };
      const token = await csrf();
      const result = await api.POST(
        "/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items",
        {
          params: { path: { schoolId: effectiveSchoolId, dictionaryCode } },
          headers: { [token.headerName!]: token.token! },
          body: {
            ...pendingCreate.current.payload,
            requestId: pendingCreate.current.requestId,
          },
        },
      );
      if (!result.response.ok) {
        if (result.response.status >= 500 || result.response.status === 408)
          throw new Error("提交结果未确认；请保留输入并重试同一条请求");
        pendingCreate.current = undefined;
        throw new Error(
          result.response.status === 409
            ? "字典代码已存在或请求身份与原提交不符"
            : "新增被拒绝，请检查权限和输入",
        );
      }
      pendingCreate.current = undefined;
      createForm.resetFields();
      await cache.invalidateQueries({
        queryKey: ["organization-dictionary-items"],
      });
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  function beginEdit(item: Item) {
    setEditing(item);
    editForm.setFieldsValue({
      label: item.label ?? "",
      sortOrder: item.sortOrder ?? 0,
      active: item.active ?? true,
    });
  }

  async function update(values: {
    label: string;
    sortOrder: number;
    active: boolean;
  }) {
    if (!editing || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError("");
    try {
      const token = await csrf();
      const result = await api.POST(
        "/api/organization/dictionary-items/{id}/update",
        {
          params: { path: { id: editing.id! } },
          headers: { [token.headerName!]: token.token! },
          body: {
            expectedVersion: editing.version,
            label: values.label.trim(),
            sortOrder: values.sortOrder ?? 0,
            active: values.active,
          },
        },
      );
      if (!result.response.ok)
        throw new Error(
          result.response.status === 409
            ? "字典项已被其他人修改，请刷新后重新编辑"
            : "保存被拒绝，请检查权限和输入",
        );
      setEditing(undefined);
      await cache.invalidateQueries({
        queryKey: ["organization-dictionary-items"],
      });
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  if (capabilities.isPending || schools.isPending)
    return (
      <PageState
        title="统一字典"
        description="正在确认当前账号的字典维护范围…"
        loading
      />
    );
  if (capabilities.error || schools.error)
    return (
      <PageState
        title="统一字典"
        stateTitle="无法读取字典维护权限"
        stateHint={(capabilities.error ?? schools.error)?.message}
        action={
          <Button
            onClick={() => {
              void capabilities.refetch();
              void schools.refetch();
            }}
          >
            重试
          </Button>
        }
      />
    );
  if (availableSchools.length === 0)
    return (
      <PageState
        title="统一字典"
        stateTitle="当前范围内没有可维护的学校"
        stateHint="需要组织维护权限与学校范围；请联系机构管理员开通。"
      />
    );

  return (
    <Page>
      <PageHeader
        title="统一字典"
        description="按学校维护稳定代码与显示名称。代码创建后不可改名或复用；停用保留历史引用。"
      />
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Toolbar>
        <Select
          aria-label="学校"
          style={{ width: 240 }}
          value={effectiveSchoolId || undefined}
          loading={schools.isFetching}
          placeholder="选择学校"
          options={availableSchools.map((school) => ({
            value: school.id,
            label: school.name,
          }))}
          onChange={(value) => setSchoolId(value)}
        />
        <Input
          aria-label="字典代码"
          style={{ width: 220 }}
          value={dictionaryCode}
          maxLength={64}
          onChange={(event) =>
            setDictionaryCode(event.target.value.toUpperCase())
          }
        />
        <Checkbox
          checked={includeInactive}
          onChange={(event) => setIncludeInactive(event.target.checked)}
        >
          包含已停用
        </Checkbox>
      </Toolbar>
      <Typography.Text type="secondary">
        按学校隔离维护；字典项代码创建后不可改名或复用，停用保留历史引用。
      </Typography.Text>
      {!validDictionaryCode ? (
        <Alert
          title="字典代码须为大写字母、数字和下划线，且以字母开头"
          type="warning"
        />
      ) : null}
      <Panel ariaLabel="字典项列表">
        <div style={{ padding: 16, borderBottom: "1px solid var(--ts-line)" }}>
          <Form
            form={createForm}
            layout="inline"
            onFinish={create}
            disabled={busy}
          >
            <Form.Item
              name="itemCode"
              rules={[
                { required: true, message: "请输入稳定代码" },
                {
                  pattern: /^[A-Za-z][A-Za-z0-9_]{0,63}$/,
                  message: "代码须以字母开头",
                },
              ]}
            >
              <Input
                aria-label="新增代码"
                placeholder="稳定代码"
                maxLength={64}
              />
            </Form.Item>
            <Form.Item
              name="label"
              rules={[
                { required: true, whitespace: true, message: "请输入显示名称" },
              ]}
            >
              <Input
                aria-label="新增名称"
                placeholder="显示名称"
                maxLength={100}
              />
            </Form.Item>
            <Form.Item name="sortOrder" initialValue={0}>
              <InputNumber aria-label="新增排序" min={0} max={1_000_000} />
            </Form.Item>
            <Button
              htmlType="submit"
              type="primary"
              loading={busy}
              disabled={!validDictionaryCode || !effectiveSchoolId}
            >
              新增字典项
            </Button>
          </Form>
        </div>
        <Table<Item>
          rowKey={(item) => item.id ?? ""}
          size="small"
          loading={items.isFetching}
          dataSource={items.data ?? []}
          columns={[
            { title: "代码", dataIndex: "itemCode" },
            { title: "名称", dataIndex: "label" },
            { title: "排序", dataIndex: "sortOrder" },
            {
              title: "状态",
              dataIndex: "active",
              render: (active: boolean) =>
                active ? (
                  <StatusTag tone="ok">启用</StatusTag>
                ) : (
                  <StatusTag tone="neutral">停用</StatusTag>
                ),
            },
            {
              title: "版本",
              dataIndex: "version",
            },
            {
              title: "操作",
              render: (_: unknown, item: Item) => (
                <Button onClick={() => beginEdit(item)}>编辑</Button>
              ),
            },
          ]}
          locale={{ emptyText: "当前字典代码暂无字典项" }}
        />
      </Panel>
      <Modal
        title={`编辑字典项 ${editing?.itemCode ?? ""}`}
        open={!!editing}
        onCancel={() => setEditing(undefined)}
        onOk={() => void editForm.submit()}
        confirmLoading={busy}
      >
        <Form
          form={editForm}
          layout="vertical"
          onFinish={update}
          disabled={busy}
        >
          <Form.Item
            label="显示名称"
            name="label"
            rules={[{ required: true, whitespace: true }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder" rules={[{ required: true }]}>
            <InputNumber min={0} max={1_000_000} style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item label="状态" name="active" valuePropName="checked">
            <Checkbox>启用</Checkbox>
          </Form.Item>
        </Form>
      </Modal>
    </Page>
  );
}
