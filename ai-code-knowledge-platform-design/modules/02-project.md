# 02 Project Service（项目管理）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写
> 部署：与 GitHub Integration 合并为部署单元 ③ Project（逻辑上仍为两个业务服务）

---

## 1. 模块职责

Project Service 是平台"项目上下文"域的管理者：

- 管理平台项目（Project）及其与 GitHub Repository 的关联；
- 维护项目分支（Branch）快照与同步状态；
- 维护项目成员（Owner / Member / Viewer）与来源；
- 跟踪项目级状态：初始分析状态、整体评估状态、知识库状态；
- 触发并编排项目相关的异步任务（GitHub 同步、初始分析、整体评估）。

核心原则：

> 项目是平台业务组织单元；GitHub 是数据源；Contributors 用于发现成员，但最终成员关系由平台维护。

---

## 2. 为什么存在

- 用户选择 GitHub Repository 后需要一个**平台侧的业务容器**：仓库元数据只是 GitHub 的镜像，项目承载分析状态、成员、知识与评估；
- 成员、分支、分析状态等有业务生命周期，不能散落在 GitHub 数据层；
- 知识、审查、RAG 都以"项目"为上下文聚合，Project 是它们的组织锚点；
- 与 GitHub Integration 合并部署但职责分离：Project 管业务，GitHub Integration 管数据获取。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| 平台项目 CRUD 与生命周期 | GitHub API 调用封装（GitHub Integration） |
| 项目 ↔ 仓库关联 | Commit / Diff 获取与分析（Code Analysis） |
| 分支快照与同步状态 | Review 生成与 Finding（Review） |
| 成员发现与角色管理 | 知识资产与提炼（Knowledge / RAG） |
| 项目状态（初始分析 / 整体评估）跟踪 | MCP Profile 知识范围（MCP / Knowledge） |
| 整体评估触发（异步、限频） | 评分计算（Analysis 异步产出，写入 assessment_scores） |

---

## 4. 核心对象与模型

```text
Project（平台项目）
 ├── Repository 关联（V1 业务约束：1 项目 = 1 仓库；数据模型保留 1:N 预留）
 ├── Branch 快照
 ├── Members（Owner / Member / Viewer）
 ├── 初始分析状态
 ├── 整体评估状态
 └── Project Knowledge（由 Knowledge 域管理，本项目只是上下文）
```

### 4.1 Project（项目）

- 平台业务单元，不直接等于 GitHub Repository；
- **V1 业务约束写死：1 个项目仅绑定 1 个 Repository**（减少实现面：令牌作用域、Graph 状态、Review 归属均按单仓库设计）；数据模型保留 **1:N** 结构，未来放开多仓库时无需改表；
- 状态：`ACTIVE` / `ARCHIVED`（软删除）；
- 项目拥有者：创建者（自动 OWNER）。

### 4.2 Repository 关联

- 通过 GitHub Integration 列出用户可访问仓库 → 用户选择 → 写入 `github_repositories`（元数据镜像）+ `project_repositories`（关联）；
- 仓库元数据只存**展示与分析所需字段**，不存源码；
- 仓库被删除/失去权限时标记 `UNREACHABLE`，不阻塞平台已有数据。

### 4.3 Members（成员）

- 角色：`OWNER`（项目创建者）、`MEMBER`（Contributors 发现或手动添加）、`VIEWER`（只读）；
- 来源：`GITHUB_CONTRIBUTOR`（自动发现）/ `MANUAL`（手动添加）/ `CREATOR`（创建者）；
- **未注册用户处理**：GitHub Contributor 可能尚未登录平台——先以 GitHub 身份占位（github_user_id + login），该用户首次登录后自动绑定到平台用户并成为项目成员。

### 4.4 Branch（分支）

- 按仓库维护分支快照（名称、HEAD SHA、是否默认分支）；
- 同步策略：接入时全量、之后按需/手动刷新（GitHub 限流考虑）；
- 分支是 Branch / Module Analysis 与 Commit 列表的入口。

### 4.5 项目状态

```text
初始分析：NOT_STARTED → QUEUED → RUNNING → COMPLETED / FAILED
整体评估：同初始分析状态机，另加 LAST_AT 用于限频
知识状态：知识计数 / 待审核数（由 Knowledge 域统计，本项目仅展示）
```

---

## 5. 与 GitHub 的关联

### 5.1 Repository 选择与接入（流程）

```text
用户登录后
 → GET /api/project/repositories（经 Gateway 鉴权，X-User-Id 透传）
 → Project Service → GitHub Integration（内部受信调用）
      → 获取 GitHub App 安装覆盖的仓库列表（installation token，见模块 01 §4.4）
 → 返回仓库列表（full_name / 私有 / 默认分支 / 最近推送）
 → 用户选择仓库 → POST /api/project
      → 写 github_repositories 元数据 + project_repositories 关联 + 创建项目
      → 触发异步：分支同步 + Contributors 同步
      → 初始分析入队（Analysis Service）
```

### 5.2 同步内容与策略

| 同步内容 | 频率 | 说明 |
| --- | --- | --- |
| 仓库元数据 | 接入时 / 手动刷新 | full_name、默认分支、私有、描述、更新时间 |
| 分支列表 | 接入时 / 手动刷新 / 分析前 | 名称、HEAD SHA |
| Contributors | 接入时 / 手动刷新 | 用于成员发现，见 5.3 |

同步全部走异步任务，并受 GitHub 限流约束（指数退避，见模块 03 §6.1）。

### 5.3 Contributors → 成员发现

```text
GET /repos/{owner}/{repo}/contributors（GitHub Integration）
  ↓
每个 contributor：
  ├── 已在平台注册（oauth_accounts.provider_user_id 命中）
  │     → 自动加入 project_members（MEMBER）
  └── 未注册
        → 写入占位成员（github_user_id + login，user_id 为空）
  ↓
首次登录时（模块 01 建账户逻辑）按 provider_user_id 回填：
  project_members.user_id = 新用户 id，占位转为正式 MEMBER
```

- 占位成员不发送邀请邮件（V1 无通知系统），仅在项目成员页展示"待绑定"；
- 成员可手动移除/改角色；Contributors 再次同步时**不覆盖**手动调整（手动调整优先）。

### 5.4 角色模型

**项目角色由 Project Service 独立管理，与 GitHub 权限完全解耦**：

| 角色 | 权限概览 |
| --- | --- |
| OWNER | 项目全部权限：编辑、成员管理、归档/删除、触发分析/评估、知识管理 |
| MEMBER | 查看项目、触发分析/评估、知识管理（确认/拒绝 Finding、发布知识） |
| VIEWER | 只读查看项目、Review、知识 |

- GitHub Contributors 仅用于**成员发现与候选邀请**（§5.3），不映射为任何项目角色；
- 角色调整只能由平台内操作完成（OWNER 管理）。

---

## 6. 项目创建与生命周期

### 6.1 创建流程

```text
POST /api/project { repositoryId 或 full_name }
 1. 校验仓库可达（GitHub Integration）
 2. 创建 projects（owner_user_id = 当前用户，角色 OWNER）
 3. 写 github_repositories + project_repositories
 4. 提交异步任务：
      a. 分支同步
      b. Contributors 同步
      c. 初始分析入队（Analysis Service）
 5. 返回项目摘要 + 任务状态
```

### 6.2 归档 / 删除

- **默认软删除（ARCHIVED）**：项目从列表隐藏，历史 Review / 知识保留（知识是独立资产）；
- 归档项目可恢复；
- 彻底删除为**后续功能**（需级联确认：Review、Knowledge、assessment_scores 的处置策略再定）；
- OWNER 可归档/恢复；删除权限仅 OWNER。

---

## 7. 权限模型（项目域内）

### 7.1 判定位置

- **Gateway 只做身份认证**（校验 X-User-Id）；
- **Project Service 在本地按 project_members 判定项目角色**（数据所有者判定原则）；
- 其他服务（Review / Knowledge / Analysis）需要项目权限时，调用 Project 内部接口校验，不复制成员表。

### 7.2 角色 × 操作矩阵

| 操作 | OWNER | MEMBER | VIEWER |
| --- | --- | --- | --- |
| 查看项目 / 仓库 / 分支 / 成员 | ✓ | ✓ | ✓ |
| 触发同步 / 初始分析 / 整体评估 | ✓ | ✓ | ✗ |
| 调整成员角色 / 添加成员 | ✓ | ✗ | ✗ |
| 编辑项目信息 | ✓ | ✗ | ✗ |
| 归档 / 恢复 / 删除 | ✓ | ✗ | ✗ |
| 知识管理（确认/发布） | ✓ | ✓ | ✗ |

---

## 8. 接口设计

| 方法/路径 | 说明 | 项目角色 |
| --- | --- | --- |
| `GET /api/project/repositories` | 列出当前用户可访问的 GitHub 仓库（经 GitHub Integration） | 登录 |
| `POST /api/project` | 创建项目（选择仓库） | 登录 |
| `GET /api/project` | 项目列表（分页，含状态） | 登录（仅自己的项目） |
| `GET /api/project/{id}` | 项目详情 | 项目成员 |
| `PATCH /api/project/{id}` | 编辑项目名称/描述 | OWNER |
| `POST /api/project/{id}/archive` | 归档 / 恢复 | OWNER |
| `DELETE /api/project/{id}` | 删除（V1 为归档；彻底删除后续） | OWNER |
| `POST /api/project/{id}/sync` | 手动触发 GitHub 同步（分支 + Contributors） | MEMBER+ |
| `GET /api/project/{id}/branches` | 分支列表 | 项目成员 |
| `GET /api/project/{id}/members` | 成员列表（含待绑定占位） | 项目成员 |
| `PUT /api/project/{id}/members/{memberId}` | 调整角色 / 状态 | OWNER |
| `DELETE /api/project/{id}/members/{memberId}` | 移除成员 | OWNER |
| `POST /api/project/{id}/assessment` | 触发整体评估（异步、限频） | MEMBER+ |
| `GET /api/project/{id}/assessment-status` | 整体评估状态与最近结果 | 项目成员 |
| `GET /internal/project/{id}/role?userId=` | 校验用户项目角色（供其他服务调用） | 内部服务令牌 |
| `GET /internal/project/{id}/member-user-ids` | 项目成员平台用户 ID 集（供知识/权限计算） | 内部服务令牌 |

---

## 9. 核心数据（字段级）

### projects

```text
id                  BIGINT PK
name                VARCHAR(100)
description         VARCHAR(500) NULL
owner_user_id       BIGINT FK → users.id
status              VARCHAR(20)      -- ACTIVE / ARCHIVED
initial_analysis    VARCHAR(20)      -- NOT_STARTED / QUEUED / RUNNING / COMPLETED / FAILED
last_assessment_at  TIMESTAMP NULL   -- 用于整体评估限频
created_at / updated_at / archived_at
```

### github_repositories（GitHub 元数据镜像）

```text
id              BIGINT PK
owner           VARCHAR(100)        -- GitHub owner
name            VARCHAR(100)
full_name       VARCHAR(200) UNIQUE
private         BOOLEAN
default_branch  VARCHAR(100)
description     VARCHAR(500) NULL
html_url        VARCHAR(512)
pushed_at       TIMESTAMP NULL
status          VARCHAR(20)         -- OK / UNREACHABLE
last_synced_at  TIMESTAMP NULL
```

### project_repositories（项目 ↔ 仓库关联）

```text
id            BIGINT PK
project_id    BIGINT FK → projects.id
repository_id BIGINT FK → github_repositories.id
role          VARCHAR(20)   -- MAIN（主仓库，V1 一般仅一个）
UNIQUE (project_id, repository_id)
```

### project_members

```text
id             BIGINT PK
project_id     BIGINT FK
user_id        BIGINT FK NULL        -- 未注册用户为空（占位）
github_user_id VARCHAR(64) NOT NULL  -- 贡献者 GitHub 数字 ID
login          VARCHAR(100)
role           VARCHAR(20)           -- OWNER / MEMBER / VIEWER
source         VARCHAR(20)           -- CREATOR / GITHUB_CONTRIBUTOR / MANUAL
status         VARCHAR(20)           -- ACTIVE / PENDING_BIND / REMOVED
joined_at      TIMESTAMP
UNIQUE (project_id, github_user_id)
```

### project_branches

```text
id            BIGINT PK
project_id    BIGINT FK
repository_id BIGINT FK
name          VARCHAR(100)
head_sha      VARCHAR(64)
is_default    BOOLEAN
last_synced_at TIMESTAMP
UNIQUE (repository_id, name)
```

### assessment_scores（V1 预留）

```text
id          BIGINT PK
project_id  BIGINT FK
dimension   VARCHAR(30)   -- ARCHITECTURE / CODE_QUALITY / MAINTAINABILITY / SECURITY
score       NUMERIC(5,2)  -- 0-100
evaluated_at TIMESTAMP
```

（计算逻辑在 Analysis 域异步产出，V1 仅预留结构，见项目定义 §9 C 方案。）

---

## 10. 与其他服务关系

```text
Gateway ──→ Project（路由 + X-User-Id 透传）
Project ──→ GitHub Integration（仓库/分支/贡献者数据，同部署单元内调用）
Project ──→ Analysis（触发初始分析、整体评估；分析状态回写）
Project ──→ Knowledge（项目知识空间上下文）
Review / Knowledge / RAG ──→ Project（内部接口校验项目角色与成员）
```

关键约定：

- Project 不直接调用 GitHub API（只经 GitHub Integration）；
- 其他服务校验项目权限一律走 `/internal/project/**`，不复制成员数据；
- 项目归档后：不再触发新分析/评估，但历史 Review 与知识仍可查询。

---

## 11. 异常处理

| 场景 | 处理 |
| --- | --- |
| 仓库不可达 / 404（删除或无权限） | 标记 UNREACHABLE，创建/同步失败返回提示，不阻塞已有项目 |
| GitHub 限流 403/429 | 指数退避；同步任务可重试（记录失败原因） |
| 重复创建同仓库项目 | 允许（同一仓库可被多个项目引用）；同用户重复创建提示已有 |
| 未注册 Contributor | 占位成员，登录后自动绑定，不报错 |
| 成员手动调整后 Contributors 再次同步 | 手动调整优先，不覆盖 |
| 非项目成员访问 | 403（Project 本地角色校验） |
| 归档后触发分析/评估 | 拒绝并返回项目已归档 |

---

## 12. 异步流程

```text
创建项目
  ├── 分支同步任务
  ├── Contributors 同步任务
  └── 初始分析任务（Analysis Service，含 Project Profile + Code Graph 冷启动）
手动同步 → 分支 / Contributors 刷新
整体评估 → 用户触发（MEMBER+）→ 异步执行 → 限频（如 24h 一次）
```

- 异步任务统一走 PostgreSQL 任务表 + Scheduler（见项目定义 §14.4），不引入消息队列；
- 任务状态落库（analysis_tasks 在 Analysis 域；Project 侧保存最新状态与时间）。

---

## 13. AI 调用点

Project Service **不直接调用 LLM**：

- Project Profile 生成、Code Graph 冷启动 → Analysis Service（AI 在其中）；
- 整体评估评分 → Analysis Service 异步产出；
- 本模块只负责编排与状态跟踪。

---

## 14. 权限要求

- 项目角色判定在 Project Service 本地，接口对非成员返回 403；
- `/internal/project/**` 仅服务网络可达（内部服务令牌）；
- 占位成员数据（github_user_id / login）属于半公开信息，页面按成员可见；
- 归档项目的知识/Review 权限不因归档变化（仍按角色）。

---

## 15. 技术实现建议

- ORM：MyBatis-Flex（数据归属 project schema）；
- 服务间调用：同部署单元内 Project ↔ GitHub Integration 直接方法/本地调用；跨服务（Analysis / Knowledge）用 HTTP 接口 + 内部令牌；
- 仓库元数据同步：接入时全量、之后手动/按需，不做常驻轮询；
- 分页：仓库列表、项目列表、成员列表统一分页；
- 软删除：status=ARCHIVED，查询默认过滤。

---

## 架构约束（P0）

1. **仓库访问仅经 GitHub App 安装覆盖范围**：项目只能关联 App 已安装授权的仓库；未安装/被移除的仓库标记 UNREACHABLE，不得绕过安装状态创建项目；
2. **成员权限与知识同步**：用户被移出项目后，其个人引用（Reference）对该项目知识同步失效（见模块 06）；
3. **异步任务统一走 PostgreSQL 任务表 + Scheduler**（status / next_run_at / 指数退避，见项目定义 §14.4），不引入消息队列；
4. **服务间调用走 HTTP 接口 + 内部令牌**；跨服务 HTTP 客户端统一 OpenFeign（版本由 Spring Cloud 2025.1.3 BOM 管理，见项目定义 §13）。
5. **V1 单仓库约束**：1 个项目仅绑定 1 个 Repository，令牌作用域、Graph 状态、Review 归属均按单仓库设计；
6. **Contributors 仅成员发现**：项目角色（OWNER/MEMBER/VIEWER）由平台独立管理，与 GitHub 权限解耦。

## 设计细则（P1 必检项）

- 仓库元数据只存展示/分析所需字段，**不存完整源码**（持久化边界见项目定义 §3.4）；
- Contributors 手动调整优先于后续同步，不覆盖；
- 归档项目不再触发新分析/评估，历史 Review/知识按角色仍可读。
- 触发分析/评估等任务时，向 Integration 传入 `installationId + repositoryId`（单仓库令牌作用域）。
- **项目角色权限用 Sa-Token 动态加载**：实现 `StpInterface`，按 loginId 返回项目角色权限码（`project:owner` / `project:member` / `project:viewer`），业务接口用 `@SaCheckPermission` 或 `StpUtil.checkPermission` 判定。

## 16. 实现优先级

### 【毕设必须】

- 创建项目（选择仓库 → 元数据镜像 + 关联）
- 项目列表 / 详情 / 归档
- 分支同步
- Contributors → 成员发现（含未注册占位与登录后绑定）
- 项目角色判定（OWNER/MEMBER/VIEWER）与内部校验接口
- 初始分析触发与状态跟踪
- 整体评估触发（异步、限频）与状态查询

### 【建议实现】

- 手动同步（分支 + Contributors）
- 成员手动调整（改角色 / 移除）
- 多仓库关联（数据结构已支持）

### 【可选扩展】

- 项目健康度评分展示（assessment_scores，V1 仅结构）
- 项目徽章 / 状态摘要

### 【暂不实现】

- 彻底删除与级联清理
- 邀请链接 / 通知
- Webhook 自动同步（V1 已支持 Commit 事件，见模块 04 §15.1；Contributor 自动同步仍为后续）

---

## 17. 后续扩展

1. **多仓库项目**：主仓库 + 关联仓库，Commit/Review 按仓库聚合；
2. **Contributor 自动同步**：Webhook 驱动的成员/贡献者变化自动同步（V2）；
3. **团队项目**：项目间共享知识导入（复制/引用已在知识域设计）；
4. **项目归档自动策略**：长期不活跃项目自动归档（可选）。

---

## 18. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| 项目与仓库关系 | V1 业务约束：1 项目 = 1 仓库；数据模型保留 1:N 预留（未来放开） | 已定 |
| 成员发现 | GitHub Contributors 自动发现；未注册用户占位、登录后绑定 | 已定 |
| 手动调整 vs 同步覆盖 | 手动调整优先，同步不覆盖 | 已定 |
| 项目删除 | V1 仅软删除（ARCHIVED），历史 Review/知识保留 | 已定 |
| 角色判定位置 | Project Service 本地；其他服务走 /internal 校验 | 已定 |
| 整体评估触发 | MEMBER+ 手动触发、异步、限频（如 24h 一次） | 已定 |
| 项目健康度 | V1 仅预留 assessment_scores 结构，评分趋势 V2（C 方案） | 已定 |
