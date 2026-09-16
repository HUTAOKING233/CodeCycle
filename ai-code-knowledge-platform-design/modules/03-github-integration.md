# 03 GitHub Integration Service（GitHub 只读数据访问）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写
> 部署：与 Project Service 合并为部署单元 ③ Project（逻辑上独立，同进程内模块化调用）

---

## 1. 模块职责

GitHub Integration Service 是平台唯一的 GitHub 数据访问出口：

- 只读获取 Repository、Branch、Commit、Diff、Tree/File、Contributor 等数据；
- 统一封装 GitHub REST API（分页、限流、重试、错误归一化）；
- 管理调用凭证的获取与生命周期（凭证本体在 Auth 域）；
- 为 Project / Analysis 提供与 GitHub 无关的**统一 GitProvider 接口**（预留 GitLab/Gitea 扩展）。

核心原则：

> GitHub 是代码来源，只读、不 Clone、不写回；API 在线读取，源码只进内存不进库。

---

## 2. 为什么存在

- 项目要"GitHub 只读 + 不 Clone"，所有数据必须经 API 获取，需要一个**收敛点**统一处理鉴权、限流、分页与错误；
- 若各业务服务各自直连 GitHub API，会重复实现凭证获取、退避、缓存，且 GitHub Token 面临扩散风险；
- 统一 GitProvider 抽象后，未来接 GitLab / Gitea / GitHub App 只改这一个模块。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| GitHub API 调用封装（REST，GraphQL 预留） | 用户身份与 OAuth（Auth） |
| 分页 / 限流 / 重试 / 错误归一化 | 项目/仓库/成员业务（Project） |
| Tree / Blob / Commit / Diff / Contributors 获取 | Commit 持久化与分析（Analysis） |
| 凭证获取（经 Auth 内部接口） | 知识提炼（Knowledge） |
| Webhook 接收（V1：Commit 事件；验签/防重放） | 任何 GitHub 写操作（Push/PR/Issue） |

**明确禁止**：GitHub 写接口调用（Push、Commit、PR、Issue、仓库修改）；数据落盘源码。

---

## 4. 凭证与安全模型

### 4.1 凭证获取（GitHub App installation token）

```text
GitHub Integration
  → GET /internal/auth/installation-token?installationId=xxx&repositoryId=yyy（内部服务令牌）
  → Auth 用 GitHub App 私钥签发 App JWT
  → 换取 installation token 时**强制传入 `repository_ids=[repositoryId]`**（仅能读取当前仓库）
  → 本次请求链使用，用完即弃，不长期驻留内存
```

- Integration **不存储** GitHub App 私钥与 OAuth 身份令牌；
- installation token 仅授予 `Contents: Read` + `Metadata: Read`（真只读，按仓库粒度）；
- **Token 作用域收窄到单个仓库**（repository_ids=[当前项目 Repo ID]），不授予整个 Installation 的所有仓库（P0 硬约束）；
- 令牌接近过期（1 小时）时重新换取，任务内按需获取；
- 凭证明文字段禁止写入日志与异常消息。

### 4.2 不 Clone / 不落盘（数据持久化边界）

```text
允许持久化：Commit 元数据、变更统计、Diff 元数据、Review 结果、Knowledge 来源（少量片段）
禁止持久化：完整 Blob、完整 Tree、完整变更前后源码
短期缓存  ：当前任务所需 AST / 代码片段，任务结束后清除
```

- 源码内容只作为分析上下文存在内存/临时空间，任务结束即丢弃；
- 长期保存的只有元数据与分析结果，不含源码（硬约束，见项目定义 §3.4）。

### 4.3 固定出口与输入校验

- 只允许请求 `api.github.com`（禁止任意 URL，防 SSRF）；
- `owner` / `repo` / `path` 等输入做严格校验（正则/长度），防路径注入与恶意输入。

---

## 5. GitHub REST API 调用面（V1）

统一 Header：

```text
Authorization: Bearer <installation_token>
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28
```

| 能力 | API | 说明 |
| --- | --- | --- |
| 安装覆盖的仓库列表 | `GET /installation/repositories` | 仅返回 GitHub App 已安装授权的仓库（分页）；不使用 `/user/repos` |
| 仓库信息 | `GET /repos/{owner}/{repo}` | 元数据 |
| 分支列表 | `GET /repos/{owner}/{repo}/branches` | 含 HEAD SHA |
| 单个分支 | `GET /repos/{owner}/{repo}/branches/{branch}` | |
| Commit 列表 | `GET /repos/{owner}/{repo}/commits?sha={branch}&per_page=100` | 按分支 |
| 单个 Commit | `GET /repos/{owner}/{repo}/commits/{sha}` | 含 stats、files 摘要 |
| Commit 对比 | `GET /repos/{owner}/{repo}/compare/{base}...{head}` | **仅用于分支对比 / Commit Range 分析**，单 Commit Review 不得使用（见 5.2） |
| 文件树 | `GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1` | 目录结构 |
| 文件内容 | `GET /repos/{owner}/{repo}/contents/{path}?ref=` 或 `GET /git/blobs/{sha}` | Base64，解码后进内存 |
| Contributors | `GET /repos/{owner}/{repo}/contributors` | 成员发现 |
| 限流状态 | `GET /rate_limit` | 诊断用 |

### 5.1 分页

- `per_page=100`（上限）；
- 解析 `Link` header 的 `rel="next"`；
- 仓库/Commit/Contributors 均需处理分页；提供游标/页码抽象给调用方。

### 5.2 大 Diff 限制与处理策略

- **单 Commit Review 优先 `GET /repos/{owner}/{repo}/commits/{sha}`**：直接取 `files[]`（文件清单、状态、增删行、patch）；
- **Compare API 仅用于分支对比或 Commit Range 分析**，禁止混用于单 Commit Review；
- 大 Diff 截断处理：先取 commits/{sha} 的 files 摘要 → 对关键文件（改动行多 / 核心文件）通过 blob 获取 before/after 完整内容，由 Analysis 做增量对比；
- 超过"最大文件数/最大 Context"限制的 Commit 按模块 04 的资源限制处理，不硬拉全量。

### 5.3 缓存（节省限流配额）

- 低频变化数据（仓库元数据、分支、Contributors）：短期内存缓存（如 Caffeine，TTL 5~15 分钟）；
- 条件请求：`ETag` / `If-None-Match`（GitHub 支持 304）；
- 不引入额外缓存组件：内存缓存 + 调用方任务节流足够；
- 缓存 Key 按 `userId + 资源路径` 隔离，避免跨用户串数据。

---

## 6. 限流与重试

### 6.1 配额意识

- **Installation Token 限流按安装粒度独立计算**（基础 5000 次/小时，随仓库数/组织用户数上浮）；
- **每次响应实时读取 `X-RateLimit-*` 响应头**（Remaining / Reset / Used / Limit），纳入任务调度与配额记账；
- 禁止依赖固定"5000/小时"文案做调度假设，一律以响应头为准；
- 大批量任务（首次分析、历史 Commit 扫描）必须**分片 + 节流**，防止一次性打爆配额。

### 6.2 错误映射与重试

| 状态 | 含义 | 处理 |
| --- | --- | --- |
| 401 | 令牌无效/过期 | 通知 Auth 刷新后重试一次；失败则标记失效，提示重新授权 |
| 403 | 无权限 / rate limit | 读 `X-RateLimit-Reset` 退避；任务标记可重试 |
| 404 | 仓库/文件不存在或无权访问 | 返回 UNREACHABLE，不重试 |
| 429 | 限流 | 指数退避（1s → 2s → 4s，上限 60s） |
| 5xx / 超时 | GitHub 故障 | 指数退避重试，最多 3 次 |

---

## 7. 接口设计（内部服务接口）

> 均为 `/internal/github/**`，需内部服务令牌；`installationId + repositoryId` 由调用方传入（Project/Analysis 从项目-仓库关联解析），Integration 以**仅限该仓库**的 installation token 访问 GitHub。

| 方法/路径 | 说明 |
| --- | --- |
| `GET /internal/github/repositories?installationId=&page=` | App 安装覆盖的仓库列表（分页） |
| `GET /internal/github/repositories/{owner}/{repo}?installationId=&repositoryId=` | 仓库元数据 |
| `GET /internal/github/repositories/{owner}/{repo}/branches?installationId=&repositoryId=` | 分支列表 |
| `GET /internal/github/repositories/{owner}/{repo}/commits?installationId=&repositoryId=&branch=&page=` | Commit 列表 |
| `GET /internal/github/repositories/{owner}/{repo}/commits/{sha}?installationId=&repositoryId=` | Commit 详情（含 files 摘要，单 Commit Review 用） |
| `GET /internal/github/repositories/{owner}/{repo}/compare/{base}...{head}?installationId=&repositoryId=` | Commit Range / 分支对比（不用于单 Commit Review） |
| `GET /internal/github/repositories/{owner}/{repo}/tree/{sha}?installationId=&repositoryId=&recursive=` | 文件树 |
| `GET /internal/github/repositories/{owner}/{repo}/blob/{sha}?installationId=&repositoryId=` | Blob 内容（Base64 → 内存） |
| `GET /internal/github/repositories/{owner}/{repo}/contents/{path}?installationId=&repositoryId=&ref=` | 单文件内容 |
| `GET /internal/github/repositories/{owner}/{repo}/contributors?installationId=&repositoryId=` | 贡献者列表 |
| `GET /internal/github/rate-limit?installationId=&repositoryId=` | 当前安装配额状态（X-RateLimit-*） |
| （不提供外部 Webhook 路由） | Webhook 由 Gateway 接收、Auth 验签，Analysis 入队；Integration 只负责按任务取 GitHub 数据 |

返回统一包装：`{ ok, data | error{ code, message, retryable } }`，上层（Project/Analysis）不感知 GitHub 具体错误结构。

---

## 8. 数据归属

GitHub Integration **无业务表**（无状态服务）：

```text
github_repositories / project_branches / project_members  → Project 域
commits / commit_changes / code_symbols / code_relations  → Analysis 域
```

Integration 只做"取数"，持久化由调用方负责。

---

## 9. 与其他服务关系

```text
Auth ──→ Integration（/internal/auth/installation-token 提供仓库访问令牌）
Project ──→ Integration（仓库/分支/Contributors，同部署单元内调用）
Analysis ──→ Integration（Commit/Diff/Tree/Blob，跨部署单元 HTTP 接口 + 内部令牌）
```

- 同部署单元（Project + Integration）：本地模块调用；
- 跨部署单元（Analysis → Integration）：HTTP 接口 + 内部服务令牌；
- 全部调用必须携带 `installationId`，Integration 按安装取凭证，杜绝跨安装/跨用户数据。

---

## 10. 异常处理（补充）

- **凭证缺失**：用户未授权/授权失效 → 返回 `AUTH_REQUIRED`，调用方提示重新登录授权；
- **仓库无权限**：403 → `REPO_ACCESS_DENIED`，项目标记 UNREACHABLE；
- **输入非法**：owner/repo 含非法字符 → 400，不发起请求；
- **任务级失败**：分析任务依赖的数据拉取失败时，任务可重试（结合 6.2），连续失败置 FAILED 并记录原因。

---

## 11. 异步流程

- 本模块自身无独立异步任务（取数是同步内部调用）；
- 大批量拉取由调用方（Analysis 任务、Project 同步任务）分片调度；
- V1 事件源（双模式，见模块 04 §15.1）：
  - Webhook（生产/演示默认）：GitHub App Webhook → Gateway `POST /api/webhooks/github` → Auth 使用 Webhook Secret 验签并防重放 → Analysis 入队"仓库变更同步"任务；Integration 不提供外部 Webhook 路由；
  - 分支轮询 / 模拟事件（开发兜底）：本地无公网域名时轮询分支新 Commit，或经调试入口注入模拟 Push 事件，保证本地调试不依赖生产环境；
  - Webhook 驱动的自动 Review（review_change）为 V2。

---

## 12. AI 调用点

无。Integration 不调用 LLM。

---

## 13. 权限要求

- 只读范围：GitHub App 仅 `Contents: Read` + `Metadata: Read`，绝不调用写接口（Push/PR/Issue/Webhook 写）；
- 凭证不落盘、不进日志、不外传（包括 MCP 返回）；
- `/internal/**` 与 Webhook 端点仅在受信网络可达；
- 源码内容不持久化（模块级硬约束，代码评审时重点检查）。

---

## 14. 技术实现建议

- HTTP 客户端：Spring Boot 4 `RestClient`（同步）为主；异步批量可用 WebClient；
- 抽象：`GitProvider` 接口 + `GitHubProvider` 实现：

```java
interface GitProvider {
    List<RepoSummary> listRepositories(String userId, int page);
    RepoDetail getRepository(String userId, String owner, String repo);
    List<BranchInfo> listBranches(String userId, String owner, String repo);
    List<CommitSummary> listCommits(String userId, String owner, String repo, String branch, int page);
    CommitDetail getCommit(String userId, String owner, String repo, String sha);
    CompareResult compare(String userId, String owner, String repo, String base, String head);
    TreeData getTree(String userId, String owner, String repo, String sha, boolean recursive);
    byte[] getBlob(String userId, String owner, String repo, String sha);
    List<Contributor> listContributors(String userId, String owner, String repo);
}
```

- 限流：本地令牌桶 / 每用户配额记账（内存），配合 6.1；
- 缓存：Caffeine（TTL + 条件请求 304）；
- 配置：GitHub API 基地址（默认 api.github.com）、超时、重试参数，全部走 Nacos 配置。

---

## 架构约束（P0）

1. **仓库访问仅经 GitHub App**：只使用 installation token（`Contents: Read` + `Metadata: Read`），**不使用 OAuth 仓库权限、不使用任何写接口**；
2. **Token 作用域收窄到单仓库**：换取 installation token 时强制 `repository_ids=[当前项目 Repo ID]`，禁止使用覆盖整个 Installation 的宽 Token；
3. **凭证集中保管**：GitHub App 私钥在 Auth 域加密保管，Integration 经 `/internal/auth/installation-token?installationId=&repositoryId=` 临时获取，不存储；
4. **仓库列表用 `GET /installation/repositories`**：不使用 `/user/repos`，不依赖任何用户 OAuth 仓库权限；
5. **限流以 `X-RateLimit-*` 响应头为准**：按安装粒度独立计算，禁止硬编码固定配额文案；
6. **源码不落盘**：应用层禁止主动持久化完整源码至业务数据库/对象存储/普通日志；分析任务可用内存或受控临时空间，任务结束立即清理；
7. **固定出口**：只允许请求 `api.github.com`，输入严格校验（防 SSRF / 路径注入）；
8. **服务间调用走 HTTP 接口 + 内部令牌**；跨服务 HTTP 客户端统一 OpenFeign（版本由 Spring Cloud 2025.1.3 BOM 管理，见项目定义 §13）。
9. **扩展点装配（Spring @Conditional 硬约束，v3.4）**：本模块扩展点（数据通道/代码托管方）一律通过 `@ConditionalOnProperty` 按需装配，未启用不加载；application.yml 声明开关并附注释（默认值/可选值/启用影响），禁止 if-else 硬编码扩展；规范见项目定义 §3.6。

## 设计细则（P1 必检项）

- installation token 短期（约 1 小时）按需换取，不长期驻留；
- **单 Commit Review 用 `commits/{sha}` 的 `files[]`；Compare API 仅用于分支对比/Commit Range**，禁止混用；
- 缓存：内存 + ETag 条件请求，缓存 Key 按安装/资源隔离，杜绝跨用户串数据；
- 限流：按安装 + 仓库粒度配额记账 + 指数退避，大批量任务分片节流；
- 缓存 Key 需含 repositoryId，避免不同仓库串数据。

### 扩展点与配置开关（v3.4）

| 扩展点 | V1 实现（默认） | 未来扩展 | 配置键（application.yml） | 默认值 |
| --- | --- | --- | --- | --- |
| Git 数据通道 | REST（`GitHubProvider`） | GraphQL（P2 复杂关联查询） | `codecycle.github.channel` | rest |
| 代码托管方 | GitHubProvider | GitLab / Gitea（`GitProvider` 扩展实现） | `codecycle.git.provider` | github |

- 开关必须带注释：默认值、可选值、启用影响（如切 graphql 时装配 GraphQL 客户端 Bean，REST Bean 不加载）；
- 未启用实现不装配，主链路（REST/GitHub）零额外依赖。

## 15. 实现优先级

### 【毕设必须】

- 用户仓库列表（分页）
- 仓库元数据 / 分支列表
- Commit 列表 / 单个 Commit 详情
- Commit 对比（compare，含大 Diff 处理策略）
- Tree / Blob / 单文件内容获取
- Contributors
- 凭证经 Auth 获取 + 401 处理
- 限流计数与错误归一化包装
- GitProvider 接口 + GitHubProvider 实现

### 【建议实现】

- 内存缓存（仓库/分支/Contributors）
- 条件请求 304
- 批量拉取节流（供首次分析使用）

### 【可选扩展】

- `GET /rate_limit` 诊断接口
- 多账户（用户多 GitHub 身份）支持

### 【暂不实现】

- GraphQL 通道（预留，暂缓）
- 多事件 Webhook（PR / Issue / 成员变化，V2；Commit 事件 V1 已支持）
- GitLab / Gitea Provider

---

## 16. 后续扩展

1. **GraphQL**：复杂关联查询（Contributors 深度、跨仓库）后续按需实现，REST 保持 V1 主力；
2. **Webhook 多事件扩展**（V2）：PR / Issue / 成员变化事件自动同步（Commit 事件 V1 已支持，见模块 04 §15.1）；
3. **多 Git 平台**：GitLab / Gitea Provider 实现 GitProvider 接口即可接入；
4. **GitHub App 迁移**：换凭证来源（user-to-server token + refresh），接口不变。

---

## 17. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| API 通道 | REST 为主，GraphQL 预留暂缓 | 已定 |
| 凭证归属 | Auth 域保管 GitHub App 私钥；Integration 经 /internal/auth/installation-token 临时获取，不存储 | 已定 |
| 是否 Clone | 否；Tree/Blob/Compare API 在线读取，源码只进内存 | 已定 |
| 缓存 | 内存缓存 + 条件请求，不引入额外缓存组件 | 已定 |
| 大 Diff | compare 元数据 + 关键文件 blob 增量对比，受资源限制约束 | 已定 |
| 写操作 | 全部禁止（Push/PR/Issue/仓库修改） | 已定 |
| 数据持久化边界 | 允许元数据/结果；禁止完整源码；短期缓存任务后清除 | 已定 |
| 仓库列表 | `GET /installation/repositories`（App 安装覆盖范围），不使用 /user/repos | 已定 |
| Token 作用域 | 换取 installation token 强制 `repository_ids=[当前项目 Repo ID]`，单仓库只读 | 已定 |
| 限流 | 以 X-RateLimit-* 响应头为准，按安装粒度独立计算，禁止硬编码配额 | 已定 |
| Diff 获取 | 单 Commit 用 commits/{sha} files[]；Compare 仅用于分支/Range | 已定 |
