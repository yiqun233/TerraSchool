import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Segmented, Space, Tabs } from "antd";
import { useNavigate } from "react-router-dom";
import { api } from "../../shared/http";
import {
  groupActions,
  myPermissionsQuery,
  permissionCatalogQuery,
  type ActionGrant,
  type MyPermissionSummary,
  type PermissionAction,
} from "../../shared/permissions";
import { useSession } from "../../app/session";
import type { CurrentPermissions } from "../../app/session";
import {
  LoadingBlock,
  Page,
  PageHeader,
  Panel,
  StateBlock,
  StatusTag,
} from "../../shared/ui/Page";
import { AuthorizationManager } from "./AuthorizationManager";
import { RoleTemplateManager } from "./RoleTemplateManager";
import { AccountLifecycleManager } from "./AccountLifecycleManager";
import styles from "./permissions.module.css";

/**
 * 权限管理。
 *
 * 页面只读呈现“我有什么、每个动作覆盖哪些学校、有哪些动作可配”，
 * 具体写入仍走各管理窗口及其服务端校验；前端不作为授权依据。
 */
export function PermissionConsole() {
  const { permissions, permissionsLoading } = useSession();
  const [tab, setTab] = useState("mine");
  const catalog = useQuery(permissionCatalogQuery());
  const mine = useQuery({ ...myPermissionsQuery(), enabled: !!permissions });
  const schools = useQuery({
    queryKey: ["permission-school-options"],
    // 没有组织查看权限时读不到学校名称，列表只显示学校数量。
    enabled: !!permissions?.canReadOrganizations,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET(
        "/api/organization/school-options",
        { signal, params: { query: { search: "" } } },
      );
      if (!response.ok || !data) throw new Error("无法读取学校名称");
      return data;
    },
  });
  const schoolNames = useMemo(
    () =>
      new Map(
        (schools.data ?? []).map((unit) => [unit.id ?? "", unit.name ?? ""]),
      ),
    [schools.data],
  );

  if (permissionsLoading) {
    return (
      <Page>
        <PageHeader
          title="权限管理"
          description="正在确认当前账号的功能与学校范围…"
        />
        <Panel>
          <LoadingBlock />
        </Panel>
      </Page>
    );
  }

  return (
    <Page>
      <PageHeader
        title="权限管理"
        description="角色按学校提供动作集合，账号可以在此基础上单独允许或拒绝；拒绝优先，维护范围不会超过查看范围。"
      />
      <Panel>
        <Tabs
          activeKey={tab}
          onChange={setTab}
          items={[
            {
              key: "mine",
              label: "我的权限",
              children: (
                <MineTab
                  permissions={permissions}
                  catalog={catalog.data ?? []}
                  catalogError={catalog.error}
                  mine={mine.data}
                  loading={catalog.isFetching || mine.isFetching}
                  error={catalog.error ?? mine.error}
                  schoolNames={schoolNames}
                />
              ),
            },
            {
              key: "manage",
              label: "账号与角色",
              children: <ManageTab permissions={permissions} />,
            },
            {
              key: "catalog",
              label: "动作目录",
              children: (
                <CatalogTab
                  actions={catalog.data ?? []}
                  loading={catalog.isFetching}
                  error={catalog.error}
                />
              ),
            },
          ]}
        />
      </Panel>
    </Page>
  );
}

type PermissionFilter = "all" | "granted" | "missing";

function MineTab({
  permissions,
  catalog,
  catalogError,
  mine,
  loading,
  error,
  schoolNames,
}: {
  permissions: CurrentPermissions | null;
  catalog: PermissionAction[];
  catalogError: Error | null;
  mine: MyPermissionSummary | undefined;
  loading: boolean;
  error: Error | null;
  schoolNames: Map<string, string>;
}) {
  // 只看“未开通”是业务人员最常用的视角：他要知道自己少什么、该申请什么。
  const [filter, setFilter] = useState<PermissionFilter>("all");
  if (error) {
    return (
      <StateBlock
        title="无法读取当前权限"
        hint={
          catalogError
            ? "动作目录加载失败，请刷新后重试。"
            : "请刷新页面；如果仍然失败，请联系机构管理员。"
        }
        action={
          <Button onClick={() => window.location.reload()}>刷新页面</Button>
        }
      />
    );
  }
  if (loading || !mine) return <LoadingBlock>正在核对当前权限…</LoadingBlock>;

  const grants = new Map(
    (mine.actions ?? []).map((grant) => [grant.action ?? "", grant]),
  );
  const total = catalog.length;
  const granted = grants.size;
  const has = (action: PermissionAction) => !!grants.get(action.code ?? "");
  const matches = (action: PermissionAction) =>
    filter === "all" ? true : filter === "granted" ? has(action) : !has(action);
  const groups = groupActions(catalog.filter(matches)).map((section) => ({
    ...section,
    // 已开通的排在前面、未开通的沉下去；同一段内保持目录原顺序（sort 稳定）。
    actions: [...section.actions].sort(
      (left, right) => Number(has(right)) - Number(has(left)),
    ),
  }));

  return (
    <div>
      <div className={styles.summary}>
        <StatusTag tone={granted > 0 ? "ok" : "neutral"}>
          已开通 {granted} / {total} 项
        </StatusTag>
        <Segmented
          size="small"
          value={filter}
          onChange={(value) => setFilter(value as PermissionFilter)}
          options={[
            { label: `全部 ${total}`, value: "all" },
            { label: `已开通 ${granted}`, value: "granted" },
            { label: `未开通 ${total - granted}`, value: "missing" },
          ]}
        />
        <span className={styles.meta}>
          {permissions?.tenantAdmin
            ? "租户管理员：组织、授权与品牌动作默认开通；业务学校范围仍按学校授权计算。"
            : "按动作分别计算学校范围；功能级允许不产生学校范围。"}
        </span>
      </div>
      {groups.length === 0 ? (
        <StateBlock
          title={
            filter === "missing" ? "目录里的动作都已开通" : "没有符合条件的动作"
          }
          hint={
            filter === "missing"
              ? "当前账号没有任何未开通动作；如果业务上仍用不了，多半是学校范围没覆盖。"
              : "换一个筛选条件再看看；“全部”下也为空说明动作目录没返回内容，请刷新页面。"
          }
        />
      ) : (
        groups.map(({ group, actions }) => (
          <section key={group}>
            <h3 className={styles.groupTitle}>
              {group}
              <span className={styles.groupCount}>{actions.length} 项</span>
            </h3>
            <div className={styles.grid}>
              {actions.map((action) => (
                <ActionCard
                  key={action.code}
                  action={action}
                  grant={grants.get(action.code ?? "")}
                  schoolNames={schoolNames}
                />
              ))}
            </div>
          </section>
        ))
      )}
    </div>
  );
}

function ActionCard({
  action,
  grant,
  schoolNames,
}: {
  action: PermissionAction;
  grant: ActionGrant | undefined;
  schoolNames: Map<string, string>;
}) {
  const names = (grant?.schoolIds ?? [])
    .map((id) => schoolNames.get(id))
    .filter((name): name is string => !!name);
  return (
    <div className={`${styles.card} ${grant ? "" : styles.cardMuted}`}>
      <div className={styles.cardHead}>
        <span className={styles.name}>{action.label}</span>
        <GrantTag grant={grant} />
      </div>
      <span className={styles.meta}>{action.description}</span>
      {names.length > 0 ? (
        <span className={styles.meta}>学校：{names.join("、")}</span>
      ) : null}
    </div>
  );
}

function GrantTag({ grant }: { grant: ActionGrant | undefined }) {
  if (!grant) return <StatusTag tone="neutral">未开通</StatusTag>;
  if (grant.functionalOnly)
    return <StatusTag tone="warn">仅功能（无学校范围）</StatusTag>;
  const count = grant.schoolIds?.length ?? 0;
  return <StatusTag tone="ok">{`${count} 所学校`}</StatusTag>;
}

function ManageTab({
  permissions,
}: {
  permissions: CurrentPermissions | null;
}) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const canManage = !!permissions?.canManageAuthorizations;
  const schools = permissions?.manageableSchoolIds?.length ?? 0;
  const refresh = () => {
    void client.resetQueries({ queryKey: ["my-permissions"] });
    void client.resetQueries({ queryKey: ["session-permissions"] });
    void client.invalidateQueries({ queryKey: ["permission-school-options"] });
  };
  if (!canManage) {
    return (
      <StateBlock
        title="当前账号不能管理授权"
        hint="需要“授权管理”动作，并且至少覆盖一所学校。请联系机构管理员在权限管理中开通。"
      />
    );
  }
  return (
    <div>
      <p className={styles.meta}>
        可管理授权的学校：{schools}{" "}
        所。修改整套授权或角色模板会立即影响被分配的账号，
        保存时服务端会复核委派范围与最后一位覆盖全部学校的管理员。
      </p>
      <Space wrap>
        <AuthorizationManager onChanged={refresh} />
        <RoleTemplateManager onClosed={refresh} />
        <AccountLifecycleManager onClosed={refresh} />
      </Space>
      {schools === 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 16 }}
          title="还没有学校：先到“组织目录”开通第一所"
          description="开通学校是组织动作，已挪到“组织目录”里完成；建好后回到这里给账号分配角色和学校范围，员工的业务菜单才会出现。"
          action={
            <Button size="small" onClick={() => navigate("/organization")}>
              去组织目录
            </Button>
          }
        />
      ) : null}
      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        title="拒绝优先：同一动作上的拒绝会关闭该功能，任何允许都不能覆盖。"
        description="功能级允许只代表动作开启，不产生学校范围；学校范围按动作分别计算。"
      />
    </div>
  );
}

function CatalogTab({
  actions,
  loading,
  error,
}: {
  actions: PermissionAction[];
  loading: boolean;
  error: Error | null;
}) {
  if (error) {
    return (
      <StateBlock
        title="动作目录加载失败"
        hint="请刷新页面重试；目录由服务端统一提供，不在前端维护副本。"
      />
    );
  }
  if (loading) return <LoadingBlock>正在读取动作目录…</LoadingBlock>;
  const groups = groupActions(actions);
  return (
    <div>
      <p className={styles.meta}>
        共 {actions.length} 个动作，按业务分组；每个动作对应页面上的操作入口。
        权限只决定“能不能做”，具体数据范围还要看该动作的学校授权。
      </p>
      {groups.map(({ group, actions: items }) => (
        <section key={group}>
          <h3 className={styles.groupTitle}>
            {group}
            <span className={styles.groupCount}>{items.length} 项</span>
          </h3>
          <div className={styles.grid}>
            {items.map((action) => (
              <div key={action.code} className={styles.card}>
                <div className={styles.cardHead}>
                  <span className={styles.name}>{action.label}</span>
                  <Space size={4}>
                    {action.risk === "SENSITIVE" ? (
                      <StatusTag tone="warn">敏感</StatusTag>
                    ) : null}
                    <StatusTag tone="neutral">
                      {action.scope === "TENANT" ? "租户级" : "按学校"}
                    </StatusTag>
                  </Space>
                </div>
                <span className={styles.meta}>{action.description}</span>
                <span className={styles.code}>{action.code}</span>
                <span className={styles.meta}>
                  对应页面：{(action.pages ?? []).join("、")}
                </span>
              </div>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
