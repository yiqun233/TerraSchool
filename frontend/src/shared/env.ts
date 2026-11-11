/**
 * 运行环境标识：顶栏右上角那一枚小标签的唯一来源。
 *
 * 租户端与平台运营端页面骨架不同，但“这是生产/预览/本地”的判定必须一致，
 * 否则同一套构建在两个入口会给出不同结论。配色由各自的 CSS Modules 决定，
 * 这里只给出语义档位，不返回样式类名，避免共享层依赖某个页面模块的样式表。
 */
export type EnvironmentTone = "production" | "preview" | "local";

export interface EnvironmentBadge {
  label: string;
  tone: EnvironmentTone;
}

export function currentEnvironment(): EnvironmentBadge {
  const value =
    (import.meta.env.VITE_APP_ENV as string | undefined) ??
    import.meta.env.MODE;
  if (value === "production") return { label: "生产环境", tone: "production" };
  if (value === "preview" || value === "staging")
    return { label: "预览环境", tone: "preview" };
  return { label: "本地开发", tone: "local" };
}
