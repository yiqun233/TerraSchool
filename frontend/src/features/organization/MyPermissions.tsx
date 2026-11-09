import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Modal, Space, Typography } from "antd";
import { api } from "../../shared/http";
import { AuthorizationManager } from "../identity/AuthorizationManager";
import { RoleTemplateManager } from "../identity/RoleTemplateManager";
import { AccountLifecycleManager } from "../identity/AccountLifecycleManager";
import { SchoolCreator } from "./SchoolCreator";
import { MyEmailConfiguration } from "../identity/MyEmailConfiguration";

/** 本人查看与管理入口分开；缓存不用于允许业务操作。 */
export function MyPermissions() {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const permission = useQuery({
    queryKey: ["own-organization-permissions"],
    enabled: open,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const { data, response } = await api.GET("/api/session/permissions", {
        signal,
      });
      if (!response.ok || !data)
        throw new Error("无法读取当前权限，请重新登录或稍后重试");
      return data;
    },
  });
  return (
    <>
      <Button type="text" size="small" onClick={() => setOpen(true)}>
        我的权限
      </Button>
      <Modal
        title="我的组织权限"
        open={open}
        onCancel={() => setOpen(false)}
        footer={<Button onClick={() => setOpen(false)}>关闭</Button>}
      >
        {permission.isFetching ? (
          <p role="status">正在核对当前权限…</p>
        ) : permission.error ? (
          <Alert type="error" title={permission.error.message} />
        ) : permission.data ? (
          <Space orientation="vertical">
            <Typography.Text>
              组织查看：
              {permission.data.canReadOrganizations ? "允许" : "未允许"}
            </Typography.Text>
            <Typography.Text>
              可查看学校：{permission.data.readableSchoolIds?.length ?? 0}所
            </Typography.Text>
            <Typography.Text>
              组织维护：
              {permission.data.canMaintainOrganizations ? "允许" : "未允许"}
            </Typography.Text>
            <Typography.Text>
              可维护学校：{permission.data.maintainableSchoolIds?.length ?? 0}所
            </Typography.Text>
            <Typography.Text type="secondary">
              权限有调整时，请重新打开此窗口查看。
            </Typography.Text>
            <Typography.Text>
              可管理授权学校：{permission.data.manageableSchoolIds?.length ?? 0}
              所
            </Typography.Text>
            <MyEmailConfiguration
              onChanged={() => void client.resetQueries()}
            />
            {permission.data.canManageAuthorizations &&
            (permission.data.manageableSchoolIds?.length ?? 0) > 0 ? (
              <Space wrap>
                <AuthorizationManager
                  onChanged={() => void client.resetQueries()}
                />
                <RoleTemplateManager
                  onClosed={() => void client.resetQueries()}
                />
                <AccountLifecycleManager
                  onClosed={() => void client.resetQueries()}
                />
                <SchoolCreator onClosed={() => void client.resetQueries()} />
              </Space>
            ) : null}
          </Space>
        ) : null}
      </Modal>
    </>
  );
}
