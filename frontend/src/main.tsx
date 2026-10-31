import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import OrganizationPage from "./features/organization/OrganizationPage";
const router = createBrowserRouter([
  { path: "/", element: <OrganizationPage /> },
]);
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{ token: { colorPrimary: "#254c78", borderRadius: 6 } }}
    >
      <RouterProvider router={router} />
    </ConfigProvider>
  </StrictMode>,
);
