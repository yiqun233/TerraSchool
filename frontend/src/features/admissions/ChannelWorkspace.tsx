import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import {
  Page,
  PageHeader,
  PageState,
  Panel,
  SimplePager,
  StatusTag,
} from "../../shared/ui/Page";
import { ChannelAddress } from "./ChannelAddress";
import { newRequestId } from "../../shared/ids";

type Channel = components["schemas"]["Channel"];
type Draft = { id: string; name: string; active: boolean; version: number };

/** 渠道基础管理；请求结果不确定时保留创建身份，编辑结果不确定时要求重新读取。 */
export function ChannelWorkspace({
  onUnauthorized,
  accountKey,
}: {
  onUnauthorized: () => void;
  accountKey: string;
}) {
  const client = useQueryClient();
  const [schoolId, setSchoolId] = useState<string>();
  const [page, setPage] = useState(0);
  const [name, setName] = useState("");
  const [addressId, setAddressId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const storageKey = `teraschool.channel-create.${accountKey}`;
  const [pending, setPending] = useState<{
    requestId: string;
    schoolId: string;
    name: string;
  } | null>(() => {
    try {
      const stored = localStorage.getItem(storageKey);
      if (!stored) return null;
      const value: unknown = JSON.parse(stored);
      if (
        typeof value === "object" &&
        value !== null &&
        "requestId" in value &&
        "schoolId" in value &&
        "name" in value &&
        typeof value.requestId === "string" &&
        typeof value.schoolId === "string" &&
        typeof value.name === "string"
      )
        return {
          requestId: value.requestId,
          schoolId: value.schoolId,
          name: value.name,
        };
      return null;
    } catch {
      return null;
    }
  });
  const guard = useRef(false);
  const schools = useQuery({
    queryKey: ["channel-schools"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/admissions/channels/capabilities",
        { signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data)
        throw new Error("无法读取渠道权限，请联系管理员授权。");
      return data.schools ?? [];
    },
  });
  const visibleSchools = schools.error ? [] : (schools.data ?? []);
  const current = pending?.schoolId ?? schoolId ?? visibleSchools[0]?.id;
  const editable =
    !schools.error &&
    !!visibleSchools.find((school) => school.id === current)?.maintainable;
  const rows = useQuery({
    queryKey: ["channels", current, page],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/admissions/channels", {
        params: { query: { schoolId: current!, page, size: 20 } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data)
        throw new Error("无法读取渠道，请检查学校范围或刷新重试。");
      return data;
    },
  });
  const canEdit = editable && !rows.error;
  async function create() {
    if (guard.current || !current) return;
    const input = pending ?? {
      requestId: newRequestId(),
      schoolId: current,
      name: name.trim(),
    };
    if (!input.name) {
      setNotice("请填写渠道名称。");
      return;
    }
    guard.current = true;
    setBusy(true);
    setNotice("");
    let sent = false;
    try {
      const token = await csrf();
      localStorage.setItem(storageKey, JSON.stringify(input));
      setPending(input);
      sent = true;
      const { response } = await api.POST("/api/admissions/channels", {
        headers: { [token.headerName!]: token.token! },
        body: input,
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        if (response.status < 500 && !pending) {
          localStorage.removeItem(storageKey);
          setPending(null);
          setNotice("创建被拒绝，请核对权限和渠道名称。");
        } else if (response.status < 500)
          setNotice(
            "本次重试被拒绝，首次结果仍未确认；请恢复权限或核对记录后再处理。",
          );
        else setNotice("结果未确认，请保留当前内容并使用同一请求重试。");
        return;
      }
      localStorage.removeItem(storageKey);
      setPending(null);
      setName("");
      setPage(0);
      setNotice("渠道已创建。");
      await client.invalidateQueries({
        queryKey: ["channels", input.schoolId],
      });
    } catch {
      if (sent) setNotice("结果未确认，请保留当前内容并使用同一请求重试。");
      else setNotice("未取得安全令牌或无法保存重试信息，请检查浏览器后重试。");
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  async function save() {
    if (!draft || guard.current) return;
    guard.current = true;
    setBusy(true);
    setNotice("");
    let sent = false;
    try {
      const token = await csrf();
      sent = true;
      const { response } = await api.POST(
        "/api/admissions/channels/{id}/update",
        {
          params: { path: { id: draft.id } },
          headers: { [token.headerName!]: token.token! },
          body: {
            name: draft.name.trim(),
            active: draft.active,
            expectedVersion: draft.version,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setNotice(
          response.status === 409
            ? "渠道已被修改，请刷新后重新编辑。"
            : "保存未完成，请刷新后核对当前状态。",
        );
      } else setNotice("渠道已更新。");
      setDraft(null);
      await client.invalidateQueries({ queryKey: ["channels", current] });
    } catch {
      setNotice(
        sent
          ? "保存结果未确认，请刷新核对后重新编辑。"
          : "未取得安全令牌，请重试。",
      );
      if (sent) {
        setDraft(null);
        await client.invalidateQueries({ queryKey: ["channels", current] });
      }
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }

  if (schools.isPending)
    return (
      <PageState
        title="招生渠道"
        description="正在确认当前账号的渠道权限…"
        loading
      />
    );
  if (schools.error)
    return (
      <PageState
        title="招生渠道"
        description="正在确认当前账号的渠道权限。"
        stateTitle="无法读取渠道权限"
        stateHint={schools.error.message}
        action={<Button onClick={() => void schools.refetch()}>重试</Button>}
      />
    );
  if (visibleSchools.length === 0)
    return (
      <PageState
        title="招生渠道"
        stateTitle="当前范围内没有可查看的学校"
        stateHint="请先建校并给账号分配学校范围，招生渠道才会可用。"
      />
    );

  return (
    <Page>
      <PageHeader
        title="招生渠道"
        description="维护学校招生渠道名称和启用状态；历史渠道停用后仍保留，已产生的客户来源不会丢失。"
        actions={
          <Button onClick={() => void rows.refetch()} loading={rows.isFetching}>
            刷新
          </Button>
        }
      />
      {pending ? (
        <Alert
          type="warning"
          showIcon
          title={`渠道“${pending.name}”的创建结果待确认，请使用同一请求重试；重新登录同一账号后仍可恢复。`}
        />
      ) : null}
      {notice ? <Alert type="info" title={notice} showIcon /> : null}
      {rows.error ? (
        <Alert type="error" title={rows.error.message} showIcon />
      ) : null}
      <Panel
        ariaLabel="招生渠道列表"
        head={
          <Select
            aria-label="学校"
            style={{ minWidth: 200 }}
            value={current}
            disabled={busy || !!pending}
            options={visibleSchools.map((school) => ({
              value: school.id,
              label: school.name,
            }))}
            onChange={(value) => {
              setSchoolId(value);
              setAddressId(null);
              setPage(0);
            }}
          />
        }
        headEnd={
          canEdit ? (
            <Space>
              <Input
                aria-label="渠道名称"
                placeholder="渠道名称"
                maxLength={50}
                value={name}
                disabled={busy || !!pending}
                onChange={(event) => setName(event.target.value)}
                style={{ width: 220 }}
              />
              <Button
                type="primary"
                loading={busy}
                onClick={() => void create()}
              >
                {pending ? "重试创建" : "新建渠道"}
              </Button>
            </Space>
          ) : undefined
        }
        footer={
          <SimplePager
            page={page}
            hasMore={!!rows.data?.hasMore}
            onPrev={() => setPage(page - 1)}
            onNext={() => setPage(page + 1)}
            disabled={busy || !!pending || !!rows.error}
          />
        }
      >
        <Table<Channel>
          rowKey={(row) => row.id!}
          loading={rows.isFetching}
          dataSource={
            schools.error || rows.error ? [] : (rows.data?.items ?? [])
          }
          pagination={false}
          columns={[
            { title: "渠道名称", dataIndex: "name" },
            {
              title: "状态",
              width: 110,
              render: (_, row) =>
                row.active ? (
                  <StatusTag tone="ok">启用</StatusTag>
                ) : (
                  <StatusTag tone="neutral">停用</StatusTag>
                ),
            },
            {
              title: "操作",
              render: (_, row) =>
                row.id && !rows.error && !schools.error ? (
                  <Space>
                    <Button
                      disabled={busy || !!pending}
                      onClick={() => setAddressId(row.id!)}
                    >
                      地址
                    </Button>
                    {canEdit && row.version != null ? (
                      <Button
                        disabled={busy || !!pending}
                        onClick={() =>
                          setDraft({
                            id: row.id!,
                            name: row.name ?? "",
                            active: !!row.active,
                            version: row.version!,
                          })
                        }
                      >
                        编辑
                      </Button>
                    ) : null}
                  </Space>
                ) : null,
            },
          ]}
        />
      </Panel>
      {addressId && current && !schools.error && !rows.error ? (
        <ChannelAddress
          key={addressId}
          id={addressId}
          schoolId={current}
          canEdit={canEdit}
          onClose={() => setAddressId(null)}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
      <Modal
        title="编辑渠道"
        open={!!draft}
        onCancel={() => !busy && setDraft(null)}
        onOk={() => void save()}
        confirmLoading={busy}
      >
        {draft ? (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Input
              aria-label="编辑渠道名称"
              maxLength={50}
              value={draft.name}
              disabled={busy}
              onChange={(event) =>
                setDraft({ ...draft, name: event.target.value })
              }
            />
            <Space>
              启用{" "}
              <Switch
                checked={draft.active}
                disabled={busy}
                onChange={(active) => setDraft({ ...draft, active })}
              />
            </Space>
          </Space>
        ) : null}
      </Modal>
    </Page>
  );
}
