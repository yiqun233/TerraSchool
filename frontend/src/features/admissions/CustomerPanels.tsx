import { Alert, Button, Card, Space, Table } from "antd";
import type { components } from "../../shared/generated/api";

type Customer = components["schemas"]["Customer"];
type CustomerReminder = NonNullable<
  NonNullable<components["schemas"]["ReminderPage"]["items"]>[number]
>;

/**
 * 待回访提醒（本人客户）。
 *
 * 纯展示：提醒只读，点“查看跟进”把客户交给主组件打开跟进抽屉；
 * 主组件在无完整电话权限时不渲染本面板。
 */
export function CustomerRemindersCard({
  items,
  error,
  fetching,
  onRefresh,
  onOpenFollowup,
}: {
  items: CustomerReminder[];
  error: Error | null;
  fetching: boolean;
  onRefresh: () => void;
  onOpenFollowup: (customerId: string) => void;
}) {
  return (
    <Card
      title="待回访提醒（本人客户）"
      size="small"
      style={{ marginTop: 12 }}
      extra={
        <Button size="small" onClick={onRefresh} loading={fetching}>
          刷新
        </Button>
      }
    >
      {error ? <Alert type="error" title={error.message} showIcon /> : null}
      <Table
        rowKey={(row) => row.id!}
        size="small"
        loading={fetching}
        dataSource={items}
        pagination={false}
        locale={{ emptyText: "当前没有到期提醒" }}
        columns={[
          { title: "客户", dataIndex: "customerName" },
          { title: "下次联系", dataIndex: "nextContactOn" },
          { title: "最近跟进", dataIndex: "occurredOn" },
          { title: "跟进摘要", dataIndex: "content", ellipsis: true },
          {
            title: "操作",
            render: (_, row) =>
              row.customerId ? (
                <Button
                  size="small"
                  onClick={() => onOpenFollowup(row.customerId!)}
                >
                  查看跟进
                </Button>
              ) : null,
          },
        ]}
      />
    </Card>
  );
}

/**
 * 学校公海。
 *
 * 纯展示：认领动作由主组件执行并负责版本校验与审计；分页状态也由主组件持有。
 */
export function CustomerPoolCard({
  items,
  error,
  fetching,
  busy,
  maintainable,
  hasMore,
  page,
  onPrev,
  onNext,
  onClaim,
}: {
  items: Customer[];
  error: Error | null;
  fetching: boolean;
  busy: boolean;
  maintainable: boolean;
  hasMore: boolean;
  page: number;
  onPrev: () => void;
  onNext: () => void;
  onClaim: (customer: Customer) => void;
}) {
  return (
    <Card title="学校公海" size="small" style={{ marginTop: 20 }}>
      {error ? <Alert type="error" title={error.message} showIcon /> : null}
      <Table<Customer>
        rowKey={(row) => row.id!}
        loading={fetching}
        dataSource={items}
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
                  disabled={busy || !maintainable}
                  onClick={() => onClaim(row)}
                >
                  认领
                </Button>
              ) : null,
          },
        ]}
      />
      <Space style={{ marginTop: 8 }}>
        <Button disabled={page === 0 || busy} onClick={onPrev}>
          上一页
        </Button>
        <span>第 {page + 1} 页</span>
        <Button disabled={!hasMore || busy} onClick={onNext}>
          下一页
        </Button>
      </Space>
    </Card>
  );
}
