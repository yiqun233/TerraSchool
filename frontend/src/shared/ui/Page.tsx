import type { ReactNode } from "react";
import { Button, Pagination } from "antd";
import styles from "./ui.module.css";

/**
 * 页面骨架：标题、说明、操作区必须由页面显式提供。
 * 这里不猜页面用途，也不把标题塞进表格列。
 */
export function Page({ children }: { children: ReactNode }) {
  return <div className={styles.page}>{children}</div>;
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className={styles.header}>
      <div>
        <h1 className={styles.title}>{title}</h1>
        {description ? (
          <p className={styles.description}>{description}</p>
        ) : null}
      </div>
      {actions ? <div className={styles.actions}>{actions}</div> : null}
    </div>
  );
}

export function Toolbar({ children }: { children: ReactNode }) {
  return <div className={styles.toolbar}>{children}</div>;
}

export function Panel({
  children,
  head,
  headEnd,
  footer,
  ariaLabel,
}: {
  children: ReactNode;
  head?: ReactNode;
  headEnd?: ReactNode;
  footer?: ReactNode;
  ariaLabel?: string;
}) {
  return (
    <section className={styles.panel} aria-label={ariaLabel}>
      {head || headEnd ? (
        <div className={styles.panelHead}>
          <div className={styles.panelHeadStart}>{head}</div>
          {headEnd ? (
            <div className={styles.panelHeadEnd}>{headEnd}</div>
          ) : null}
        </div>
      ) : null}
      <div className={styles.panelBody}>{children}</div>
      {footer ? <div className={styles.panelFoot}>{footer}</div> : null}
    </section>
  );
}

export interface StatItem {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
}

/** 3–4 个关键指标，用 1px 分隔的连续卡片，不浮起。 */
export function StatStrip({ items }: { items: StatItem[] }) {
  return (
    <div className={styles.stats} role="group" aria-label="关键指标">
      {items.map((item) => (
        <div className={styles.stat} key={item.label}>
          <div className={styles.statLabel}>{item.label}</div>
          <div className={styles.statValue}>{item.value}</div>
          {item.detail ? (
            <div className={styles.statDetail}>{item.detail}</div>
          ) : null}
        </div>
      ))}
    </div>
  );
}

export type StatusTone = "ok" | "warn" | "danger" | "info" | "neutral";

const TAG_CLASS: Record<StatusTone, string> = {
  ok: styles.tagOk,
  warn: styles.tagWarn,
  danger: styles.tagDanger,
  info: styles.tagInfo,
  neutral: styles.tagNeutral,
};

/** 状态标签必须“圆点 + 文字”，不能只靠底色区分。 */
export function StatusTag({
  tone,
  children,
}: {
  tone: StatusTone;
  children: ReactNode;
}) {
  return <span className={`${styles.tag} ${TAG_CLASS[tone]}`}>{children}</span>;
}

export function StateBlock({
  title,
  hint,
  action,
}: {
  title: ReactNode;
  hint?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className={styles.state}>
      <p className={styles.stateTitle}>{title}</p>
      {hint ? <p className={styles.stateHint}>{hint}</p> : null}
      {action ? <div className={styles.stateAction}>{action}</div> : null}
    </div>
  );
}

/**
 * 页面级状态页：权限、加载、空范围等还没进入业务内容时使用。
 * 保持标题/说明，避免用户只看到一句 Alert 而不知道自己在哪个页面。
 */
export function PageState({
  title,
  description,
  stateTitle,
  stateHint,
  action,
  loading = false,
}: {
  title: ReactNode;
  description?: ReactNode;
  stateTitle?: ReactNode;
  stateHint?: ReactNode;
  action?: ReactNode;
  loading?: boolean;
}) {
  return (
    <Page>
      <PageHeader title={title} description={description} />
      <Panel>
        {loading ? (
          <LoadingBlock />
        ) : (
          <StateBlock title={stateTitle} hint={stateHint} action={action} />
        )}
      </Panel>
    </Page>
  );
}

export function LoadingBlock({
  children = "正在加载…",
}: {
  children?: ReactNode;
}) {
  return (
    <div className={styles.loading} role="status">
      {children}
    </div>
  );
}

/**
 * 面板底部分页：左侧总数，右侧页码。
 * page 从 0 开始，Page 组件从 1 开始，避免各页各写一套 offset 换算。
 */
export function TableFooter({
  total,
  page,
  pageSize,
  onChange,
  unit = "条",
  noun = "记录",
}: {
  total: number;
  page: number;
  pageSize: number;
  onChange: (page: number) => void;
  unit?: string;
  noun?: string;
}) {
  const start = total === 0 ? 0 : page * pageSize + 1;
  const end = Math.min(total, (page + 1) * pageSize);
  return (
    <>
      <span>
        共 {total} {unit}
        {total > 0 ? `，第 ${start}–${end} 条` : ""}
      </span>
      {total > pageSize ? (
        <Pagination
          size="small"
          current={page + 1}
          pageSize={pageSize}
          total={total}
          showSizeChanger={false}
          onChange={(next) => onChange(next - 1)}
          aria-label={`${noun}分页`}
        />
      ) : null}
    </>
  );
}

/** 后端只返回 hasMore 的列表用它；有 total 时优先用 TableFooter。 */
export function SimplePager({
  page,
  hasMore,
  onPrev,
  onNext,
  disabled,
}: {
  page: number;
  hasMore: boolean;
  onPrev: () => void;
  onNext: () => void;
  disabled?: boolean;
}) {
  return (
    <>
      <span>第 {page + 1} 页</span>
      <span style={{ display: "inline-flex", gap: 8 }}>
        <Button size="small" disabled={disabled || page === 0} onClick={onPrev}>
          上一页
        </Button>
        <Button size="small" disabled={disabled || !hasMore} onClick={onNext}>
          下一页
        </Button>
      </span>
    </>
  );
}

export const pageStyles = styles;
