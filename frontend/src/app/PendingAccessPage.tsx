import { Button, Space } from "antd";
import { useNavigate } from "react-router-dom";
import { useSession } from "./session";
import { Page, PageHeader, Panel, StateBlock } from "../shared/ui/Page";

/**
 * “登录成功但还没有任何业务权限”时的落地页。
 *
 * 不把人送进必然 403 的业务页，也不假装功能不存在：说清缺什么、谁能开通、下一步点哪里。
 * 权限仍由接口逐次校验，这里只承担入口说明，不作为授权依据。
 */
export function PendingAccessPage() {
  const { session } = useSession();
  const navigate = useNavigate();
  return (
    <Page>
      <PageHeader
        title="还没有可用的业务功能"
        description="账号已经登录，但当前没有开启任何业务动作。"
      />
      <Panel>
        <StateBlock
          title="需要机构管理员先授予权限"
          hint={
            session.tenantAdmin
              ? "你是本机构的租户管理员：先去“组织目录”建学校，建好后会为你开通该校的组织、员工、教师与教室权限；学员、报名、教务等业务权限在“权限管理”里按学校分配。"
              : "请联系机构管理员，在“权限管理 → 账号与角色”里为你的账号分配角色和学校范围；开通后重新登录即可看到菜单。"
          }
          action={
            <Space wrap>
              {session.tenantAdmin ? (
                <Button
                  type="primary"
                  onClick={() => navigate("/organization")}
                >
                  去组织目录建学校
                </Button>
              ) : null}
              <Button onClick={() => navigate("/permissions")}>
                查看我的权限
              </Button>
            </Space>
          }
        />
      </Panel>
    </Page>
  );
}
