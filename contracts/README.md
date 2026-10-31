# 契约生成

REST/JSON 的权威声明位于后端 HTTP 适配与公开 DTO。运行 `python tools/engineering/verify.py`，真实 HTTP 集成测试导出 `backend/applications/server/target/openapi.json`；随后在 frontend 执行 `pnpm run generate`。

生成文件 `frontend/src/shared/generated/api.ts` 与 jOOQ 类型不入库，禁止手工维护副本。前端使用 openapi-fetch 的 paths 类型访问组织与会话查询；Spring Security 登录/退出为表单协议，不伪造业务 Controller 声明。完整契约与错误见组织查询功能文档。
