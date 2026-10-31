import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Alert, Form, Input, Typography } from "antd";
import type { DraftCommand } from "./contract-check";

// 只验证所选主组件能够共同编译；这不是正式管理端、登录页或业务验收页面。
function ProbePage() {
  return <main>
    <Typography.Title level={2}>TeraSchool 技术验证</Typography.Title>
    <Alert title="仅验证构建与契约，不提交业务数据。" type="info" />
    <Form<DraftCommand> layout="vertical">
      <Form.Item name="amount" label="金额字符串"><Input readOnly value="123456789012345678.123456" /></Form.Item>
    </Form>
  </main>;
}
const queryClient = new QueryClient();
const router = createBrowserRouter([{ path: "/", element: <ProbePage /> }]);
createRoot(document.getElementById("root")!).render(
  <StrictMode><QueryClientProvider client={queryClient}><RouterProvider router={router}/></QueryClientProvider></StrictMode>,
);
