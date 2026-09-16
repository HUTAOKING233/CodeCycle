# 08 MCP Service（Agent 知识接口）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写
> 部署：独立部署单元 ⑥ MCP（独立故障域）

---

## 1. 模块职责

MCP Service 将平台的知识检索能力通过 **Model Context Protocol** 暴露给外部 AI Agent：

- 提供远程 **HTTPS Streamable HTTP** 端点（`https://codecycle.me/mcp`）；
- 管理 MCP Profile（知识范围）与 MCP Token 的生命周期（配合 Auth / Knowledge）；
- 提供 Agent 接入配置示例（Claude Code / Cursor 等）；
- 保证故障隔离：MCP 是 RAG 下游的**协议适配层**，不承载核心业务逻辑。

核心定位：

> MCP 是 RAG 的下游知识接口，不是代码审查系统；第一职责是让 Agent 在开发前获取项目规范、Skill、历史错误。

---

## 2. 为什么存在

- 让 Claude Code / Cursor 等 Agent 在编码前主动查询平台沉淀的工程知识，实现"预防性开发"；
- 作为外部暴露面，需要独立的协议适配与安全边界；
- 独立部署保证：MCP 挂不影响 Web/Review/Knowledge；Review 挂不影响 MCP 知识查询。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| MCP 协议适配（Streamable HTTP / Tools / Resources） | 知识检索实现（RAG） |
| MCP Profile 管理（知识范围编排） | 知识资产与生命周期（Knowledge） |
| Agent 接入配置示例 | Token 签发/校验（Auth，经 Gateway） |
| 调用 RAG 并格式化返回 | 代码审查（Review） |
| review_change 工具编排（V2） | GitHub 数据获取（GitHub Integration） |

---

## 4. 定位与解耦

```text
                     MCP 职责优先级
 ① search_knowledge / get_knowledge   ★★★★★   Agent 开发前获取知识
 ② review_change（V2）                 ★★       实时审查触发（次要）
```

解耦约束：

```text
MCP 挂掉   → Web / Review / Knowledge 全部正常
Review 挂  → MCP 知识查询正常（MCP 只依赖 Knowledge/RAG）
RAG 挂     → MCP 不可用（符合约定，RAG 是 MCP 的必要依赖）
```

- MCP 不调用 Review 实时服务（V1）；
- MCP 与 Web API 共享 Knowledge Service 业务能力，是同一能力的两种协议出口。

---

## 5. 传输与部署

```text
Claude Code / Cursor / 其他 Agent
  ↓ HTTPS（隧道域名）
https://codecycle.me/mcp（经 Gateway 路由 + MCP Token 鉴权）
  ↓
MCP Service → MCP Profile 解析 → RAG 检索 → 格式化返回
```

- 传输：**HTTPS Streamable HTTP**（Spring AI 2.0 中 SSE 已弃用）；
- 生产强制 HTTPS（与 GitHub OAuth 回调同域名 `codecycle.me`）；
- 开发调试：`http://localhost:8080/mcp`。

---

## 6. 鉴权与 Token

### 6.1 原则

- **MCP 请求与 Web API 一样统一经由 Gateway 鉴权**，MCP Service 不单独实现身份认证；
- **MCP Token 与 Web 登录令牌体系完全分离**（最小权限、固定知识范围）；
- **单一高熵 Token + 滑动过期**（见模块 01 §7.5）：`ccmcp_` 前缀，30 天滑动有效，无 Refresh 流程；
- Token 生成：Snowflake ID（业务主键）+ SecureRandom 256-bit → Base64URL；存储仅 SHA-256 哈希（PG + Redis）；
- 不向 Agent 暴露用户登录 Token 与 GitHub Token。

### 6.2 校验链路

```text
Agent 请求（Authorization: Bearer ccmcp_xxx）
 → Gateway 计算 SHA-256(token) → Redis GET mcp:token:{hash}
      → 命中：status=REVOKED / expiresAt < now → 401；否则通过
      → Miss：调用 Auth 内部 Token Introspection（查 PostgreSQL 事实源）
              → 有效：回填 Redis（TTL = 剩余时间），通过
              → 无效 / 撤销 / 过期 → 401
 → 窗口化滑动续期：仅当剩余 TTL ≤ 7 天，SET expiresAt = now + 30d（刷新 TTL）；否则只验证不续期
 → 透传 X-Mcp-Profile-Id
 → MCP Service 校验 Profile 与知识范围
 → 解析 Profile → Knowledge/RAG 检索
 → 401（过期/撤销）→ 用户在 Web 管理页重新生成 Token
```

### 6.3 生命周期

- 用户创建 Profile → 选择知识范围 → 创建 **MCP Token**（Auth 域按 §7.5 生成，`ccmcp_` 明文仅展示一次）；
- 一个 Profile 可拥有多个 Token（设备/工具级隔离），可单独撤销/重新生成；
- 有效期 **30 天滑动（窗口化）**：剩余 TTL ≤ 7 天才续期重置 now+30d；连续 30 天未使用自动失效（Redis TTL 淘汰 + PG 逻辑校验）；
- 撤销 / Profile 禁用 / 用户禁用 → status=REVOKED（Redis + PG），立即全局失效。

---

## 7. MCP Profile（知识范围）

```text
Profile
 ├── name（如 "Java 后端开发"）
 ├── project_ids：选择的 Project Knowledge
 ├── include_personal：是否混合个人知识（默认 false，显式开启）
 └── description
```

- 用户可创建多个 Profile（不同项目/语言/用途）；
- 同一 MCP Server 服务不同 Agent 时，用不同 Token 绑定不同 Profile；
- **include_personal 默认 false（v3.5 定案）**：只有显式勾选才把个人知识（含有效个人引用）纳入 Agent 检索范围，防止默认泄露个人知识；
- 范围最终由 RAG 的 Scope Filter 强制执行（模块 07 §8），MCP 不越权。

---

## 8. Tools 设计

### 8.1 V1（必做）

```text
search_knowledge(query, typeHint?, limit?)
  → 语义检索知识（范围由 Profile 决定）
  → 返回精炼结构化结果

get_knowledge(id)
  → 获取单条知识详情（含来源摘要与关系，供 Agent 引用）
  → 硬约束：必须同时校验知识归属范围（WHERE knowledge.id = ? AND knowledge ∈ profile_scope），禁止仅按 id 直查
```

### 8.2 V2（次要）

```text
review_change(repository, baseSha, headSha)
  → 触发 Commit Review 并返回结果（需要时，不强制）
```

### 8.3 参数与返回

`search_knowledge` 返回（与 RAG 输出一致，MCP 只做协议封装）：

```json
{
  "results": [
    {
      "id": "knowledge-1024",
      "type": "ARCHITECTURE_RULE",
      "title": "Controller 不直接访问 Mapper",
      "content": "Controller 应通过 Service 层处理业务逻辑。",
      "priority": "high",
      "scope": "project",
      "source": {"commit": "a83f92d"}
    }
  ],
  "usage": {"candidates": 20, "returned": 5, "tokens": 1800}
}
```

原则：

- 返回**结构化字段 + 面向 Agent 的精炼文本**双层设计；
- 默认 **Top 3~8 条**，不做全量；
- **不返回完整 Knowledge 内部对象**（metadata、revisions、embeddings 等）；
- 不把大量源码塞给 Agent（知识本身不含源码 + 检索压缩双层保障）。

---

## 9. 客户端接入配置

不同 Agent 配置方式不同（JSON / UI 表单），平台只提供配置模板，不针对客户端做专门适配。

### Claude Code（JSON）

```json
{
  "mcpServers": {
    "codecycle": {
      "url": "https://codecycle.me/mcp",
      "headers": {"Authorization": "Bearer ccmcp_xxx"}
    }
  }
}
```

接入说明：

- Web 管理页生成 `ccmcp_` Token（明文仅展示一次）→ 粘贴到配置；
- **无需刷新逻辑**：只要持续使用（30 天内至少一次请求），Token 自动续期；过期/撤销后重新生成即可；
- MCP OAuth 化（Authorization spec）列为 V2 评估。

### Cursor / 其他（表单）

```text
Name:      CodeCycle
URL:       https://codecycle.me/mcp
Token:     ccmcp_xxx（Web 管理页生成，30 天滑动有效）
```

- 前端"MCP"页提供复制配置模板、Token 创建/撤销/重新生成入口；
- 配置模板按 Profile 生成（不同 Token 绑定不同 Profile）。

---

## 10. 接口设计

| 方法/路径 | 说明 | 鉴权 |
| --- | --- | --- |
| `POST /mcp`（含 `/mcp/**`） | MCP Streamable HTTP 端点 | MCP Token（Gateway：Redis 优先 + Auth Introspection 兜底） |
| `GET /api/mcp/profiles` | Profile 列表 | 登录 |
| `POST /api/mcp/profiles` | 创建 Profile（绑定范围） | 登录 |
| `PATCH /api/mcp/profiles/{id}` | 修改 Profile | 本人 |
| `DELETE /api/mcp/profiles/{id}` | 删除 Profile（关联 Token 一并撤销） | 本人 |
| `POST /api/auth/mcp/tokens` | 创建 MCP Token（Auth 签发；Snowflake + SecureRandom，`ccmcp_`，明文仅展示一次） | Web Access JWT |
| `GET /api/auth/mcp/tokens` | Token 摘要列表（别名 / 最后使用 / 过期，不含原文） | Web Access JWT |
| `DELETE /api/auth/mcp/tokens/{id}` | 撤销 Token（status=REVOKED，立即全局失效） | Web Access JWT |
| `POST /api/auth/mcp/tokens/{id}/regenerate` | 重新生成（撤销旧 Token，生成全新 Token） | Web Access JWT |
| `GET /api/mcp/config-examples` | 各客户端配置模板（按 Profile） | 本人 |

---

## 11. 数据归属与关系

```text
mcp_profiles → Knowledge 域（知识范围定义，模块 06）
mcp_tokens → Auth 域（Snowflake ID + SHA-256 哈希 + status，模块 01）
MCP Token 运行时 → Redis（mcp:token:{sha256(token)}，TTL 自动淘汰）
MCP Service  → 协议适配 + 管理编排（不复制数据）
```

调用路径：

```text
MCP Service ──→ Auth（创建/撤销 Token）
MCP Service ──→ Knowledge（Profile 读写）
MCP Service ──→ RAG（检索，同单元调用或内部 API）
Gateway ──→ MCP（/mcp 路由 + Redis 校验，Miss → Auth Introspection 回源）
```

---

## 12. 异常处理

| 场景 | 处理 |
| --- | --- |
| Token 过期/撤销 | 返回认证错误，Agent 提示用户更新 Token |
| Profile 被删 | Token 失效（级联撤销），返回 Profile 不存在 |
| RAG 不可用 | MCP 返回"知识检索暂不可用"（不阻塞 Web） |
| 查询无结果 | 返回空结果 + 建议调整查询（不编造知识） |
| 工具参数非法 | 返回参数错误说明（结构化） |
| 并发创建 Token | 幂等/唯一约束（profile_id + 备注） |

---

## 13. 异步流程

MCP 本身**无异步流程**（同步请求-响应）；Token/Profile 生命周期操作同步完成。

---

## 14. AI 调用点

无直接 LLM 调用。MCP 只做协议适配与格式化；检索/问答能力在 RAG（`ask` 时 LLM 在 RAG 侧）。

---

## 15. 权限要求

- MCP Token 只绑定 Profile 知识范围，**绝不授予 GitHub / 用户管理权限**；
- Gateway 强制校验，MCP Service 不信任客户端传入的 userId/projectId；
- 生产强制 HTTPS；开发环境明确标记非生产；
- 日志不记录 Authorization / Token 明文。

---

## 16. 技术实现建议

- Spring AI 2.0 MCP：`@McpTool` / `@McpResource` 注解暴露工具；
- 传输：Spring AI MCP **Streamable HTTP** Server（WebMVC starter）；
- 安全：MCP Token 由 Gateway 统一校验（Redis 优先；Miss 时调用 Auth 内部 Token Introspection 回源 PostgreSQL）；不启用框架自带的其他认证插件（认证只认平台 MCP Token），MCP Service 不重复鉴权；
- 工具实现：薄适配层 → 调用 RAG 检索 → 结构化返回，不承载业务逻辑。

---

## 架构约束（P0）

1. **MCP Token 只走 HTTP Authorization Header**：Tool 入参只含业务参数（query / id / limit 等），任何令牌不得出现在 Tool 参数、URL 或返回体中；
2. **get_knowledge 必须校验归属范围**：`WHERE knowledge.id = ? AND knowledge ∈ profile_scope`，越权返回"不存在"，不泄露存在性信息；
3. **鉴权统一走 Gateway**：MCP Service 不单独实现身份认证，只信任 Gateway 透传的 `X-Mcp-Profile-Id`；
4. **MCP 不依赖 Review**：只调用 Knowledge/RAG；Review 挂不影响 MCP 知识查询；
5. **不返回完整内部对象**：默认 Top 3~8，结构化字段 + 精炼文本，不含 metadata / revisions / embeddings。
6. **单一高熵 Token + 滑动过期**：`ccmcp_`，30 天滑动；无 Refresh / 无 Token Version / 无换发；
7. **只存哈希**：PG 与 Redis 仅存 SHA-256(token)，绝不存 Token 原文；
8. **Redis 仅限 MCP Token 运行时状态**（校验 + 滑动续期），不作为通用缓存；Gateway 不直接查询 PostgreSQL——Redis Miss 时调用 Auth 内部 Token Introspection，由 Auth 查 PG 并回填 Redis。

## 设计细则（P1 必检项）

- Token 撤销即时失效：status=REVOKED（Redis + PG），Profile 删除级联撤销关联 Token；
- 窗口化滑动续期：仅当剩余 TTL ≤ 7 天才 SET expiresAt = now+30d（Redis TTL 同步），避免每次请求无谓写；PG 异步/批量回写 lastUsedAt/expiresAt；
- Redis Miss 兜底：Gateway → Auth `POST /internal/auth/mcp/introspect`（查 PostgreSQL）→ 有效则回填 Redis（TTL = 剩余时间）继续请求；无效返回 401；
- 401 语义：Gateway 返回 401，用户在 Web 管理页重新生成（连续 30 天未用自动失效）；
- 基于 Token 的 Rate Limit（防暴力破解/滥用）；
- 返回内容必须经过 RAG 的 Scope Filter 与 Context Compression，禁止绕过；
- 客户端配置差异（JSON / 表单）属接入适配，平台只提供 Endpoint + Token + 配置模板；
- 日志不记录 Authorization / Token 明文。

## 17. 实现优先级

### 【毕设必须】

- MCP Streamable HTTP 端点（走 Gateway 鉴权）
- MCP Token 创建/撤销（Auth 签发，明文一次展示）
- MCP Profile 创建/修改/删除（知识范围）
- `search_knowledge` / `get_knowledge` 工具
- 结构化 + 精炼文本返回（Top 3~8，不返回内部对象）
- 客户端配置示例（Claude Code JSON / Cursor 表单）

### 【建议实现】

- 多 Profile（多 Token 多范围）
- Token 有效期与备注
- 撤销即时校验

### 【可选扩展】

- `@McpResource` 暴露项目知识资源
- 检索 usage 统计

### 【暂不实现】

- `review_change`（V2）
- MCP Prompts / 复杂编排
- 多 Agent 会话管理
- OAuth 式 MCP 授权（V2 评估）

---

## 18. 后续扩展

1. **review_change**（V2）：Agent 主动触发 Commit Review（次要能力）；
2. **Resources**：将项目 Profile / 规则以 MCP Resource 暴露，支持语义化引用；
3. **OAuth 授权**：MCP 规范支持 OAuth 时评估替代固定 Token；
4. **多 Agent 场景**：不同 Agent 绑定不同 Profile 的组合检索。

---

## 19. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| 传输 | 远程 HTTPS Streamable HTTP（SSE 已弃用） | 已定 |
| 鉴权 | 与其他微服务一致走 Gateway；单一高熵 Token（`ccmcp_`）与登录 Token 分离 | 已定 |
| 范围 | Token/Profile → Knowledge Scope（Project 为默认范围，Personal 可显式开启；默认不混合） | 已定 |
| V1 工具 | search_knowledge / get_knowledge；review_change V2 | 已定 |
| 返回 | 结构化字段 + 精炼文本；Top 3~8；不返回完整内部对象 | 已定 |
| 数据归属 | Profile 在 Knowledge 域、mcp_tokens 在 Auth 域（只存哈希）、Redis 存运行时状态，MCP 只做适配与编排 | 已定 |
| 签发 | MCP Token 由 Auth 统一签发（Snowflake ID + SecureRandom 256-bit，前缀 ccmcp_） | 已定 |
| 续期 | 30 天滑动过期（窗口化：剩余 TTL ≤ 7 天才续期）；Redis Miss 由 Auth Token Introspection 回源 PG；无 Refresh 流程 / 无 Token Version / 无换发 | 已定 |
| 存储 | 只存 SHA-256 哈希；Redis 仅 MCP Token 运行时状态（TTL 自动淘汰） | 已定 |
| 故障隔离 | MCP 挂不影响 Web/Review/Knowledge；Review 挂不影响 MCP 知识查询 | 已定 |
