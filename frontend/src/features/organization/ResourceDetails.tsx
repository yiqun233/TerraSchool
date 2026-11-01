import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Form, Input, Modal, Select, Space } from "antd";
import { api, csrf } from "../../shared/http";
import type { components } from "../../shared/generated/api";

type Resource = components["schemas"]["ResourceView"];
type Personnel = components["schemas"]["PersonnelData"];
type External = components["schemas"]["ExternalIdentifiers"];
type Mode = "qualification" | "account-link" | "personnel" | "external";
const externalFields: { key: keyof External; label: string }[] = [
  { key: "alternateName", label: "其他名称" },
  { key: "legacyNumber", label: "原系统编号" },
  { key: "qq", label: "QQ" },
  { key: "wechatAccount", label: "微信号" },
  { key: "wechatOpenId", label: "微信 OpenID" },
  { key: "workWechatUserId", label: "企业微信用户 ID" },
  { key: "dingTalkUserId", label: "钉钉用户 ID" },
  { key: "meetingUserId", label: "腾讯会议用户 ID" },
  { key: "telemarketingUserId", label: "外呼系统用户 ID" },
  { key: "seatSystemType", label: "外呼系统类型" },
  { key: "seatNumber", label: "坐席号" },
  { key: "seatExtension", label: "坐席分机" },
  { key: "teachingItemCode", label: "教学项目标识" },
];
const personnelFields: {
  key: keyof Personnel;
  label: string;
  date?: boolean;
}[] = [
  { key: "employeeNumber", label: "工号" },
  { key: "employeeType", label: "员工类型" },
  { key: "post", label: "岗位" },
  { key: "jobStatus", label: "任职状态" },
  { key: "entryDate", label: "入职日期", date: true },
  { key: "leaveDate", label: "离职日期", date: true },
  { key: "contractStart", label: "合同开始日期", date: true },
  { key: "contractEnd", label: "合同结束日期", date: true },
  { key: "probationStart", label: "试用期开始", date: true },
  { key: "probationEnd", label: "试用期结束", date: true },
  { key: "gender", label: "性别" },
  { key: "birthday", label: "出生日期", date: true },
  { key: "idNumber", label: "身份证件号码" },
  { key: "personalPhone", label: "手机号" },
  { key: "officePhone", label: "办公电话" },
  { key: "personalEmail", label: "个人邮箱" },
  { key: "enterpriseEmail", label: "企业邮箱" },
  { key: "bankName", label: "开户银行" },
  { key: "bankAccount", label: "银行账号" },
  { key: "emergencyContactName", label: "紧急联系人" },
  { key: "emergencyContactRelation", label: "联系人关系" },
  { key: "emergencyContactPhone", label: "联系人电话" },
  { key: "education", label: "学历" },
  { key: "graduateSchool", label: "毕业学校" },
  { key: "major", label: "专业" },
  { key: "householdCity", label: "户籍城市" },
  { key: "householdType", label: "户籍类型" },
  { key: "householdAddress", label: "户籍地址" },
  { key: "residenceCity", label: "居住城市" },
  { key: "residenceStreet", label: "居住街道" },
  { key: "residenceAddress", label: "居住地址" },
  { key: "maritalStatus", label: "婚姻状况" },
  { key: "politicalStatus", label: "政治面貌" },
  { key: "housingFundAccount", label: "公积金账号" },
  { key: "teacherCertificateStatus", label: "教师资格证状态" },
  { key: "baseSalary", label: "基本工资" },
  { key: "employerSocialInsurance", label: "单位承担社保金额" },
  { key: "employerHousingFund", label: "单位承担公积金金额" },
  { key: "memo", label: "备注" },
];
const titles: Record<Mode, string> = {
  qualification: "教师资质",
  "account-link": "账号绑定",
  personnel: "人事档案",
  external: "通讯资料",
};

export function ResourceDetails({
  row,
  mode,
  onSaved,
}: {
  row: Resource;
  mode: Mode;
  onSaved: () => void;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const sending = useRef(false);
  const id = row.id!;
  const details = useQuery({
    queryKey: ["resource-detail", mode, id],
    enabled: open,
    retry: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      if (mode === "qualification") {
        const result = await api.GET(
          "/api/organization/teachers/{id}/qualification",
          { signal, params: { path: { id } } },
        );
        if (!result.response.ok || !result.data)
          throw new Error("无法读取教师资质，请核对权限。");
        return result.data;
      }
      if (mode === "account-link") {
        const result = await api.GET(
          "/api/organization/employees/{id}/account-link",
          { signal, params: { path: { id } } },
        );
        if (!result.response.ok || !result.data)
          throw new Error("需要员工维护及账号授权管理权限。");
        return result.data;
      }
      if (mode === "external") {
        const result = await api.GET(
          "/api/organization/employees/{id}/external-identifiers",
          { signal, params: { path: { id } } },
        );
        if (!result.response.ok || !result.data)
          throw new Error("需要单独的人事档案查看权限。");
        return result.data;
      }
      const result = await api.GET(
        "/api/organization/employees/{id}/personnel",
        { signal, params: { path: { id } } },
      );
      if (!result.response.ok || !result.data)
        throw new Error("需要单独的人事档案查看权限。");
      return result.data;
    },
  });
  const accounts = useQuery({
    queryKey: ["link-account-options", id, search],
    enabled: open && mode === "account-link" && !!details.data,
    queryFn: async ({ signal }) => {
      const result = await api.GET("/api/authorization/accounts", {
        signal,
        params: { query: { search } },
      });
      if (!result.response.ok || !result.data)
        throw new Error("无法读取可管理账号。");
      return result.data;
    },
  });
  function close() {
    if (!busy) {
      setOpen(false);
      setBlocked(false);
      setError("");
      setSearch("");
      queryClient.removeQueries({
        queryKey: ["resource-detail", mode, id],
        exact: true,
      });
    }
  }
  async function save(
    values: Personnel &
      External & { qualification?: string; accountId?: string },
  ) {
    if (sending.current || blocked || !details.data) return;
    sending.current = true;
    setBusy(true);
    setError("");
    let sent = false;
    try {
      const token = await csrf();
      sent = true;
      const headers = { [token.headerName!]: token.token! };
      const version = details.data.version!;
      let response: Response;
      if (mode === "qualification") {
        ({ response } = await api.POST(
          "/api/organization/teachers/{id}/qualification",
          {
            params: { path: { id } },
            headers,
            body: {
              expectedVersion: version,
              qualification: values.qualification ?? "",
            },
          },
        ));
      } else if (mode === "account-link") {
        ({ response } = await api.POST(
          "/api/organization/employees/{id}/account-link",
          {
            params: { path: { id } },
            headers,
            body: {
              expectedVersion: version,
              accountId: values.accountId || undefined,
            },
          },
        ));
      } else if (mode === "external") {
        ({ response } = await api.POST(
          "/api/organization/employees/{id}/external-identifiers",
          {
            params: { path: { id } },
            headers,
            body: { expectedVersion: version, data: values },
          },
        ));
      } else {
        ({ response } = await api.POST(
          "/api/organization/employees/{id}/personnel",
          {
            params: { path: { id } },
            headers,
            body: { expectedVersion: version, data: values },
          },
        ));
      }
      if (!response.ok) {
        if (
          response.status === 403 ||
          response.status === 409 ||
          response.status >= 500 ||
          response.status === 408
        )
          setBlocked(true);
        setError(
          response.status === 409
            ? "资料版本或账号绑定已变化，请关闭后重新读取。"
            : response.status === 403
              ? "当前权限不足或学校已停用。"
              : response.status >= 500
                ? "保存结果尚未确认，请关闭后重新核对。"
                : "保存失败，请核对字段及日期。",
        );
        return;
      }
      setOpen(false);
      queryClient.removeQueries({
        queryKey: ["resource-detail", mode, id],
        exact: true,
      });
      onSaved();
    } catch {
      if (sent) setBlocked(true);
      setError(
        sent
          ? "保存结果尚未确认，请关闭后重新核对。"
          : "无法获取安全令牌，请重试。",
      );
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }
  const info = details.data;
  const initial = info
    ? mode === "qualification"
      ? { qualification: "qualification" in info ? info.qualification : "" }
      : mode === "account-link"
        ? { accountId: "accountId" in info ? info.accountId : undefined }
        : "data" in info
          ? (info.data ?? {})
          : {}
    : {};
  return (
    <>
      <Button
        onClick={() => {
          setOpen(true);
          setBlocked(false);
          setError("");
          void details.refetch();
        }}
      >
        {titles[mode]}
      </Button>
      <Modal
        title={`${titles[mode]}：${row.name}`}
        open={open}
        onCancel={close}
        closable={!busy}
        mask={{ closable: !busy }}
        footer={null}
        destroyOnHidden
        width={mode === "personnel" ? 780 : 520}
      >
        {details.isFetching ? (
          <p>正在读取…</p>
        ) : details.error ? (
          <Alert type="error" title={details.error.message} />
        ) : info ? (
          <>
            {error ? <Alert type="error" title={error} /> : null}
            {accounts.error ? (
              <Alert type="warning" title={(accounts.error as Error).message} />
            ) : null}
            <Form
              key={`${id}-${mode}-${info.version}`}
              layout="vertical"
              initialValues={initial}
              onFinish={save}
              disabled={busy || blocked}
            >
              {mode === "qualification" ? (
                <Form.Item name="qualification" label="资质简介">
                  <Input.TextArea rows={6} maxLength={10000} showCount />
                </Form.Item>
              ) : null}
              {mode === "account-link" ? (
                <Form.Item name="accountId" label="关联账号">
                  <Select
                    allowClear
                    options={[
                      ...("accountId" in info &&
                      info.accountId &&
                      info.accountLogin &&
                      !(accounts.data ?? []).some(
                        (item) => item.id === info.accountId,
                      )
                        ? [{ value: info.accountId, label: info.accountLogin }]
                        : []),
                      ...(accounts.data ?? []).map((item) => ({
                        value: item.id!,
                        label: item.name!,
                      })),
                    ]}
                    showSearch={{ filterOption: false, onSearch: setSearch }}
                    placeholder="搜索账号，清空表示解绑"
                  />
                </Form.Item>
              ) : null}
              {mode === "external" ? (
                <>
                  <p>这些标识仅用于人员资料，不会自动开通外部登录或权限。</p>
                  {externalFields.map((field) => (
                    <Form.Item
                      key={field.key}
                      name={field.key}
                      label={field.label}
                    >
                      <Input maxLength={200} />
                    </Form.Item>
                  ))}
                </>
              ) : null}
              {mode === "personnel"
                ? personnelFields.map((field) => (
                    <Form.Item
                      key={field.key}
                      name={field.key}
                      label={field.label}
                    >
                      <Input
                        maxLength={field.key === "memo" ? 2000 : 200}
                        placeholder={field.date ? "YYYY-MM-DD" : undefined}
                      />
                    </Form.Item>
                  ))
                : null}
              {mode === "personnel" ? (
                <>
                  <Form.List name="children">
                    {(fields, { add, remove }) => (
                      <>
                        <h4>子女资料</h4>
                        {fields.map((field) => (
                          <Space key={field.key} align="start" wrap>
                            <Form.Item
                              name={[field.name, "name"]}
                              label="姓名"
                              rules={[{ required: true }]}
                            >
                              <Input maxLength={100} />
                            </Form.Item>
                            <Form.Item
                              name={[field.name, "gender"]}
                              label="性别"
                            >
                              <Input maxLength={100} />
                            </Form.Item>
                            <Form.Item
                              name={[field.name, "birthday"]}
                              label="出生日期"
                            >
                              <Input placeholder="YYYY-MM-DD" />
                            </Form.Item>
                            <Button onClick={() => remove(field.name)}>
                              移除
                            </Button>
                          </Space>
                        ))}
                        <Button onClick={() => add()}>添加子女</Button>
                      </>
                    )}
                  </Form.List>
                  <Form.List name="contributionPeriods">
                    {(fields, { add, remove }) => (
                      <>
                        <h4>社保和公积金金额阶段</h4>
                        {fields.map((field) => (
                          <Space key={field.key} align="start" wrap>
                            <Form.Item
                              name={[field.name, "type"]}
                              label="类型"
                              rules={[{ required: true }]}
                            >
                              <Select
                                style={{ width: 150 }}
                                options={[
                                  {
                                    value: "SOCIAL_INSURANCE",
                                    label: "单位承担社保",
                                  },
                                  {
                                    value: "HOUSING_FUND",
                                    label: "单位承担公积金",
                                  },
                                ]}
                              />
                            </Form.Item>
                            <Form.Item
                              name={[field.name, "startMonth"]}
                              label="开始月份"
                              rules={[{ required: true }]}
                            >
                              <Input placeholder="YYYY-MM" />
                            </Form.Item>
                            <Form.Item
                              name={[field.name, "amount"]}
                              label="金额"
                              rules={[{ required: true }]}
                            >
                              <Input placeholder="0.00" />
                            </Form.Item>
                            <Button onClick={() => remove(field.name)}>
                              移除
                            </Button>
                          </Space>
                        ))}
                        <Button onClick={() => add()}>添加金额阶段</Button>
                      </>
                    )}
                  </Form.List>
                </>
              ) : null}
              <Space>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={busy}
                  disabled={blocked}
                >
                  保存
                </Button>
                <Button onClick={close}>关闭</Button>
              </Space>
            </Form>
          </>
        ) : null}
      </Modal>
    </>
  );
}
