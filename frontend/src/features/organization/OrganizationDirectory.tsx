import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button, Table } from "antd";
import type { components } from "../../shared/generated/api";
import { api } from "../../shared/http";
import {
  LoadingBlock,
  Page,
  PageHeader,
  Panel,
  StateBlock,
  StatusTag,
  TableFooter,
} from "../../shared/ui/Page";
import { useSession } from "../../app/session";
import { OnboardingGuide } from "./OnboardingGuide";
import { OrganizationEditor } from "./OrganizationEditor";
import { SchoolCreator } from "./SchoolCreator";
import { SchoolEditor } from "./SchoolEditor";

type Unit = components["schemas"]["OrganizationUnit"];

const KIND_LABEL: Record<string, string> = {
  SCHOOL: "学校",
  CAMPUS: "校区",
  DEPARTMENT: "部门",
};

/**
 * 组织目录：学校是根，校区与部门直属学校。
 *
 * 零学校时不再显示空表，而是给出“先建一所学校”的三步引导；
 * 引导是否出现由可读学校为空这一事实决定，不额外存“已引导”标记。
 */
export function OrganizationDirectory() {
  const { session, permissions, permissionsLoading, onUnauthorized, reload } =
    useSession();
  const [page, setPage] = useState(0);
  // 引导可以跳过，但跳过只在本次访问有效：不落库、不记住，刷新后仍按事实判定。
  const [guideSkipped, setGuideSkipped] = useState(false);
  const canRead = !!permissions?.canReadOrganizations;
  const needsOnboarding =
    canRead &&
    (permissions?.readableSchoolIds?.length ?? 0) === 0 &&
    (!!permissions?.canMaintainOrganizations ||
      !!permissions?.canManageAuthorizations);
  const showGuide = needsOnboarding && !guideSkipped;

  const schoolOptions = useQuery({
    queryKey: ["organization-school-options", session.tenantId],
    enabled: canRead && !showGuide,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/school-options",
        { params: { query: { search: "" } }, signal },
      );
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok) throw new Error("无法读取学校列表");
      return data ?? [];
    },
  });

  const units = useQuery({
    queryKey: [
      "organization",
      session.accountId,
      session.tenantId,
      "units",
      page,
    ],
    enabled: canRead && !showGuide,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/organization/units", {
        params: { query: { page, size: 20 } },
        signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        throw new Error("登录已失效，请重新登录");
      }
      if (!response.ok)
        throw new Error(
          response.status === 403
            ? "缺少组织查看权限，请联系机构管理员开通"
            : "无法读取组织目录，请稍后重试",
        );
      return data;
    },
  });

  if (permissionsLoading) {
    return (
      <Page>
        <PageHeader
          title="组织目录"
          description="正在确认当前账号的组织范围…"
        />
        <LoadingBlock />
      </Page>
    );
  }

  if (!canRead) {
    return (
      <Page>
        <PageHeader title="组织目录" />
        <Panel>
          <StateBlock
            title="没有查看组织目录的权限"
            hint="需要 ORGANIZATION_READ 权限；请联系机构管理员在“账号与权限”中开通，或确认当前学校范围。"
          />
        </Panel>
      </Page>
    );
  }

  if (showGuide) {
    return (
      <Page>
        <PageHeader
          title="先建一所学校"
          description="学校是校区、部门、员工、班级和报名的起点；建好后本页会显示完整的组织目录。"
        />
        <OnboardingGuide
          onSchoolCreated={() => reload()}
          onSkip={() => setGuideSkipped(true)}
        />
      </Page>
    );
  }

  const items = units.data?.items ?? [];
  const schoolNames = new Map(
    (schoolOptions.data ?? []).map((unit) => [unit.id, unit.name]),
  );
  const canManage = !!permissions?.canManageAuthorizations;

  return (
    <Page>
      <PageHeader
        title="组织目录"
        description="学校、校区与部门按层级维护。校区与部门分别直属学校，不互相嵌套；停用只影响后续业务选择，历史资料保留。"
        actions={
          <>
            <SchoolCreator onClosed={() => void units.refetch()} />
            <OrganizationEditor
              identityKey={session.accountId}
              onSaved={() => void units.refetch()}
            />
            <Button
              loading={units.isFetching}
              onClick={() => void units.refetch()}
            >
              刷新
            </Button>
          </>
        }
      />
      <Panel
        ariaLabel="组织目录"
        footer={
          units.error || units.isPending ? null : (
            <TableFooter
              total={units.data?.total ?? 0}
              page={page}
              pageSize={20}
              onChange={setPage}
              unit="个单位"
              noun="组织"
            />
          )
        }
      >
        {units.isPending ? (
          <LoadingBlock>正在读取组织目录…</LoadingBlock>
        ) : units.error ? (
          <StateBlock
            title="组织目录加载失败"
            hint={units.error.message}
            action={<Button onClick={() => void units.refetch()}>重试</Button>}
          />
        ) : items.length === 0 ? (
          <StateBlock
            title="当前范围内暂无组织"
            hint="账号还没有可读的学校；请先建校，或联系机构管理员调整学校范围。"
          />
        ) : (
          <Table<Unit>
            rowKey="id"
            dataSource={items}
            pagination={false}
            loading={units.isFetching}
            locale={{ emptyText: "当前范围内暂无组织" }}
            columns={[
              {
                title: "名称",
                dataIndex: "name",
                render: (name: string, unit) => (
                  <div>
                    <div>{name}</div>
                    {unit.kind === "CAMPUS" && unit.shortName ? (
                      <div style={{ color: "var(--ts-ink-4)", fontSize: 11.5 }}>
                        {unit.shortName}
                      </div>
                    ) : null}
                  </div>
                ),
              },
              {
                title: "类型",
                dataIndex: "kind",
                width: 90,
                render: (kind: string) => KIND_LABEL[kind] ?? kind,
              },
              {
                title: "所属学校",
                dataIndex: "schoolId",
                render: (schoolId: string, unit) =>
                  unit.kind === "SCHOOL"
                    ? "—"
                    : (schoolNames.get(schoolId) ?? "—"),
              },
              {
                title: "地址",
                dataIndex: "address",
                render: (address: string | undefined, unit) =>
                  unit.kind === "CAMPUS" ? address || "—" : "—",
              },
              {
                title: "状态",
                dataIndex: "active",
                width: 100,
                render: (active: boolean | undefined) =>
                  active === false ? (
                    <StatusTag tone="neutral">停用</StatusTag>
                  ) : (
                    <StatusTag tone="ok">可用</StatusTag>
                  ),
              },
              {
                title: "操作",
                key: "actions",
                width: 150,
                render: (_, unit) =>
                  unit.kind === "SCHOOL" && canManage ? (
                    <SchoolEditor
                      school={unit}
                      onSaved={() => void units.refetch()}
                    />
                  ) : (
                    <OrganizationEditor
                      selected={unit}
                      identityKey={session.accountId}
                      onSaved={() => void units.refetch()}
                    />
                  ),
              },
            ]}
          />
        )}
      </Panel>
    </Page>
  );
}
