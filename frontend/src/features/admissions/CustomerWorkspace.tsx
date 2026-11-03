import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import { CustomerFollowups } from "./CustomerFollowups";

type Customer = components["schemas"]["Customer"];
type Pending = {
  requestId: string;
  schoolId: string;
  fingerprint: string;
  channelId: string | null;
};

/** 浏览器仅保存请求身份与摘要，不持久化客户姓名或电话。 */
async function fingerprint(requestId: string, values: unknown[]) {
  const bytes = new TextEncoder().encode(
    JSON.stringify([requestId, ...values]),
  );
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash), (value) =>
    value.toString(16).padStart(2, "0"),
  ).join("");
}
function readPending(key: string): Pending | null {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    if (
      typeof value === "object" &&
      value !== null &&
      "requestId" in value &&
      "schoolId" in value &&
      "fingerprint" in value &&
      "channelId" in value &&
      typeof value.requestId === "string" &&
      typeof value.schoolId === "string" &&
      typeof value.fingerprint === "string" &&
      (value.channelId === null || typeof value.channelId === "string")
    )
      return {
        requestId: value.requestId,
        schoolId: value.schoolId,
        fingerprint: value.fingerprint,
        channelId: value.channelId,
      };
  } catch {
    /* 读取失败时后续持久化仍会检查。 */
  }
  return null;
}

export function CustomerWorkspace({
  accountKey,
  onUnauthorized,
}: {
  accountKey: string;
  onUnauthorized: () => void;
}) {
  const storageKey = `teraschool.customer-create.${accountKey}`;
  const client = useQueryClient();
  const [pending, setPending] = useState<Pending | null>(() =>
    readPending(storageKey),
  );
  const [schoolId, setSchoolId] = useState<string>();
  const [page, setPage] = useState(0);
  const [channelPage, setChannelPage] = useState(0);
  const [channelId, setChannelId] = useState<string | undefined>(
    () => pending?.channelId ?? undefined,
  );
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [duplicate, setDuplicate] = useState<{ token: string } | null>(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [followupTarget, setFollowupTarget] = useState<string | null>(null);
  const [phoneDetail, setPhoneDetail] = useState<string | null>(null);
  const guard = useRef(false);
  const schools = useQuery({
    queryKey: ["customer-schools"],
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/capabilities",
        { signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data)
        throw new Error("无法读取客户权限，请联系管理员。");
      return data.schools ?? [];
    },
  });
  const visibleSchools = schools.error ? [] : (schools.data ?? []);
  const current = pending?.schoolId ?? schoolId ?? visibleSchools[0]?.id;
  const currentSchool = visibleSchools.find((item) => item.id === current);
  const rows = useQuery({
    queryKey: ["customers", current, page],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/admissions/customers", {
        params: { query: { schoolId: current!, page, size: 20 } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data)
        throw new Error("无法读取本人客户，请核对权限并刷新。");
      return data;
    },
  });
  const channels = useQuery({
    queryKey: ["customer-channel-options", current, channelPage],
    enabled: !!current && !!currentSchool?.maintainable,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/admissions/channels", {
        params: { query: { schoolId: current!, page: channelPage, size: 20 } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (response.status === 403) return null;
      if (!response.ok || !data)
        throw new Error("无法读取渠道，仍可不选择渠道录入。");
      return data;
    },
  });
  const canEdit =
    !!currentSchool?.maintainable && !schools.error && !rows.error;
  const visibleChannels = channels.error ? null : channels.data;
  const channelUnavailable = !!channelId && !visibleChannels;
  useEffect(() => {
    if (!currentSchool?.phoneReadable || schools.error || rows.error) {
      setPhoneDetail(null);
      setFollowupTarget(null);
    }
  }, [currentSchool?.phoneReadable, schools.error, rows.error]);
  function editName(value: string) {
    setName(value);
    setDuplicate(null);
  }
  function editPhone(value: string) {
    setPhone(value);
    setDuplicate(null);
  }
  async function create(confirmationToken?: string) {
    if (guard.current || !current || !canEdit || channelUnavailable) return;
    const cleanName = name.trim(),
      cleanPhone = phone.trim();
    if (!cleanName || !cleanPhone) {
      setNotice("请填写客户姓名和联系电话。");
      return;
    }
    guard.current = true;
    setBusy(true);
    setNotice("");
    let sent = false;
    try {
      const requestId = pending?.requestId ?? crypto.randomUUID();
      const digest = await fingerprint(requestId, [
        current,
        cleanName,
        cleanPhone,
        channelId ?? null,
      ]);
      if (pending && digest !== pending.fingerprint) {
        setNotice("输入与待确认请求不一致，请重新填写原姓名、电话和渠道。");
        return;
      }
      const token = await csrf();
      const next = {
        requestId,
        schoolId: current,
        fingerprint: digest,
        channelId: channelId ?? null,
      };
      localStorage.setItem(storageKey, JSON.stringify(next));
      setPending(next);
      sent = true;
      const { data, response } = await api.POST("/api/admissions/customers", {
        headers: { [token.headerName!]: token.token! },
        body: {
          requestId,
          schoolId: current,
          name: cleanName,
          phone: cleanPhone,
          channelId,
          confirmationToken,
        },
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        if (!pending && response.status < 500) {
          localStorage.removeItem(storageKey);
          setPending(null);
          setNotice("录入被拒绝，请核对权限、电话和渠道状态。");
        } else {
          setDuplicate(null);
          setNotice("请求未完成或结果未确认，请保留请求并核对原始内容后重试。");
        }
        return;
      }
      if (
        data?.status === "DUPLICATE_CONFIRMATION_REQUIRED" &&
        data.confirmationToken
      ) {
        setDuplicate({ token: data.confirmationToken });
        setNotice(
          "本人有完整电话权限的学校中有相同号码，请核对后决定是否继续录入。",
        );
        return;
      }
      if (data?.status === "CANCELLED") {
        localStorage.removeItem(storageKey);
        setPending(null);
        setDuplicate(null);
        setNotice("该请求已取消，未录入客户。");
        return;
      }
      if (data?.status !== "CREATED" || !data.customer) {
        setNotice("请求结果未确认，请使用相同内容重试。");
        return;
      }
      localStorage.removeItem(storageKey);
      setPending(null);
      setName("");
      setPhone("");
      setChannelId(undefined);
      setDuplicate(null);
      setPage(0);
      setNotice("客户已录入，仅创建人可查看。");
      await client.invalidateQueries({ queryKey: ["customers", current] });
    } catch {
      setNotice(
        sent
          ? "请求结果未确认，请使用相同内容重试。"
          : "无法准备安全请求，请检查浏览器及网络后重试。",
      );
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  async function cancel() {
    if (!pending || guard.current) return;
    const cleanName = name.trim(),
      cleanPhone = phone.trim();
    if (!cleanName || !cleanPhone) {
      setNotice("请先填写原姓名、电话和渠道以核对待取消请求。");
      return;
    }
    guard.current = true;
    setBusy(true);
    setNotice("");
    try {
      const digest = await fingerprint(pending.requestId, [
        pending.schoolId,
        cleanName,
        cleanPhone,
        channelId ?? null,
      ]);
      if (digest !== pending.fingerprint) {
        setNotice("输入与待确认请求不一致，请重新填写原姓名、电话和渠道。");
        return;
      }
      const token = await csrf();
      const { data, response } = await api.POST(
        "/api/admissions/customers/cancel",
        {
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId: pending.requestId,
            schoolId: pending.schoolId,
            name: cleanName,
            phone: cleanPhone,
            channelId,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (
        !response.ok ||
        !data ||
        !["CANCELLED", "CREATED"].includes(data.status ?? "")
      ) {
        setNotice("取消结果未确认，请保留原请求并稍后重试。");
        return;
      }
      localStorage.removeItem(storageKey);
      setPending(null);
      setDuplicate(null);
      setName("");
      setPhone("");
      setChannelId(undefined);
      setNotice(
        data.status === "CANCELLED"
          ? "该请求已取消，未录入客户。"
          : "原请求已录入客户，请在列表中核对。",
      );
      await client.invalidateQueries({
        queryKey: ["customers", pending.schoolId],
      });
    } catch {
      setNotice("取消结果未确认，请保留原请求并稍后重试。");
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  async function reveal(id: string) {
    if (guard.current) return;
    guard.current = true;
    setBusy(true);
    setNotice("");
    try {
      const token = await csrf();
      const { data, response } = await api.POST(
        "/api/admissions/customers/{id}/phone",
        {
          params: { path: { id } },
          headers: { [token.headerName!]: token.token! },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data?.phone) {
        setNotice("无法查看完整电话，请核对当前权限。");
        return;
      }
      setPhoneDetail(data.phone);
    } catch {
      setNotice("查看电话失败，请重试。");
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  return (
    <section>
      <Typography.Paragraph>
        这里只显示您创建的客户。电话默认脱敏，查看完整号码和录入查重需要单独授权；查重只覆盖本人有完整电话权限的学校。
      </Typography.Paragraph>
      {schools.error ? (
        <Alert type="error" title={schools.error.message} showIcon />
      ) : null}
      {currentSchool && !currentSchool.phoneReadable ? (
        <Alert
          type="info"
          title="录入和查重需要该学校的完整电话查看权限；当前只能查看脱敏列表。"
          showIcon
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {pending ? (
        <Alert
          type="warning"
          showIcon
          title="存在待确认的请求。重新进入后请填写原姓名和电话；原渠道已保留，可重试或取消该请求。"
          action={
            <Button disabled={busy} onClick={() => void cancel()}>
              取消此请求
            </Button>
          }
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {notice ? (
        <Alert
          type="info"
          title={notice}
          showIcon
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {duplicate ? (
        <Alert
          type="warning"
          title="本人有完整电话权限的学校中发现相同号码，是否仍要录入？"
          action={
            <Space>
              <Button disabled={busy} onClick={() => void cancel()}>
                取消录入
              </Button>
              <Button
                onClick={() => void create(duplicate.token)}
                loading={busy}
                disabled={!canEdit || channelUnavailable}
              >
                确认重复仍录入
              </Button>
            </Space>
          }
          showIcon
          style={{ marginBottom: 12 }}
        />
      ) : null}
      <Space wrap>
        <Select
          aria-label="客户所属学校"
          style={{ minWidth: 180 }}
          value={current}
          disabled={busy || !!pending}
          options={visibleSchools.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
          onChange={(value) => {
            setSchoolId(value);
            setPage(0);
            setChannelPage(0);
            setChannelId(undefined);
            setPhoneDetail(null);
            setFollowupTarget(null);
            setDuplicate(null);
          }}
        />
        {canEdit || pending ? (
          <>
            <Input
              aria-label="客户姓名"
              placeholder="客户姓名"
              maxLength={50}
              value={name}
              disabled={busy || !!duplicate}
              onChange={(event) => editName(event.target.value)}
              style={{ width: 150 }}
            />
            <Input
              aria-label="客户电话"
              placeholder="联系电话"
              maxLength={30}
              value={phone}
              disabled={busy || !!duplicate}
              onChange={(event) => editPhone(event.target.value)}
              style={{ width: 170 }}
            />
            {visibleChannels ? (
              <Select
                aria-label="客户渠道"
                allowClear
                placeholder="渠道（可选）"
                value={channelId}
                disabled={busy || !!duplicate || !!pending}
                style={{ width: 180 }}
                options={(visibleChannels.items ?? [])
                  .filter((item) => item.active || item.id === channelId)
                  .map((item) => ({ value: item.id, label: item.name }))}
                onChange={(value) => {
                  setChannelId(value);
                  setDuplicate(null);
                }}
              />
            ) : null}
            {visibleChannels && (channelPage > 0 || visibleChannels.hasMore) ? (
              <Space>
                <Button
                  disabled={channelPage === 0 || busy || !!pending}
                  onClick={() => setChannelPage(channelPage - 1)}
                >
                  上一组渠道
                </Button>
                <Button
                  disabled={!visibleChannels.hasMore || busy || !!pending}
                  onClick={() => setChannelPage(channelPage + 1)}
                >
                  下一组渠道
                </Button>
              </Space>
            ) : null}
            {channelUnavailable && !pending ? (
              <Button
                disabled={busy}
                onClick={() => {
                  setChannelId(undefined);
                  setDuplicate(null);
                }}
              >
                不选择渠道
              </Button>
            ) : null}
            <Button
              type="primary"
              loading={busy}
              disabled={!canEdit || channelUnavailable}
              onClick={() => void create()}
            >
              {pending ? "重试录入" : "录入客户"}
            </Button>
          </>
        ) : null}
      </Space>
      {channels.error ? (
        <Alert
          type="warning"
          title={channels.error.message}
          showIcon
          style={{ marginTop: 12 }}
        />
      ) : null}
      {rows.error ? (
        <Alert
          type="error"
          title={rows.error.message}
          showIcon
          style={{ marginTop: 12 }}
        />
      ) : null}
      <Table<Customer>
        rowKey={(row) => row.id!}
        loading={rows.isFetching}
        dataSource={schools.error || rows.error ? [] : (rows.data?.items ?? [])}
        pagination={false}
        style={{ marginTop: 16 }}
        columns={[
          { title: "客户姓名", dataIndex: "name" },
          { title: "联系电话", dataIndex: "maskedPhone" },
          {
            title: "操作",
            render: (_, row) =>
              currentSchool?.phoneReadable &&
              !rows.error &&
              !schools.error &&
              row.id ? (
                <Space>
                  <Button disabled={busy} onClick={() => void reveal(row.id!)}>
                    查看完整电话
                  </Button>
                  <Button
                    disabled={busy}
                    onClick={() => setFollowupTarget(row.id!)}
                  >
                    跟进记录
                  </Button>
                </Space>
              ) : null,
          },
        ]}
      />
      <Space style={{ marginTop: 12 }}>
        <Button
          disabled={page === 0 || busy}
          onClick={() => {
            setPage(page - 1);
            setPhoneDetail(null);
          }}
        >
          上一页
        </Button>
        <span>第 {page + 1} 页</span>
        <Button
          disabled={
            !!rows.error || !!schools.error || !rows.data?.hasMore || busy
          }
          onClick={() => {
            setPage(page + 1);
            setPhoneDetail(null);
          }}
        >
          下一页
        </Button>
      </Space>
      {followupTarget &&
      currentSchool?.phoneReadable &&
      !schools.error &&
      !rows.error ? (
        <CustomerFollowups
          key={followupTarget}
          customerId={followupTarget}
          accountKey={accountKey}
          canEdit={canEdit}
          onClose={() => setFollowupTarget(null)}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
      <Modal
        title="完整联系电话"
        open={
          phoneDetail !== null &&
          !!currentSchool?.phoneReadable &&
          !schools.error &&
          !rows.error
        }
        onCancel={() => setPhoneDetail(null)}
        footer={<Button onClick={() => setPhoneDetail(null)}>关闭</Button>}
      >
        <Typography.Text>{phoneDetail}</Typography.Text>
      </Modal>
    </section>
  );
}
