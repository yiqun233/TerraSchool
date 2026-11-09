import { useState } from "react";
import { App as AntApp, Button, ConfigProvider } from "antd";
import { RouterProvider } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ShellBoot } from "./AppShell";
import { router } from "./router";
import { LoginPage } from "./LoginPage";
import { PasswordChangePage } from "./PasswordChangePage";
import { fetchSessionContext, SessionProvider } from "./session";
import { TenantBrandingProvider } from "../shared/theme/TenantBrandingProvider";
import { DEFAULT_BRANDING } from "../shared/theme/brand";
import { buildTenantTheme } from "../shared/theme/tenantTheme";
import { Page, PageHeader, Panel, StateBlock } from "../shared/ui/Page";

const bootTheme = buildTenantTheme(DEFAULT_BRANDING);

/**
 * 租户端根装配：身份 → 强制改密 → 品牌 → 壳层。
 *
 * 未登录时只渲染中性登录页，不提前请求租户品牌；登录成功后再按租户加载，
 * 避免把上一个租户的颜色或缓存带进新会话。
 */
export function TenantApp() {
  const client = useQueryClient();
  const [notice, setNotice] = useState("");
  const session = useQuery({
    queryKey: ["session-context"],
    queryFn: fetchSessionContext,
    retry: false,
    refetchOnWindowFocus: false,
  });

  function reloadSession() {
    void client.invalidateQueries({ queryKey: ["session-context"] });
    void client.invalidateQueries({ queryKey: ["session-permissions"] });
    void client.invalidateQueries({ queryKey: ["tenant-branding"] });
  }

  function signedIn() {
    setNotice("");
    reloadSession();
  }

  function passwordChanged() {
    client.setQueryData(["session-context"], null);
    setNotice("密码已更新，请用新密码重新登录。");
  }

  if (session.isPending) return <ShellBoot />;

  if (session.error) {
    return (
      <ConfigProvider theme={bootTheme} button={{ autoInsertSpace: false }}>
        <AntApp>
          <div style={{ padding: 24 }}>
            <Page>
              <PageHeader title="无法连接服务" />
              <Panel>
                <StateBlock
                  title="会话状态读取失败"
                  hint={session.error.message}
                  action={
                    <Button type="primary" onClick={reloadSession}>
                      重试
                    </Button>
                  }
                />
              </Panel>
            </Page>
          </div>
        </AntApp>
      </ConfigProvider>
    );
  }

  if (!session.data) {
    return (
      <ConfigProvider theme={bootTheme} button={{ autoInsertSpace: false }}>
        <AntApp>
          <LoginPage onSignedIn={signedIn} notice={notice} />
        </AntApp>
      </ConfigProvider>
    );
  }

  if (session.data.passwordChangeRequired) {
    return (
      <ConfigProvider theme={bootTheme} button={{ autoInsertSpace: false }}>
        <AntApp>
          <PasswordChangePage
            onChanged={passwordChanged}
            onSessionLost={() => {
              client.setQueryData(["session-context"], null);
            }}
          />
        </AntApp>
      </ConfigProvider>
    );
  }

  return (
    <TenantBrandingProvider tenantId={session.data.tenantId}>
      <AntApp>
        <SessionProvider
          key={`${session.data.tenantId}:${session.data.accountId}`}
          session={session.data}
        >
          <RouterProvider router={router} />
        </SessionProvider>
      </AntApp>
    </TenantBrandingProvider>
  );
}
