# 01 Gateway & Auth Service（登录与鉴权）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写

---

## 1. 模块职责

### Gateway（认证入口层）

- 统一外部入口与路由；
- **Token 校验**（Web Access 用 Sa-Token 无状态 JWT 验签；MCP Token 经 Redis 哈希校验 + 窗口化滑动续期，Miss 时由 Auth Token Introspection 回源）；
- **身份上下文注入**：剥离原始凭证，向下游透传 `X-User-Id` / `X-Auth-Type` / `X-Mcp-Profile-Id`；
- CORS、限流入口。

**Gateway 不做任何数据库操作**，不承载业务逻辑。

### Auth（身份与凭证域）

- 用户身份（users）与 GitHub OAuth 登录（oauth_accounts）；
- **签发分工**：Web Access / Refresh 由 Sa-Token 生成/签发；**MCP Token 由 Auth 域按 §7.5 规则统一签发**（Snowflake ID + SecureRandom）；Refresh Token 持久化；
- 平台用户与 GitHub 账户关联；
- GitHub App 私钥加密保管与 installation token 签发服务。

核心原则：

> Gateway 管"认证入口与校验"，Auth 管"身份与签发"；**业务层认证鉴权统一由 Sa-Token 承担**（登录态、权限、角色），不采用 Spring Security。

---

## 2. 为什么存在

- 身份是"按业务边界拆微服务"的第一道边界；
- 登录鉴权与业务解耦，后续引入邮箱登录不侵入业务服务；
- MCP 作为外部 Agent 入口必须与 Web 走同一套 Gateway 鉴权；
- GitHub 凭证（OAuth 身份令牌 + GitHub App 私钥）必须集中安全保管。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| Gateway 路由 / Token 校验 / 身份头注入 | GitHub 数据读取（GitHub Integration） |
| GitHub OAuth 登录（身份） | 项目/仓库/成员业务（Project） |
| 平台用户与 GitHub 账户关联 | MCP Profile 知识范围（Knowledge / MCP） |
| Token 签发 / 持久化 / 撤销（Access / Refresh / MCP） | 知识检索（RAG） |
| GitHub App 私钥保管与 installation token 签发 | 代码分析（Analysis） |

---

## 4. 认证方案（双轨：身份与数据访问分离）

### 4.1 为什么身份与仓库权限分离

OAuth App 的 `repo` scope 在能力上是**读写**，与"绝不修改代码"原则冲突。因此：

```text
OAuth App：仅登录身份（read:user user:email），不申请仓库权限
GitHub App：仓库只读（Contents: Read + Metadata: Read），按仓库粒度安装授权
```

| 维度 | OAuth App（身份） | GitHub App（仓库） |
| --- | --- | --- |
| 用途 | 登录 / 用户身份 | 仓库只读数据访问 |
| Scope/权限 | `read:user user:email` | `Contents: Read` + `Metadata: Read` |
| 仓库范围 | 无仓库权限 | 用户安装时选择（All / Selected） |
| 令牌 | 身份令牌（8h + refresh） | installation token（约 1h，按需换取） |
| 安全 | 最小身份权限 | 真只读 + 按仓库粒度 |

### 4.2 OAuth App 创建（身份登录）

```text
Settings → Developer settings → OAuth Apps → New OAuth App
 ├── Application name：CodeCycle
 ├── Homepage URL：开发 http://localhost:8080；生产 https://codecycle.me
 ├── Redirect URI：
 │    开发 http://localhost:8080/api/auth/github/callback
 │    生产 https://codecycle.me/api/auth/github/callback
 ├── 允许万用符匹配：不启用
 ├── 启用设备流：不启用（V1）
 └── 用户访问令牌过期：开启（已定）
```

- 授权 scope：`read:user user:email`；
- 令牌过期模式（已定）：access 8h + refresh 6 个月；Auth 实现 refresh 并加密存储 refresh_token；
- 支持 PKCE（code_challenge/code_verifier，S256），服务端流程启用，配合 state 防 CSRF。

### 4.3 GitHub App 创建（仓库只读）

```text
Settings → Developer settings → GitHub Apps → New GitHub App
 ├── Repository permissions（全部只读）：
 │    Contents: Read（文件与 Commit 历史）
 │    Metadata: Read（仓库基础信息，自动授予）
 │    Commit statuses: Read（可选）
 │    Pull requests: Read（可选，V2）
 ├── Webhook：V1 启用（Commit 变更事件源；Webhook Secret 必填，HMAC 验签）
 ├── User-to-server token expiration：建议开启
 └── 生成并保存：App ID、Client ID、Client Secret、Private Key（PEM，仅下载一次）
```

### 4.4 安装与令牌

```text
用户安装 App 到账户/组织 → 选择仓库（All / Selected）
 → 平台记录 github_app_installations（installation_id、账户、仓库范围）
 → Auth 用 App 私钥签发 App JWT（RS256，10 分钟有效）
 → POST /app/installations/{installation_id}/access_tokens
      **强制传入 repository_ids=[当前项目 Repo ID]**（Token 仅能读取该仓库）
 → installation token（约 1h）
 → GitHub Integration 用该 token 只读访问仓库
```

- installation token 短期有效，**每次任务按需换取**，不长期驻留；
- **Token 作用域收窄到单仓库**（repository_ids 必传），禁止使用覆盖整个 Installation 的宽 Token；
- 只有 App 安装覆盖的仓库可被选择/分析；
- 卸载 App / 移除仓库 → 平台同步标记不可达。

---

## 5. GitHub 仓库访问级别与项目角色

**GitHub Contributors 仅用于成员发现与候选邀请**，不代表任何项目角色：

- CodeCycle 项目角色（OWNER / MEMBER / VIEWER）由 Project Service **独立管理**，与 GitHub 权限完全解耦；
- 平台不读取 GitHub Collaborator 权限细节，不做 Admin→OWNER 之类的映射；
- 仓库只读能力由 GitHub App 权限决定（§4.3），与用户账户权限无关。

---

## 6. 账户模型

```text
users（平台用户）
 ├── id / email（可空，预留邮箱登录）/ display_name / status / role / 时间字段

oauth_accounts（OAuth 身份）
 ├── user_id FK / provider=GITHUB / provider_user_id（唯一）
 ├── login / name / avatar_url / email
 ├── access_token_encrypted / refresh_token_encrypted（过期模式）
 ├── token_scopes（read:user,user:email）/ token_status / last_synced_at

github_app_keys（GitHub App 凭证，全局一份）
 ├── app_id / client_id / private_key_encrypted（PEM）/ 时间字段

github_app_installations（用户安装记录）
 ├── user_id FK / installation_id（唯一）/ account_login / account_type
 ├── repo_selection（ALL / SELECTED）/ 时间字段

mcp_tokens（MCP Token，见 §7.5）
 ├── id（Snowflake，业务主键）/ user_id FK / profile_id FK（Knowledge 域 mcp_profiles）
 ├── token_hash（SHA-256；Token 原文 `ccmcp_` 仅展示一次，绝不存明文）
 ├── status（ACTIVE / REVOKED / EXPIRED）
 ├── expires_at（30 天滑动）/ last_used_at / alias（展示别名）
 └── created_at / revoked_at
```

> Redis 运行时键 `mcp:token:{sha256(token)}` → { userId, profileId, status, expiresAt }，TTL = expiresAt 剩余；PG 为最终事实源。

- 首次 OAuth 登录即注册（按 provider_user_id 关联）；
- 邮箱登录为后续版本：users.email 已预留，绑定/解绑流程届时设计；
- 平台角色：USER（默认）/ ADMIN（预留）。

---

## 7. Token 体系

| 令牌 | 用途 | 寿命 | 存储 | 校验位置 |
| --- | --- | --- | --- | --- |
| Access Token（Sa-Token JWT） | 前端调用业务 API | 30 分钟 | 前端内存 | Gateway 无状态验签 + 业务服务 Sa-Token 解析 |
| Refresh Token | 换取新 Access | 7 天（可配置） | DB 哈希 + httpOnly Cookie | Auth |
| MCP Token（`ccmcp_`，Auth 统一签发） | Agent 调 MCP（每次请求携带） | **30 天滑动过期**（剩余 TTL ≤ 7 天才续期） | PostgreSQL + Redis 仅存 SHA-256 哈希 | Gateway（Redis 优先 + Auth Introspection 兜底） |
| GitHub OAuth Token | 登录身份验证 | 8h + refresh 6 个月 | Auth 加密 | Auth |
| GitHub App installation token | 仓库只读访问（**单仓库作用域**） | 约 1 小时 | 不落库，按需换取 | GitHub Integration |

- **签发分工**：Web Access / Refresh 由 Sa-Token 负责（JWT 无状态 + `SaFoxUtil` 随机令牌）；**MCP Token 由 Auth 域按 §7.5 生成规则统一签发**（Snowflake ID + SecureRandom 256-bit，前缀 `ccmcp_`），不使用 Sa-Token JWT；
- Sa-Token JWT Claims：`loginId`（userId）、`auth_type`、`role`、`exp`；**算法定案（v3.3）**：V1 统一 HS256（256-bit 随机密钥），RS256 升级列为 V2；
- Refresh Token 轮换 + 重用检测；登出作废（Sa-Token 登出 + DB 失效）；
- **MCP Token 只走 HTTP Authorization Header**，不出现在任何 Tool 参数中（硬约束）；
- **业务层鉴权**：各业务服务接入 Sa-Token（登录态 `StpUtil.checkLogin()`、权限 `@SaCheckPermission`），角色/权限由 `StpInterface` 动态加载（见 §14）。

### 7.5 MCP Token：单一高熵 Token + 滑动过期（已定）

MCP 不采用双层凭证，而采用**单一高熵 Token + 滑动过期**，消除签发/续期复杂度：

```text
MCP Token（ccmcp_xxx，30 天滑动有效）
  └── 剩余 TTL ≤ 7 天 → 有效期重置为 now + 30 天（窗口化滑动）
  └── 连续 30 天未使用 → 自动失效（无需 Refresh 流程）
```

**1. Token 生成规则（唯一性 + 不可预测性）**

```text
原则：Snowflake 管 ID，SecureRandom 管密钥

Token ID（业务主键，mcp_tokens.id）：Snowflake ID，用于数据库关联 / 日志 / UI 展示
Token 原文（Client 持有）：
  SecureRandom 生成 256-bit 随机数 → Base64URL 编码 → 加前缀 ccmcp_
  示例：ccmcp_9Zx2FgH8KjL1MnBvCxZaSdFgHjKlQwErTyUiOp
```

- 不可预测性：攻击者无法通过枚举 User ID 或时间戳伪造 Token；
- **由 Auth 域统一签发**（Sa-Token 负责 Web 登录态与业务权限；MCP Token 按本生成规则签发，不采用 Sa-Token JWT）。

**2. 存储设计（绝不以明文存 Token）**

```text
PostgreSQL：mcp_tokens（事实源）—— token_hash(SHA-256) + userId + profileId + status + expiresAt + lastUsedAt
Redis：mcp:token:{sha256(token)} → { userId, profileId, status, expiresAt }
       TTL = expiresAt 剩余时间（Redis 自动淘汰过期数据）
```

- 数据库与缓存**只存 SHA-256 哈希**，不存 Token 原文（与密码存储逻辑一致，防拖库）；
- Redis 为运行时状态（快速校验路径），PostgreSQL 为最终事实源。

**3. 滑动过期（Sliding Expiration）**

- Token 有效期固定 **30 天**；
- **窗口化续期**：仅当剩余 TTL ≤ 7 天才执行 `expiresAt = now + 30 天`（Redis SET 并刷新 TTL，PG 异步/批量回写）；窗口内只验证不续期，避免高并发无谓写；
- 只要持续使用就永远有效；连续 30 天未使用自动失效；
- **无需 Refresh Lock / 换发**：续期只在窗口边界发生，且只是 `EXPIRE` / `SET`，不存在"换发风暴"。

**4. 请求验证流程（Gateway 路径）**

```text
1. Client：Authorization: Bearer ccmcp_xxx
2. Gateway 计算 SHA-256(token) → tokenHash
3. Redis GET mcp:token:{tokenHash}
     - 命中：status=REVOKED / expiresAt < now → 401；否则通过
     - Miss：调用 Auth 内部 Token Introspection（查 PostgreSQL 事实源）
             → 有效：回填 Redis（TTL = 剩余时间），通过
             → 无效 / 撤销 / 过期 → 401
4. 验证通过 → 窗口化滑动续期：仅当剩余 TTL ≤ 7 天，SET expiresAt = now + 30d（刷新 TTL）
5. 取 profileId → 路由至 MCP Service（MCP Service 校验 Profile 与知识范围）
```

**5. 用户管理操作（UI/API）**

```text
创建       → 生成新 Token，返回原文（仅显示一次）
撤销       → Redis 与 PostgreSQL 置 status=REVOKED，立即全局失效
重新生成   → 撤销旧 Token，生成全新 Token 返回
查看列表   → 别名 / 最后使用时间 / 过期时间（不显示 Token 原文）
```

**6. 移除的冗余设计（相对原双层方案）**

| 原设计 | 本方案处理 |
| --- | --- |
| Access Token + Refresh Token 双层 | 仅保留单一高熵 Token（长期凭证） |
| OAuth 授权码 / PKCE | 不需要（用户即资源所有者，不涉及第三方授权） |
| Token Version 机制 | 撤销直接 `status=REVOKED`，Redis 立即生效 |
| 分布式 Refresh Lock | 滑动过期是"续命"非"换发"，无并发刷新风险 |
| TTL 随机抖动 | Token 串不变，仅过期时间滑动，无惊群效应 |
| Gateway 本地缓存降级（refresh） | 验证路径 = Redis GET + Miss 时 Auth Introspection（一次内网调用），暂缓本地缓存；压力大再引入 |

**7. 安全要点**

- 全链路 HTTPS；
- 存储仅 SHA-256 哈希（防拖库）；
- 实时撤销（泄露后立即失效）；
- 绑定 Profile Scope（泄露也只能访问该 Profile 知识）；
- 基于 Token 的 Rate Limit（防暴力破解/滥用）。

---

## 8. 认证流程

### 8.1 登录（OAuth 身份）

```text
Browser → Gateway → Auth
 1. GET /api/auth/github/authorize（state + PKCE）
    → 302 github.com/login/oauth/authorize?scope=read:user%20user:email
 2. 用户授权 → GitHub 302 → /api/auth/github/callback?code=&state=
 3. Auth 校验 state → 换令牌（8h access + refresh）→ GET /user、/user/emails
    → 创建/更新 users + oauth_accounts
    → Sa-Token 登录（StpUtil.login(userId)）→ 签发无状态 Access JWT + Refresh Cookie → 302 前端
 4. 前端 GET /api/auth/me 获取用户信息 + Access Token（放内存，请求头携带）
```

### 8.2 GitHub App 安装

```text
用户跳转 App 安装页 → 选择仓库 → GitHub 回调
 → Auth 记录 github_app_installations → 前端显示可分析仓库列表
```

### 8.3 登出 / 刷新 / MCP

```text
登出：POST /api/auth/logout（作废 Refresh，清 Cookie，清内存 Access）
刷新：POST /api/auth/refresh（Cookie）→ 新 Access + 新 Refresh（轮换）
MCP ：Agent → https://codecycle.me/mcp（Authorization: Bearer ccmcp_xxx）
      → Gateway 计算 SHA-256 → Redis 校验（存在 / status / expiresAt；Miss → Auth Introspection 回源 PG）→ 窗口化滑动续期
      → X-Mcp-Profile-Id → MCP Service（校验 Profile 与知识范围）
      → 401（过期/撤销）→ 用户在 Web 管理页重新生成 Token
```

---

## 9. Gateway 设计

### 9.1 路由

| 路径 | 目标 | 鉴权 |
| --- | --- | --- |
| `/api/auth/**` | Auth | authorize/callback 放行；其余登录态 |
| `/api/project/**` | Project | Access JWT |
| `/api/analysis/**` | Analysis | Access JWT |
| `/api/review/**` | Review | Access JWT |
| `/api/knowledge/**` | Knowledge | Access JWT |
| `/api/rag/**` | RAG | Access JWT |
| `/mcp`（含 `/mcp/**`） | MCP | MCP Token（Gateway：Redis 优先 + Auth Introspection 兜底） |

### 9.2 职责边界（硬约束）

```text
Gateway：认证入口、Sa-Token 无状态校验（Web）、MCP Token 经 Redis 校验（Miss 时调用 Auth Token Introspection，不直接查询 PostgreSQL）、路由、身份上下文注入
Auth   ：用户身份、GitHub OAuth（手动实现）、Sa-Token 签发（Web）与 MCP Token 签发（§7.5）、Refresh Token 持久化
```

- Gateway 用 **Sa-Token reactor 集成**（`sa-token-reactor-spring-boot4-starter` + `SaReactorFilter` + 白名单）校验 Web Access Token；MCP Token 按 §7.5 走 Redis 哈希校验（Miss → Auth Introspection 回源）与窗口化滑动续期；
- 校验通过后透传 `X-User-Id` / `X-Auth-Type` / `X-Mcp-Profile-Id`（并保留 Authorization 供业务服务 Sa-Token 二次解析）；
- 业务服务用 Sa-Token 构建登录上下文（`StpUtil.getLoginId()`）并做权限判定，同时信任 Gateway 透传头兜底；
- 内部服务调用使用内部服务令牌，走 `/internal/**`，外部不可达。

---

## 10. 接口设计（Auth）

| 方法/路径 | 说明 | 鉴权 |
| --- | --- | --- |
| `GET /api/auth/github/authorize` | 发起 OAuth（身份） | 公开 |
| `GET /api/auth/github/callback` | OAuth 回调 | 公开（state+PKCE） |
| `GET /api/auth/github-app/install` | 跳转 GitHub App 安装 | 登录 |
| `GET /api/auth/github-app/callback` | 安装回调，记录 installation | 登录 |
| `GET /api/auth/github-app/installations` | 已安装 App 列表与仓库范围 | 登录 |
| `POST /api/auth/refresh` | 刷新 Access | Refresh Cookie |
| `POST /api/auth/logout` | 登出 | 登录态 |
| `GET /api/auth/me` | 当前用户 + 关联账户 | Access JWT |
| `POST /api/auth/mcp/tokens` | 创建 MCP Token（Snowflake ID + SecureRandom，`ccmcp_`，明文仅展示一次） | Web Access JWT（Sa-Token 登录态） |
| `GET /api/auth/mcp/tokens` | Token 列表（别名 / 最后使用 / 过期时间，不含原文） | Web Access JWT（Sa-Token 登录态） |
| `DELETE /api/auth/mcp/tokens/{id}` | 撤销 Token（Redis + PG 置 REVOKED，立即全局失效） | Web Access JWT（Sa-Token 登录态） |
| `POST /api/auth/mcp/tokens/{id}/regenerate` | 重新生成（撤销旧 Token，生成全新 Token） | Web Access JWT（Sa-Token 登录态） |
| `GET /internal/auth/github-token?userId=` | 身份令牌（历史兼容，身份域保留） | 内部令牌 |
| `GET /internal/auth/installation-token?installationId=&repositoryId=` | 换取**仅限单仓库**的 GitHub App installation token（repository_ids 必传） | 内部令牌 |

---

## 11. 与其他服务关系

```text
Gateway ──→ Auth（登录/刷新；Token 校验在 Gateway 无状态完成）
GitHub Integration ──→ Auth（/internal/auth/installation-token 获取仓库访问令牌）
Project ──→ Auth（安装记录查询；X-User-Id 身份）
MCP Service ──→ Auth（MCP Token 签发/撤销；status/profile 校验数据在 Auth 域）
```

- 其他服务不接触 GitHub App 私钥与 OAuth 身份令牌；
- 仓库访问凭证统一由 Auth 经内部接口发放，最短生命周期。

---

## 12. 异常处理

| 场景 | 处理 |
| --- | --- |
| state 不匹配 / PKCE 校验失败 | 拒绝登录，记录告警 |
| OAuth 令牌过期 | 用 refresh 刷新；失败则提示重新授权 |
| GitHub App 私钥缺失/损坏 | 部署检查失败提示（配置错误） |
| installation token 换取失败 | 任务 FAILED_RETRYABLE；检查 App 安装状态 |
| App 被卸载 / 仓库被移除 | 标记 UNREACHABLE，提示重新安装 |
| MCP Token 过期/撤销 | Gateway 返回 401；用户在 Web 管理页重新生成（连续 30 天未用自动失效） |
| Profile 禁用/用户禁用 | Token 置 REVOKED；下次校验失败，Agent 提示更新 Token |
| 用户削减 scope | 身份仍可用；仓库访问不受影响（身份与数据分离） |

---

## 13. 架构约束（P0）

1. **OAuth 仅登录身份**：scope 固定 `read:user user:email`，**不申请任何仓库权限**；
2. **仓库只读走 GitHub App**：仅 `Contents: Read` + `Metadata: Read`，按仓库粒度安装授权；
3. **Gateway 职责**：认证入口、Token 校验、路由、身份上下文注入；**不做数据库操作**；
4. **MCP Token 只走 Authorization Header**，Tool 入参不含 Token；
5. **GitHub App 私钥加密保管**，installation token 短期按需换取，不长期驻留。
6. **Installation Token 单仓库作用域**：换取时强制 `repository_ids=[当前项目 Repo ID]`，禁止宽 Token 覆盖整个 Installation；
7. **Contributors 仅成员发现**：项目角色由 Project Service 独立管理，与 GitHub 权限解耦，不做权限映射。
8. **业务层认证鉴权统一用 Sa-Token**：不采用 Spring Security；GitHub OAuth 客户端手动实现（简单 HTTP 流程），Sa-Token 只负责登录态/权限/角色。

## 13.1 架构约束（P0）· Sa-Token 专项

1. **框架选型**：Sa-Token v1.46.0（`sa-token-spring-boot4-starter` 业务服务 + `sa-token-reactor-spring-boot4-starter` Gateway + `sa-token-jwt`）；
2. **无状态 JWT 模式**：使用 `StpLogicJwtForStateless`，Gateway 与业务服务共享 `jwt-secret-key` 验签，不引入服务端会话存储；
3. **统一签发**：Web Access / Refresh 由 Sa-Token 生成/签发；**MCP Token 由 Auth 域按 §7.5 规则统一签发**（Snowflake ID + SecureRandom），仅 Auth 可签发，其他服务不得自行签发；
4. **业务服务鉴权**：登录态用 `StpUtil.checkLogin()`，权限用 `@SaCheckPermission`；角色/权限码由 `StpInterface` 从数据库动态加载（项目角色 OWNER/MEMBER/VIEWER）；
5. **Gateway 不做数据库操作**：Sa-Token 校验在 Gateway 以无状态验签完成，身份写入 `X-User-Id` 透传。
6. **MCP 单一高熵 Token + 窗口化滑动过期**：30 天有效（剩余 TTL ≤ 7 天才续期），无 Refresh 流程、无 Token Version、无换发；
7. **MCP 存储只存哈希**：PostgreSQL 与 Redis 仅存 SHA-256(token)，绝不存 Token 原文；
8. **Redis 仅限 MCP Token 运行时状态**（校验路径 + 窗口化滑动续期），不作为通用缓存；Gateway 不直接查询 PostgreSQL——Redis Miss 时调用 Auth 内部 Token Introspection，由 Auth 查 PG 并回填 Redis。

---

## 14. 设计细则（P1 必检项）

- 身份令牌过期模式已定：access 8h + refresh 6 个月，Auth 加密保存并实现懒刷新/后台刷新；
- Refresh Token：7 天可配置、轮换 + 重用检测；
- PKCE + state 双重防 CSRF；
- GitHub App 安装记录与仓库选择同步（卸载/移除检测）；
- Sa-Token JWT 配置：`jwt-secret-key`（256-bit 随机密钥，HS256；开发/生产统一，生产仅环境变量/密文注入，支持密钥轮换）；
- `StpInterface` 实现：`getRoleList` / `getPermissionList` 按 loginId 加载项目角色权限码（如 `project:owner` / `project:member` / `project:viewer`）；
- 登出：Sa-Token 登出 + Refresh Token DB 失效 + Cookie 清除；
- MCP Token 生成：Snowflake ID（业务主键）+ SecureRandom 256-bit → Base64URL → 前缀 `ccmcp_`；
- MCP Token 有效期：30 天滑动（窗口化：剩余 TTL ≤ 7 天才重置 now+30d）；Redis TTL = expiresAt 剩余；
- MCP 校验：Gateway 计算 SHA-256 → Redis GET → 命中则校验 status/expiresAt；Miss 则调用 Auth Token Introspection（查 PG）→ 有效回填 Redis；续期仅在剩余 TTL ≤ 7 天时 SET；PG 为事实源（异步回写 lastUsedAt/expiresAt）；
- MCP 撤销：Redis + PG 置 REVOKED，立即全局失效；重新生成 = 撤销旧 + 新建；
- 基于 Token 的 Rate Limit（防暴力破解/滥用）；
- MCP OAuth 化（Authorization spec）列为 V2 评估。

---

## 15. 技术实现建议

- **GitHub OAuth 客户端：手动实现**（authorize / callback / state / PKCE / 换令牌，标准 HTTP 流程），**不使用 Spring Security**；
- **认证鉴权：Sa-Token v1.46.0**（业务服务 `sa-token-spring-boot4-starter`；Gateway `sa-token-reactor-spring-boot4-starter` + `SaReactorFilter`；`sa-token-jwt` 无状态模式）；
- GitHub App 令牌：Nimbus JOSE 签发 App JWT（RS256）→ 换取 installation token；
- JWT：Sa-Token JWT（hutool-jwt）；AES-256-GCM 加密 GitHub App 私钥与 OAuth 身份令牌；
- Cookie：httpOnly + SameSite=Lax + Secure（生产）；
- Gateway：Spring Cloud Gateway + SaReactorFilter 验签 + 身份头注入；
- 配置：application-dev/prod；生产域名 `codecycle.me`；回调 `https://codecycle.me/api/auth/github/callback`。

---

## 16. 实现优先级

### 【毕设必须】

- OAuth 身份登录（authorize/callback、state、PKCE、建账户）
- GitHub App 创建引导、安装回调、installation 记录
- **Sa-Token 集成**：业务服务 starter + Gateway reactor starter + JWT 无状态模式
- Access Token（Sa-Token JWT）+ Gateway 统一鉴权 + 身份头透传
- StpInterface 动态项目角色权限码
- Refresh Token（轮换 + 重用检测）
- GitHub App 私钥加密保管 + installation token 内部接口
- MCP Token 创建/列表/撤销/重新生成（§7.5：Snowflake + SecureRandom + 滑动过期 + Redis 校验）
- GitHub App Webhook 入口由 Gateway 接收（`POST /api/webhooks/github`），Auth 负责 HMAC 验签与防重放，Analysis 负责事件入队；生产默认事件源，见模块 04 §15.1
- `/api/auth/me`、登出、配置文档（README：双 App 创建步骤）

### 【建议实现】

- 多 Profile / 多 Token（配合 MCP）
- 安装状态同步（卸载检测）

### 【暂不实现】

- 邮箱登录（后续版本）
- 仓库写权限（废弃）

---

## 17. 后续扩展

1. 邮箱登录与账户绑定（users.email 已预留）；
2. Webhook 驱动的自动 Review（review_change，V2）；
3. 多 GitHub 身份；
4. 平台角色 ADMIN 与管理员引导。
5. 生产 JWT 升级 RS256（RSA 密钥对，Auth 私钥签发）——V1 使用 Sa-Token 默认 HS256，见 §18 决策记录；

---

## 18. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| 认证方式 | OAuth App 仅身份（read:user user:email）+ GitHub App 仓库只读（Contents/Metadata Read） | 已定 |
| 仓库授权粒度 | GitHub App 按仓库安装（All / Selected） | 已定 |
| 令牌过期模式 | OAuth 身份令牌 access 8h + refresh 6 个月，Auth 实现 refresh | 已定 |
| Refresh Token 寿命 | 7 天（可配置） | 已定 |
| Gateway/Auth 职责 | Gateway 认证入口 + Token 校验 + 身份注入，不做 DB；Auth 身份 + 签发 + 持久化 | 已定 |
| 认证鉴权框架 | Sa-Token v1.46.0（Boot4 starter + reactor Gateway + JWT 无状态），不采用 Spring Security | 已定 |
| GitHub OAuth 客户端 | 手动实现（state/PKCE/换令牌），不使用 Spring Security OAuth2 Client | 已定 |
| 业务层权限 | StpInterface 动态加载项目角色（OWNER/MEMBER/VIEWER 权限码） | 已定 |
| MCP Token 模型 | 单一高熵 Token（`ccmcp_`，Snowflake ID + SecureRandom 256-bit）+ 30 天滑动过期 | 已定 |
| MCP Token 传递 | 仅 Authorization Header，Tool 入参不含 Token | 已定 |
| MCP 撤销机制 | status=REVOKED（Redis + PG 立即全局失效）；重新生成 = 撤销旧 + 新建 | 已定 |
| MCP 续期 | 30 天滑动过期（窗口化：剩余 TTL ≤ 7 天才续期）；Redis Miss 由 Auth Token Introspection 回源 PG；无 Refresh 流程 / 无 Token Version / 无换发 | 已定 |
| MCP 运行时存储 | Redis（仅 MCP Token 运行时状态，TTL 自动淘汰）+ PostgreSQL（事实源，只存哈希） | 已定 |
| 生产域名 | `codecycle.me`；MCP `https://codecycle.me/mcp` | 已定 |
| Installation Token 作用域 | 换取时强制 `repository_ids=[当前项目 Repo ID]`，单仓库只读 | 已定 |
| Contributors 角色 | 仅成员发现/候选邀请；项目角色由 Project Service 独立管理，与 GitHub 权限解耦 | 已定 |
| 生产 JWT 算法 | V1 统一 HS256（256-bit 随机密钥，env 注入、可轮换）；RS256 密钥对升级列为 V2 | 已定 |
| GitHub Webhook 事件源 | V1：Webhook（生产默认，HMAC 验签 + 防重放）+ 分支轮询 / 模拟事件（开发兜底，不依赖公网域名） | 已定 |
