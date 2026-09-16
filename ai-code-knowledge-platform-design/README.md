# AI Code Knowledge & Incremental Review Platform

这是 CodeCycle 设计文档入口。当前基线为 v3.6（2026-09-04）。

## 阅读顺序

1. [项目定义](../AI代码工程知识与质量分析平台_项目定义.md)：全局定位、跨模块硬约束、技术基线、MVP 范围和版本决策。
2. [01 Gateway & Auth](modules/01-gateway-auth.md) 至 [08 MCP](modules/08-mcp.md)：按业务边界拆分的模块设计，模块内的字段、接口、流程和实现优先级以此为准。
3. 专项设计：
   - [部署](architecture/deployment.md)
   - [数据库](database/database-design.md) · [AI 模型](ai/ai-model-design.md) · [前端](frontend/frontend-design.md)
4. [CHANGELOG](../CHANGELOG.md)：只记录历史决策和已废弃结论，不作为当前设计规范。

## 文档维护规则

- 全局规则只写入项目定义；模块细节只写入对应的 `modules/` 文档，避免复制同一份设计。
- 专项文档只保留无法由模块文档替代的视图：架构图、部署拓扑、完整数据库设计、AI 模型配置和前端页面设计。
- 模块文档中的实现、字段、接口和流程优先于概览性描述；发生冲突时，以模块文档和项目定义的最新基线为准。

## 核心闭环

GitHub Repository → Commit/Diff → 增量代码分析 → AI Review → Review Finding → Knowledge Extraction → Project/Personal Knowledge → RAG → MCP → Agent → 新 Commit。
