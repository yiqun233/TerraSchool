import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Select, Table, Typography } from "antd";
import { api } from "../../shared/http";
import {
  LoadingBlock,
  Page,
  PageHeader,
  Panel,
  StateBlock,
  StatusTag,
  TableFooter,
  Toolbar,
} from "../../shared/ui/Page";
import { PersistentCsvExportButton } from "../shared/PersistentCsvExportButton";
import {
  ApproveButton,
  EnrollmentBatchCreator,
  EnrollmentCreator,
  EnrollmentDetail,
  PrepaymentCreator,
} from "./EnrollmentForms";
import type { EnrollmentView, FundsCapabilities } from "./enrollmentShared";
import { STATUS_LABEL } from "./enrollmentShared";

// 拆分前就导出的组件保持同一导入路径；PrepaymentCreator 仍属模块内部实现，不从本文件外泄。
export {
  ApproveButton,
  validateCourseLines,
  EnrollmentBatchCreator,
  EnrollmentCreator,
  EnrollmentDetail,
  ReceiptCreator,
  AllocateAction,
  ReleaseButton,
  VoidReceiptButton,
  DiscountModal,
} from "./EnrollmentForms";

function EnrollmentStatePage({
  title,
  hint,
  action,
}: {
  title: string;
  hint: string;
  action?: React.ReactNode;
}) {
  return (
    <Page>
      <PageHeader
        title="报名与收款"
        description="报名只提交学员与价格版本；收款、分配与余额分开记账。"
      />
      <Panel>
        <StateBlock title={title} hint={hint} action={action} />
      </Panel>
    </Page>
  );
}

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
  const [page, setPage] = useState(0);
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
    queryKey: ["enrollments", current, page],
    enabled: !!current,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/enrollment/enrollments", {
        params: { query: { schoolId: current!, page, size: 20 } },
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
    return (
      <EnrollmentStatePage
        title="无法读取报名权限"
        hint={capabilities.error.message}
        action={
          <Button onClick={() => void capabilities.refetch()}>重试</Button>
        }
      />
    );
  if (capabilities.isPending)
    return (
      <Page>
        <PageHeader
          title="报名与收款"
          description="正在确认当前账号的报名权限…"
        />
        <LoadingBlock />
      </Page>
    );
  if (!capabilities.data?.canRead)
    return (
      <EnrollmentStatePage
        title="没有报名读取权限"
        hint="需要 ENROLLMENT_READ 权限；请联系机构管理员在“账号与权限”中开通。"
      />
    );
  const schools = capabilities.data.schools ?? [];
  if (schools.length === 0)
    return (
      <EnrollmentStatePage
        title="当前范围内没有可查看的学校"
        hint="请先建校并给账号分配学校范围，报名与收款才会可用。"
      />
    );

  return (
    <Page>
      <PageHeader
        title="报名与收款"
        description="报名只提交学员与价格版本，单价与课时由服务端从价格快照冻结；收款、分配与余额分开记账。"
        actions={
          <>
            {school?.maintainable ? (
              <>
                <Button
                  type="primary"
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
            <PersistentCsvExportButton
              exportType="ENROLLMENT"
              schoolId={current}
            />
            <PersistentCsvExportButton
              exportType="RECEIPTS"
              schoolId={current}
            />
            <Button
              onClick={() => void enrollments.refetch()}
              loading={enrollments.isFetching}
            >
              刷新
            </Button>
          </>
        }
      />
      <Toolbar>
        <Select
          aria-label="归属学校"
          style={{ width: 220 }}
          value={current}
          options={schools.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
          onChange={(value) => {
            setSchoolId(value);
            setPage(0);
          }}
        />
        <Typography.Text type="secondary">
          报名冻结价格与课时；收款与分配分开记账
        </Typography.Text>
      </Toolbar>
      {error ? <Alert title={error} type="error" showIcon /> : null}
      <Panel
        ariaLabel="报名列表"
        footer={
          enrollments.error ? null : (
            <TableFooter
              total={enrollments.data?.total ?? 0}
              page={page}
              pageSize={20}
              onChange={setPage}
              unit="条报名"
              noun="报名"
            />
          )
        }
      >
        {enrollments.error ? (
          <StateBlock
            title="报名列表加载失败"
            hint={enrollments.error.message}
            action={
              <Button onClick={() => void enrollments.refetch()}>重试</Button>
            }
          />
        ) : (
          <Table
            rowKey="id"
            loading={enrollments.isPending}
            dataSource={enrollments.data?.items ?? []}
            onRow={(row) => ({
              onClick: () => setDetailId(row.id ?? null),
              tabIndex: 0,
              className: "ts-row-clickable",
              "aria-label": `打开${row.studentName ?? "报名"}的报名详情`,
              onKeyDown: (event) => {
                if (event.target !== event.currentTarget) return;
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  setDetailId(row.id ?? null);
                }
              },
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
                  <StatusTag
                    tone={
                      status === "ACTIVE"
                        ? "ok"
                        : status === "PENDING_AUDIT"
                          ? "info"
                          : "neutral"
                    }
                  >
                    {STATUS_LABEL[status] ?? status}
                  </StatusTag>
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
        )}
      </Panel>
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
    </Page>
  );
}

/** 先记录学员名下的未分配实收；报名成立后可在报名详情中分配抵扣应收。 */
