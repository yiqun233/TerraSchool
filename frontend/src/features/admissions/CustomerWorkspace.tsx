import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Typography,
} from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";
import { CustomerFollowups } from "./CustomerFollowups";

type Customer = components["schemas"]["Customer"];
type MergedCustomer = components["schemas"]["MergedCustomer"];
type CustomerStudentLink = components["schemas"]["CustomerStudentLinkView"];
type CustomerConversion = components["schemas"]["CustomerConversionResult"];
type CustomerRecyclePolicy = components["schemas"]["CustomerRecyclePolicy"];
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
  const [poolPage, setPoolPage] = useState(0);
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
  const [mergeSource, setMergeSource] = useState<Customer | null>(null);
  const [conversionTarget, setConversionTarget] = useState<Customer | null>(
    null,
  );
  const [studentName, setStudentName] = useState("");
  const [contactRelation, setContactRelation] = useState("GUARDIAN");
  const [conversionError, setConversionError] = useState("");
  const [conversionResult, setConversionResult] =
    useState<CustomerConversion | null>(null);
  const [conversionBusy, setConversionBusy] = useState(false);
  const [assignmentTarget, setAssignmentTarget] = useState<Customer | null>(
    null,
  );
  const [assignmentAccount, setAssignmentAccount] = useState<string>();
  const [assignmentReason, setAssignmentReason] = useState("");
  const [assignmentRequestId, setAssignmentRequestId] = useState<string>();
  const conversionGuard = useRef(false);
  const [mergePrimaryId, setMergePrimaryId] = useState<string>();
  const [mergeHistoryFor, setMergeHistoryFor] = useState<string | null>(null);
  const [recyclePolicyOpen, setRecyclePolicyOpen] = useState(false);
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
  const reminderDate = new Date();
  const reminderThroughOn = `${reminderDate.getFullYear()}-${String(reminderDate.getMonth() + 1).padStart(2, "0")}-${String(reminderDate.getDate()).padStart(2, "0")}`;
  const reminders = useQuery({
    queryKey: ["customer-reminders", current, reminderThroughOn],
    enabled: !!current && !!currentSchool?.phoneReadable,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/reminders",
        {
          params: {
            query: {
              schoolId: current!,
              throughOn: reminderThroughOn,
              page: 0,
              size: 20,
            },
          },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data)
        throw new Error("无法读取跟进提醒，请核对权限。");
      return data;
    },
  });
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
  const poolRows = useQuery({
    queryKey: ["customer-pool", current, poolPage],
    enabled: !!current && !!currentSchool?.poolReadable,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/pool",
        {
          params: { query: { schoolId: current!, page: poolPage, size: 20 } },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data)
        throw new Error("无法读取学校公海客户，请核对权限。");
      return data;
    },
  });
  const mergedRows = useQuery({
    queryKey: ["customer-merges", mergeHistoryFor],
    enabled: !!mergeHistoryFor,
    queryFn: async ({ signal }): Promise<MergedCustomer[]> => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/{id}/merges",
        { params: { path: { id: mergeHistoryFor! } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data) throw new Error("无法读取客户合并记录");
      return data;
    },
  });
  const conversionLink = useQuery({
    queryKey: ["customer-student-link", accountKey, conversionTarget?.id],
    enabled: !!conversionTarget?.id,
    queryFn: async ({ signal }): Promise<CustomerStudentLink | null> => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/{id}/student-link",
        {
          params: { path: { id: conversionTarget!.id! } },
          signal,
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok) throw new Error("无法读取客户转学员关联");
      return data ?? null;
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
  const assignees = useQuery({
    queryKey: ["customer-assignees", current],
    enabled: !!current && !!currentSchool?.assignmentManageable,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/assignable-employees",
        { params: { query: { schoolId: current! } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效");
      }
      if (!response.ok || !data) throw new Error("无法读取可转派负责人");
      return data;
    },
  });
  const canEdit =
    !!currentSchool?.maintainable &&
    !!currentSchool.phoneReadable &&
    !schools.error &&
    !rows.error;
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
      setNotice("客户已录入，当前负责人可查看。");
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
  async function poolAction(
    id: string,
    version: number,
    action: "claim" | "release-to-pool",
  ) {
    if (guard.current || !current) return;
    guard.current = true;
    setBusy(true);
    setNotice("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        action === "claim"
          ? "/api/admissions/customers/{id}/claim"
          : "/api/admissions/customers/{id}/release-to-pool",
        {
          params: { path: { id } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: version },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setNotice(
          response.status === 409
            ? "客户归属已变化，请刷新列表后重试。"
            : "客户归属调整失败，请核对权限后重试。",
        );
        return;
      }
      setNotice(action === "claim" ? "已认领客户。" : "客户已转入公海。");
      await Promise.all([
        client.invalidateQueries({ queryKey: ["customers", current] }),
        client.invalidateQueries({ queryKey: ["customer-pool", current] }),
      ]);
    } catch {
      setNotice("操作结果未确认，请刷新本人客户和公海后核对。");
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  async function assignCustomerOwner() {
    if (
      !assignmentTarget?.id ||
      !assignmentAccount ||
      !current ||
      guard.current
    )
      return;
    const cleanReason = assignmentReason.trim();
    if (!cleanReason) return;
    const requestId = assignmentRequestId ?? crypto.randomUUID();
    setAssignmentRequestId(requestId);
    guard.current = true;
    setBusy(true);
    setNotice("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/admissions/customers/{id}/assign-owner",
        {
          params: { path: { id: assignmentTarget.id } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId,
            expectedVersion: assignmentTarget.version ?? 0,
            ownerAccountId: assignmentAccount,
            reason: cleanReason,
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
            ? "客户或负责人状态已变化，请刷新后重新操作。"
            : "转派失败，请核对权限后重试。",
        );
        return;
      }
      setAssignmentTarget(null);
      setAssignmentRequestId(undefined);
      setAssignmentReason("");
      setAssignmentAccount(undefined);
      setNotice("客户负责人已转派。");
      await client.invalidateQueries({ queryKey: ["customers", current] });
    } catch {
      setNotice("转派结果未确认；保留当前请求身份后可安全重试。 ");
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  async function mergeCustomer() {
    if (!mergeSource?.id || !mergePrimaryId || guard.current) return;
    const primary = rows.data?.items?.find(
      (item) => item.id === mergePrimaryId,
    );
    if (!primary?.id || !current) return;
    guard.current = true;
    setBusy(true);
    setNotice("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/admissions/customers/{id}/merge",
        {
          params: { path: { id: mergeSource.id } },
          headers: { [token.headerName!]: token.token! },
          body: {
            primaryId: primary.id,
            expectedSourceVersion: mergeSource.version ?? 0,
            expectedPrimaryVersion: primary.version ?? 0,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setNotice("合并未完成，请刷新客户列表并核对版本及负责人权限。");
        return;
      }
      setNotice("客户已合并；原档及跟进历史保留，可从主档的合并记录撤销。");
      setMergeSource(null);
      setMergePrimaryId(undefined);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["customers", current] }),
        client.invalidateQueries({ queryKey: ["customer-merges", primary.id] }),
      ]);
    } catch {
      setNotice("合并结果未确认，请刷新本人客户和合并记录后核对。");
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  async function reverseCustomerMerge(source: MergedCustomer) {
    if (!source.sourceId || guard.current || !current) return;
    guard.current = true;
    setBusy(true);
    setNotice("");
    try {
      const token = await csrf();
      const { response } = await api.POST(
        "/api/admissions/customers/{id}/merge/reverse",
        {
          params: { path: { id: source.sourceId } },
          headers: { [token.headerName!]: token.token! },
          body: { expectedVersion: source.version ?? 0 },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) {
        setNotice("撤销合并失败，请刷新记录后核对版本和负责人权限。");
        return;
      }
      setNotice("已撤销合并，原客户档案已恢复到本人客户列表。");
      await Promise.all([
        client.invalidateQueries({ queryKey: ["customers", current] }),
        client.invalidateQueries({
          queryKey: ["customer-merges", mergeHistoryFor],
        }),
      ]);
    } catch {
      setNotice("撤销结果未确认，请刷新本人客户和合并记录后核对。");
    } finally {
      guard.current = false;
      setBusy(false);
    }
  }
  async function convertCustomerToStudent() {
    if (!conversionTarget?.id || !current || conversionGuard.current) return;
    const cleanName = studentName.trim();
    if (!cleanName) {
      setConversionError("请填写学员姓名。");
      return;
    }
    conversionGuard.current = true;
    setConversionBusy(true);
    setConversionError("");
    try {
      const values = [
        conversionTarget.id,
        conversionTarget.version ?? 0,
        cleanName,
        contactRelation,
      ];
      const attemptKey = `teraschool.customer-conversion.${accountKey}.${conversionTarget.id}`;
      const digest = await fingerprint("customer-conversion", values);
      let requestId: string = crypto.randomUUID();
      try {
        const saved = JSON.parse(
          localStorage.getItem(attemptKey) ?? "null",
        ) as { requestId?: unknown; fingerprint?: unknown } | null;
        if (
          typeof saved?.requestId === "string" &&
          saved.fingerprint === digest
        )
          requestId = saved.requestId;
      } catch {
        /* 损坏的身份记录按新请求处理，不读取其中其他字段。 */
      }
      localStorage.setItem(
        attemptKey,
        JSON.stringify({ requestId, fingerprint: digest }),
      );
      const token = await csrf();
      const { data, response } = await api.POST(
        "/api/admissions/customers/{id}/convert-to-student",
        {
          params: { path: { id: conversionTarget.id } },
          headers: { [token.headerName!]: token.token! },
          body: {
            requestId,
            expectedCustomerVersion: conversionTarget.version ?? 0,
            campusId: undefined,
            studentName: cleanName,
            studentNameEnglish: undefined,
            sex: "UNKNOWN",
            birthday: undefined,
            certType: undefined,
            certCode: undefined,
            studentPhone: undefined,
            email: undefined,
            customerContactRelation: contactRelation,
            confirmDuplicate: false,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok || !data?.conversion?.studentId) {
        setConversionError(
          response.status === 409
            ? "客户状态已变化或已转化，请刷新并核对。"
            : "转化结果未确认。请保留本表内容后重试。",
        );
        return;
      }
      localStorage.removeItem(attemptKey);
      setConversionResult(data);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["customers", current] }),
        client.invalidateQueries({
          queryKey: ["customer-student-link", accountKey, conversionTarget.id],
        }),
      ]);
    } catch {
      setConversionError("转化结果未确认。请保留本表内容后重试。");
    } finally {
      conversionGuard.current = false;
      setConversionBusy(false);
    }
  }
  return (
    <section>
      <Typography.Paragraph>
        这里只显示您负责的客户。电话默认脱敏，查看完整号码和录入查重需要单独授权；同校公海需要独立权限，认领后成为当前负责人。可将本人同校重复客户合并到明确选择的主档，并保留撤销入口。
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
      {currentSchool?.maintainable ? (
        <Button
          onClick={() => setRecyclePolicyOpen(true)}
          style={{ marginBottom: 12 }}
        >
          自动回收规则
        </Button>
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
      {currentSchool?.phoneReadable ? (
        <Card
          title="待回访提醒（本人客户）"
          size="small"
          style={{ marginTop: 12 }}
          extra={
            <Button
              size="small"
              onClick={() => void reminders.refetch()}
              loading={reminders.isFetching}
            >
              刷新
            </Button>
          }
        >
          {reminders.error ? (
            <Alert type="error" title={reminders.error.message} showIcon />
          ) : null}
          <Table
            rowKey={(row) => row.id!}
            size="small"
            loading={reminders.isFetching}
            dataSource={reminders.data?.items ?? []}
            pagination={false}
            locale={{ emptyText: "当前没有到期提醒" }}
            columns={[
              { title: "客户", dataIndex: "customerName" },
              { title: "下次联系", dataIndex: "nextContactOn" },
              { title: "最近跟进", dataIndex: "occurredOn" },
              {
                title: "跟进摘要",
                dataIndex: "content",
                ellipsis: true,
              },
              {
                title: "操作",
                render: (_, row) =>
                  row.customerId ? (
                    <Button
                      size="small"
                      disabled={!currentSchool.phoneReadable}
                      onClick={() => setFollowupTarget(row.customerId!)}
                    >
                      查看跟进
                    </Button>
                  ) : null,
              },
            ]}
          />
        </Card>
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
              !rows.error && !schools.error && row.id ? (
                <Space>
                  {currentSchool?.phoneReadable ? (
                    <>
                      <Button
                        disabled={busy}
                        onClick={() => void reveal(row.id!)}
                      >
                        查看完整电话
                      </Button>
                      <Button
                        disabled={busy}
                        onClick={() => setFollowupTarget(row.id!)}
                      >
                        跟进记录
                      </Button>
                    </>
                  ) : null}
                  {currentSchool?.poolManageable ? (
                    <Button
                      disabled={busy}
                      onClick={() =>
                        void poolAction(
                          row.id!,
                          row.version ?? 0,
                          "release-to-pool",
                        )
                      }
                    >
                      转入公海
                    </Button>
                  ) : null}
                  {currentSchool?.assignmentManageable ? (
                    <Button
                      disabled={busy}
                      onClick={() => {
                        setAssignmentTarget(row);
                        setAssignmentAccount(undefined);
                        setAssignmentReason("");
                        setAssignmentRequestId(crypto.randomUUID());
                      }}
                    >
                      转派负责人
                    </Button>
                  ) : null}
                  {currentSchool?.maintainable ? (
                    <>
                      {currentSchool.phoneReadable ? (
                        <Button
                          disabled={busy}
                          onClick={() => {
                            setConversionTarget(row);
                            setStudentName("");
                            setContactRelation("GUARDIAN");
                            setConversionError("");
                            setConversionResult(null);
                          }}
                        >
                          转为学员
                        </Button>
                      ) : null}
                      <Button
                        disabled={busy}
                        onClick={() => {
                          setMergeSource(row);
                          setMergePrimaryId(undefined);
                        }}
                      >
                        合并客户
                      </Button>
                      <Button
                        disabled={busy}
                        onClick={() => setMergeHistoryFor(row.id!)}
                      >
                        合并记录
                      </Button>
                    </>
                  ) : null}
                </Space>
              ) : null,
          },
        ]}
      />
      {currentSchool?.poolReadable ? (
        <Card title="学校公海" size="small" style={{ marginTop: 20 }}>
          {poolRows.error ? (
            <Alert type="error" title={poolRows.error.message} showIcon />
          ) : null}
          <Table<Customer>
            rowKey={(row) => row.id!}
            loading={poolRows.isFetching}
            dataSource={poolRows.data?.items ?? []}
            pagination={false}
            locale={{ emptyText: "当前学校公海暂无客户" }}
            columns={[
              { title: "客户姓名", dataIndex: "name" },
              { title: "联系电话", dataIndex: "maskedPhone" },
              {
                title: "操作",
                render: (_, row) =>
                  row.id ? (
                    <Button
                      disabled={busy || !currentSchool.maintainable}
                      onClick={() =>
                        void poolAction(row.id!, row.version ?? 0, "claim")
                      }
                    >
                      认领
                    </Button>
                  ) : null,
              },
            ]}
          />
          <Space style={{ marginTop: 8 }}>
            <Button
              disabled={poolPage === 0 || busy}
              onClick={() => setPoolPage(poolPage - 1)}
            >
              上一页
            </Button>
            <span>第 {poolPage + 1} 页</span>
            <Button
              disabled={!!poolRows.error || !poolRows.data?.hasMore || busy}
              onClick={() => setPoolPage(poolPage + 1)}
            >
              下一页
            </Button>
          </Space>
        </Card>
      ) : null}
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
          onClose={() => {
            setFollowupTarget(null);
            void client.invalidateQueries({
              queryKey: ["customer-reminders", current],
            });
          }}
          onUnauthorized={onUnauthorized}
        />
      ) : null}
      {recyclePolicyOpen && currentSchool?.maintainable ? (
        <CustomerRecyclePolicyEditor
          schoolId={currentSchool.id!}
          onClose={() => setRecyclePolicyOpen(false)}
          onUnauthorized={onUnauthorized}
          onUpdated={() => {
            setRecyclePolicyOpen(false);
            setNotice("自动回收规则已保存；未启用规则不会触发自动回收。");
          }}
        />
      ) : null}
      <Modal
        title="转派客户负责人"
        open={!!assignmentTarget}
        confirmLoading={busy}
        okText="确认转派"
        okButtonProps={{
          disabled:
            !assignmentAccount ||
            !assignmentReason.trim() ||
            assignmentReason.trim().length > 300,
        }}
        onOk={() => void assignCustomerOwner()}
        onCancel={() => {
          setAssignmentTarget(null);
          setAssignmentRequestId(undefined);
        }}
      >
        <Typography.Paragraph>
          将“{assignmentTarget?.name}
          ”转派给同校在职且已绑定有效账号的员工。客户版本和跟进历史会保留。
        </Typography.Paragraph>
        {assignees.error ? (
          <Alert type="error" title={assignees.error.message} showIcon />
        ) : null}
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Select
            aria-label="新负责人"
            placeholder="选择新负责人"
            loading={assignees.isFetching}
            value={assignmentAccount}
            onChange={(value) => {
              setAssignmentAccount(value);
              setAssignmentRequestId(crypto.randomUUID());
            }}
            options={(assignees.data ?? []).map((employee) => ({
              value: employee.accountId!,
              label: employee.name,
            }))}
          />
          <Input.TextArea
            aria-label="转派原因"
            placeholder="填写转派原因"
            maxLength={300}
            value={assignmentReason}
            onChange={(event) => {
              setAssignmentReason(event.target.value);
              setAssignmentRequestId(crypto.randomUUID());
            }}
          />
        </Space>
      </Modal>
      <Modal
        title="客户转学员"
        open={!!conversionTarget}
        confirmLoading={conversionBusy}
        okText={conversionResult ? "完成" : "创建学员并关联"}
        okButtonProps={{
          disabled:
            !!conversionLink.data?.studentId ||
            !!conversionResult?.conversion?.studentId,
        }}
        onOk={() => {
          if (conversionResult?.conversion?.studentId) {
            setConversionTarget(null);
            return;
          }
          void convertCustomerToStudent();
        }}
        onCancel={() => {
          setConversionTarget(null);
          setConversionResult(null);
        }}
      >
        <Typography.Paragraph>
          学员档案不会自动沿用客户姓名。请填写学员姓名，并确认客户联系人与学员的关系；客户姓名和电话会保存为主要联系人。重复学员提示只供核对，不会自动合并档案。
        </Typography.Paragraph>
        {conversionLink.error ? (
          <Alert type="error" title={conversionLink.error.message} showIcon />
        ) : null}
        {conversionError ? (
          <Alert
            type="error"
            title={conversionError}
            showIcon
            style={{ marginBottom: 12 }}
          />
        ) : null}
        {conversionResult?.conversion?.studentId ||
        conversionLink.data?.studentId ? (
          <>
            <Typography.Paragraph>
              已关联学员档案：
              {conversionResult?.conversion?.studentId ??
                conversionLink.data?.studentId}
            </Typography.Paragraph>
            {(conversionResult?.creation?.possibleDuplicates?.length ?? 0) >
            0 ? (
              <Alert
                type="warning"
                title={`创建时发现 ${conversionResult!.creation!.possibleDuplicates!.length} 条可能重复的学员档案，请进入学员档案核对。`}
                showIcon
              />
            ) : null}
          </>
        ) : (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Input
              aria-label="学员姓名"
              placeholder="学员姓名"
              maxLength={50}
              value={studentName}
              onChange={(event) => setStudentName(event.target.value)}
              disabled={conversionBusy}
            />
            <Select
              aria-label="客户联系人与学员关系"
              value={contactRelation}
              onChange={setContactRelation}
              disabled={conversionBusy}
              options={[
                { value: "FATHER", label: "父亲" },
                { value: "MOTHER", label: "母亲" },
                { value: "GUARDIAN", label: "监护人" },
                { value: "OTHER", label: "其他" },
              ]}
            />
          </Space>
        )}
      </Modal>
      <Modal
        title="合并重复客户"
        open={!!mergeSource}
        confirmLoading={busy}
        okText="确认合并"
        okButtonProps={{ disabled: !mergePrimaryId }}
        onOk={() => void mergeCustomer()}
        onCancel={() => {
          setMergeSource(null);
          setMergePrimaryId(undefined);
        }}
      >
        <Typography.Paragraph>
          将“{mergeSource?.name}
          ”作为副档合并到所选主档。副档会从本人客户列表隐藏，原档、跟进和请求记录保留；可从主档撤销合并。此操作不迁移学员或资金。
        </Typography.Paragraph>
        <Select
          aria-label="合并主档客户"
          placeholder="选择保留的主档客户"
          value={mergePrimaryId}
          onChange={setMergePrimaryId}
          style={{ width: "100%" }}
          options={(rows.data?.items ?? [])
            .filter((item) => item.id && item.id !== mergeSource?.id)
            .map((item) => ({ value: item.id!, label: item.name }))}
        />
      </Modal>
      <Modal
        title="已合并的客户副档"
        open={!!mergeHistoryFor}
        footer={<Button onClick={() => setMergeHistoryFor(null)}>关闭</Button>}
        onCancel={() => setMergeHistoryFor(null)}
      >
        {mergedRows.error ? (
          <Alert type="error" title={mergedRows.error.message} showIcon />
        ) : null}
        <Table<MergedCustomer>
          rowKey={(row) => row.sourceId!}
          size="small"
          loading={mergedRows.isFetching}
          dataSource={mergedRows.data ?? []}
          pagination={false}
          locale={{ emptyText: "该客户暂无合并副档" }}
          columns={[
            { title: "副档客户", dataIndex: "name" },
            { title: "合并时间", dataIndex: "mergedAt" },
            {
              title: "操作",
              render: (_: unknown, row) => (
                <Button
                  disabled={busy}
                  onClick={() => void reverseCustomerMerge(row)}
                >
                  撤销合并
                </Button>
              ),
            },
          ]}
        />
      </Modal>
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

function CustomerRecyclePolicyEditor({
  schoolId,
  onClose,
  onUnauthorized,
  onUpdated,
}: {
  schoolId: string;
  onClose: () => void;
  onUnauthorized: () => void;
  onUpdated: () => void;
}) {
  const [form] = Form.useForm();
  const client = useQueryClient();
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const pending = useRef<{ fingerprint: string; requestId: string } | null>(
    null,
  );
  const policy = useQuery({
    queryKey: ["customer-recycle-policy", schoolId],
    queryFn: async ({ signal }): Promise<CustomerRecyclePolicy> => {
      const { data, response } = await api.GET(
        "/api/admissions/customers/recycle-policy",
        { params: { query: { schoolId } }, signal },
      );
      if (response.status === 401) onUnauthorized();
      if (!response.ok || !data) throw new Error("无法读取自动回收规则。");
      return data;
    },
  });
  async function save(values: {
    enabled: boolean;
    inactivityDays: number;
    assignmentAgeEnabled: boolean;
    assignmentAgeDays: number;
    firstDayNoContactEnabled: boolean;
    firstDayCutoff: string;
    reason: string;
  }) {
    if (!policy.data || saving) return;
    const fingerprintValue = JSON.stringify([
      schoolId,
      policy.data.version,
      values,
    ]);
    if (pending.current?.fingerprint !== fingerprintValue)
      pending.current = {
        fingerprint: fingerprintValue,
        requestId: crypto.randomUUID(),
      };
    setSaving(true);
    setError("");
    try {
      const headers = await csrf();
      const { data, response } = await api.PUT(
        "/api/admissions/customers/recycle-policy",
        {
          params: { query: { schoolId } },
          headers,
          body: {
            requestId: pending.current.requestId,
            expectedVersion: policy.data.version,
            ...values,
          },
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录。");
      }
      if (response.status === 409)
        throw new Error("规则已被其他人修改，请关闭后重新打开并核对。");
      if (!response.ok || !data)
        throw new Error("保存未确认，可重试相同内容。");
      pending.current = null;
      await client.invalidateQueries({
        queryKey: ["customer-recycle-policy", schoolId],
      });
      onUpdated();
    } catch (reason) {
      setError(
        reason instanceof Error
          ? reason.message
          : "保存未确认，可重试相同内容。",
      );
    } finally {
      setSaving(false);
    }
  }
  return (
    <Modal
      title="自动回收规则"
      open
      onCancel={onClose}
      footer={null}
      destroyOnHidden
    >
      {policy.error ? (
        <Alert type="error" title={policy.error.message} showIcon />
      ) : null}
      {error ? <Alert type="error" title={error} showIcon /> : null}
      {policy.data ? (
        <Form
          form={form}
          layout="vertical"
          initialValues={policy.data}
          onFinish={(values) => void save(values)}
        >
          <Form.Item
            name="enabled"
            label="启用自动回收"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="inactivityDays"
            label="未跟进回收天数"
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={365} />
          </Form.Item>
          <Form.Item
            name="assignmentAgeEnabled"
            label="启用负责人分派时限"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="assignmentAgeDays"
            label="负责人分派回收天数"
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={365} />
          </Form.Item>
          <Form.Item
            name="firstDayNoContactEnabled"
            label="启用首日未联系回收"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="firstDayCutoff"
            label="首日未联系截止时间"
            rules={[{ required: true }]}
          >
            <Input type="time" />
          </Form.Item>
          <Form.Item
            name="reason"
            label="修改原因"
            rules={[{ required: true, max: 40 }]}
          >
            <Input maxLength={40} />
          </Form.Item>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" htmlType="submit" loading={saving}>
              保存规则
            </Button>
          </Space>
        </Form>
      ) : null}
    </Modal>
  );
}
