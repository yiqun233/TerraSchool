import { queryOptions } from "@tanstack/react-query";
import { api } from "./http";
import type { components } from "./generated/api";

/**
 * 权限动作目录与本人有效权限。
 *
 * 动作清单只在后端登记一次：界面选项、中文名、分组和说明都读这里，
 * 不在前端维护第二份字符串数组——那样一旦漏项，业务上就无法授权对应功能。
 */
export type PermissionAction = components["schemas"]["Action"];
export type ActionGrant = components["schemas"]["ActionGrant"];
export type MyPermissionSummary = components["schemas"]["Mine"];

export function permissionCatalogQuery() {
  return queryOptions({
    queryKey: ["permission-catalog"],
    // 目录随部署变化，不是业务数据；短缓存避免每个弹窗重复请求。
    staleTime: 5 * 60_000,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/permissions/catalog", {
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取权限动作目录");
      return data;
    },
  });
}

export function myPermissionsQuery() {
  return queryOptions({
    queryKey: ["my-permissions"],
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/permissions/mine", {
        signal,
      });
      if (!response.ok || !data) throw new Error("无法读取当前权限");
      return data;
    },
  });
}

export interface ActionGroup {
  group: string;
  actions: PermissionAction[];
}

/** 按目录顺序分组；同一分组在目录里连续出现，因此不会重复。 */
export function groupActions(actions: PermissionAction[]): ActionGroup[] {
  const groups = new Map<string, PermissionAction[]>();
  for (const action of actions) {
    const name = action.group ?? "其他";
    const list = groups.get(name);
    if (list) list.push(action);
    else groups.set(name, [action]);
  }
  return [...groups].map(([group, items]) => ({ group, actions: items }));
}

/** 下拉用的分组选项，保持目录顺序。 */
export function actionOptionGroups(actions: PermissionAction[]) {
  return groupActions(actions).map(({ group, actions: items }) => ({
    label: group,
    options: items.map((action) => ({
      value: action.code ?? "",
      label: action.label ?? action.code ?? "",
    })),
  }));
}

/** 动作码到目录条目的索引，用于渲染中文名与说明。 */
export function actionIndex(actions: PermissionAction[]) {
  return new Map(actions.map((action) => [action.code ?? "", action]));
}
