import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TenantApp } from "./app/TenantApp";
import "./shared/theme/base.css";

// 服务端数据由 Query 管理；这里只设全局默认，具体页面可按需覆盖重试。
const client = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={client}>
      <TenantApp />
    </QueryClientProvider>
  </StrictMode>,
);
