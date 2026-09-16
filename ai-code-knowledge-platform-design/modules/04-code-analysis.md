# 04 Code Analysis Service（代码分析）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写
> 部署：与 Review Service 合并为部署单元 ④ Analysis/Review（逻辑上独立，同进程内模块化编排）

---

## 1. 模块职责

Code Analysis Service 是"审查流水线"的**代码理解层**：

- 首次接入：Repository 结构理解 + Code Graph 冷启动 + Project Profile 生成；
- 增量分析：Commit → Diff → Changed Symbols → Impact Analysis → 有限 Context；
- Context Builder：产出与模型无关的标准分析上下文（AnalysisContext）；
- 静态分析：确定性规则检查（JavaParser 结构规则 + Checkstyle 基础规则集；PMD/SpotBugs 后续）；
- Branch / Module 整体分析与项目评估（异步，C 方案预留评分产出）。

核心原则：

> 只审变更影响范围，不重审整个项目；代码发现靠结构分析与静态规则，LLM 只做理解与判断；Context 受硬性资源限制。

---

## 2. 为什么存在

- Commit Review 不能是 `Diff → LLM`：缺少项目结构、调用关系与历史上下文，LLM 会误判且 Token 失控；
- 需要 Code Graph + Impact Analysis 支撑"增量审查"这一核心差异化能力；
- 静态分析提供确定性证据，避免把"发现"全部交给 LLM（用户明确要求）；
- Analysis 与 Review 分离职责：本模块"理解代码"，Review 模块"判断并给出 Finding"。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| Code Graph（符号/关系）构建与增量更新 | AI Review 与 Finding（Review） |
| Diff 解析、Changed Symbols、Impact Analysis | 知识提炼与 RAG 检索（Knowledge / RAG） |
| Context Builder（代码侧上下文） | 项目/仓库/成员业务（Project） |
| 静态分析（JavaParser + Checkstyle 基础规则集） | GitHub 数据获取（GitHub Integration） |
| 首次分析、Branch/Module 分析、整体评估任务 | 知识上下文检索（Review 阶段合并 RAG） |
| Commit / Commit Change 持久化 | MCP Profile 知识范围（MCP） |

---

## 4. 分析任务体系

```text
ANALYSIS_TASK_TYPE
 ├── PROJECT_INITIALIZATION  首次接入：结构理解 + Code Graph + Project Profile
 ├── COMMIT_ANALYSIS         Commit 增量：Diff → 符号 → 影响 → Context（供 Review）
 ├── BRANCH_ANALYSIS         Branch 整体分析（异步）
 ├── MODULE_ANALYSIS         模块/目录分析（异步）
 └── PROJECT_ASSESSMENT      整体评估（异步、限频；评分 C 方案 V1 仅结构）
```

任务状态机：

```text
QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED
（可重试：FAILED_RETRYABLE → QUEUED，重试次数上限）
（终态：COMPLETED / FAILED / CANCELLED；禁止终态 → RUNNING 转移）
```

任务由 Project 触发或 Review 内部编排触发；进度供前端轮询（V1 默认 5s）；SSE 为 V2 升级点。

---

## 5. 首次分析（冷启动）

### 5.1 流程

```text
Repository（含默认分支）
 → 获取 README / 构建文件 / 配置文件 / Dockerfile / 目录树（GitHub Integration）
 → 语言与技术栈识别（规则优先：pom.xml/build.gradle/package.json/扩展名）
 → 源码拉取（Tree/Blob，分片节流）→ JavaParser AST 解析
 → 构建 Code Graph（全量建图）
 → AI 生成 Project Profile（结构化）
 → 初始知识候选（架构规则/技术栈约束）→ Knowledge 域（用户确认后发布）
```

### 5.2 Project Profile（AI 输出，结构化）

```text
{
  "language": "Java",
  "framework": ["Spring Boot 4.1", "MyBatis-Flex"],
  "architecture": "Monolith / Microservice",
  "modules": ["auth", "community", "mall"],
  "designPatterns": ["Strategy", "Template"],
  "techStack": {"build": "Maven", "db": "PostgreSQL", "cache": null},
  "rules": ["Controller 不允许直接访问 Mapper"],
  "potentialIssues": [],
  "confidence": 0.85
}
```

- Profile 是后续 Commit Review 的固定上下文之一；
- 首次分析的目标是**建立初始认知与知识基础**，不是全量审查。

---

## 6. Code Graph

### 6.1 定位

- 参考开源项目 **codegraph** 的思路（冷启动建图、增量更新），**只借鉴不复制，不拆独立服务**；
- 存储用 PostgreSQL 关系表（`code_symbols` / `code_relations` / `analysis_snapshots`），V1 不上图数据库；
- 论文表述：基于代码结构索引的增量影响分析。

#### 6.1.1 存储压力评估（为什么 V1 不上图数据库）

V1 目标为个人/小团队 Java 项目，PostgreSQL 完全承载，读写压力可控：

- **数据量**：中等规模 Java 工程符号约 1 万 ~ 10 万、关系约 3 万 ~ 30 万行；PG 单表千万级无压力；
- **写入模式**：全量建图仅首次；之后每次 Commit 增量写几百 ~ 几千行（单事务批量提交），一天数十个 Commit 也只是万级写入；
- **读取模式**：Impact / Context 是**有界局部遍历**（深度 ≤ 3、节点 ≤ 50、Token ≤ 12000），查询为 `(project_id, repository_id, branch_id, source_symbol_id) + relation_type` 复合索引点查，单次毫秒级；V1 无全局图算法（PageRank / 社区发现）、无任意深度路径查询；
- **索引策略**：`code_relations` 建 `(project_id, repository_id, branch_id, source_symbol_id, relation_type)`；`code_symbols` 建 `(project_id, file_path)` 与 `(qualified_name)`；`valid_until` 部分索引过滤已删除节点；快照每分支保留最近 N 个（V2 归档）；
- **pgvector**：知识向量规模几千 ~ 几万条，HNSW 毫秒级，与业务同库无瓶颈；
- **图数据库对比**：Neo4j 的收益在全局多跳遍历与图算法，本项目只需局部邻域展开；引入图库反而带来双写一致、部署复杂度与学习成本；
- **何时再评估**：多语言 + 超大仓库 + GraphRAG（沿关系语义检索）时，见模块 07 §19 与本文 §20。

#### 6.1.2 未来转型说明

V1 计算引擎**全 Java（JavaParser）**，不引入 Python / Tree-sitter 服务；若未来转型 C/S 或本地化（桌面 / IDE 插件）项目，可**直接引入成熟开源计算引擎**（如 code-review-graph 的建图与 Blast Radius 思路）替代自研部分。本文保留该扩展点，V1 不提前引入。

### 6.2 符号（Symbols）

V1 覆盖 Java：

```text
CLASS / INTERFACE / ENUM / RECORD / METHOD / CONSTRUCTOR / FIELD / ANNOTATION
```

每个符号记录：

```text
file_path、qualified_name、signature、line_start/line_end、
visibility、modifiers、所在 commit sha（版本）、language
```

### 6.3 关系（Relations）

```text
Strong（强依赖，Context 高优先级）
 ├── CALLS（方法调用）
 ├── OVERRIDES / EXTENDS / IMPLEMENTS（继承/实现）
 └── FIELD_TYPE（字段类型依赖）

Weak（弱依赖，低优先级，按 Token 预算裁剪）
 └── IMPORT / REFERENCES / CONTAINS（导入/引用/包含）
```

**边置信度（V1 定案）**：每条关系记录 `confidence` 标签——

```text
EXTRACTED  解析器确定的强证据（符号解析成功）
INFERRED   跨文件启发式推断（如按 import + 同名符号匹配）
AMBIGUOUS  存在歧义（重载、泛型、动态分发等）
```

- Context 展开顺序：Strong + EXTRACTED 最优先；INFERRED 次之；Weak / AMBIGUOUS 最后且最先被 Token 预算裁剪；
- 置信度仅用于**排序与裁剪**，不阻断关系存储；不影响知识蒸馏的证据账本（该处置信度见模块 06）。

### 6.4 增量更新

```text
Commit（目标 E，父 D）→ Diff → 变更文件
  → 解析变更文件的新版本符号
  → 删除符号标记（valid_until = 当前 sha，不物理删除）
  → 更新受影响符号签名/位置
  → 新增/更新 relations（增量，不重建全图）
  → 推进 current_graph_commit：C → D → E（顺序补齐，禁止跳变）
```

- 每次分析写入 `analysis_snapshots`（project + repository + sha → 图版本），支持回溯；
- **一致性锚点（硬约束）**：Graph State 由 **(project_id, repository_id, branch_id)** 三元组唯一确定，各分支维护独立的 `current_graph_commit`；分支切换时恢复各自图状态，**严禁跨分支复用图状态**；当前图对应 C、目标 E 时，必须按父链顺序补齐中间增量（C→D→E）；检测到缺口（父链不连续/快照丢失）→ 先补增量或重建，**禁止直接跳变**；
- 首次全量建图可能较慢 → 异步任务 + 分片拉取（配合 GitHub 限流）。

---

## 7. Commit 增量分析与 Impact Analysis

### 7.1 流程

```text
Commit（sha + parents[] + diff，GitHub Integration）
 → 判断类型：
     普通 Commit → 进入增量分析
     Merge Commit（parents.length > 1）→ 标记 MERGE_COMMIT，仅记录合并信息，不进行增量 Review
 → commit_changes（文件级变更，4 种语义：ADD / MODIFY / RENAME / DELETE）
 → 定位 Changed Symbols：
     ADD    新增节点
     MODIFY 更新节点（签名/位置/内容变化）
     RENAME 识别为同一 Symbol 的位置迁移（更新 file_path/行号）
     DELETE 逻辑删除（valid_until = 当前 commit，禁止物理删除节点）
 → Code Graph 沿 Strong 关系（CALLS / OVERRIDES / IMPLEMENTS / FIELD_TYPE）优先展开
      → 影响范围（调用方、被调用方、强类型依赖）；Weak 关系按 Token 预算裁剪
 → 深度受限：max_impact_depth
 → 产出 AnalysisContext
```

### 7.2 审查范围约定

- **只审变更及其影响范围**，不审整个项目；
- **Merge Commit 不进行增量 Review**：标记 `MERGE_COMMIT`，仅记录合并信息（parents、合并文件摘要），不构建增量上下文；
- 大 Commit：超过资源限制时截断并标记"部分审查"（见第 9 节）；
- Commit Message 与变更意图进入上下文，供 Review 动态制定审查重点。

---

## 8. Context Builder

### 8.1 两级模式

**默认模式（AI / Graph 自动）**：

```text
固定优先级（按 Token 预算顺序裁剪，禁止全量拼接）：
  P0 当前 Diff
  P1 当前方法 / 类
  P2 直接调用方
  P3 强类型依赖（Strong）
  P4 项目规则
  P5 历史 Finding
  P6 用户指定范围
```

**自定义模式（用户指定）**：

```text
☑ 当前方法片段
☑ 调用方
☑ 被调用方法
☐ 同类其他方法
☐ 全模块
☑ Project Profile
☐ 历史 Review 片段
```

**自定义上下文 = Hard Include（强制包含）**：用户手动指定的文件/符号**不参与优先级排序**，无条件进入 Context；剩余 Token 预算再按默认优先级（P0~P5）自动填充。

### 8.2 输出：AnalysisContext（模型无关）

```json
{
  "commit": {"sha": "...", "message": "...", "parent": "..."},
  "changedSymbols": ["com.x.OrderService.createOrder()"],
  "impactScope": {"depth": 2, "nodes": ["OrderController", "OrderMapper", "Order"]},
  "codeFragments": [
    {"file": "OrderService.java", "symbol": "createOrder",
     "before": "...", "after": "...", "lines": "120-145"}
  ],
  "projectProfile": {"framework": "Spring Boot", "architecture": "..."},
  "staticFindings": [
    {"rule": "MethodLength", "file": "OrderService.java", "line": 130, "severity": "WARN"}
  ]
}
```

> 知识侧上下文（项目规则/历史错误）由 **Review 阶段调用 RAG** 合并，不在本模块产出——保证 Analysis 不依赖 RAG/Knowledge，RAG 挂掉时代码分析仍可用（解耦要求）。

---

## 9. 静态分析（确定性检查层）

### 9.1 工具

| 工具 | 作用 | V1 优先级 |
| --- | --- | --- |
| **JavaParser** | AST 解析、符号提取、结构分析（Code Graph 基础） | 必须 |
| **Checkstyle** | 编码规范、命名、行长等规则（基础规则集） | 必须 |
| **PMD** | 潜在缺陷、圈复杂度等规则 | 后续（不阻塞 V1） |
| **SpotBugs** | 字节码级缺陷/安全规则 | 不引入（V2） |

> V1 静态分析范围（v3.3 定案）：JavaParser 结构规则 + Checkstyle 基础规则集为必做项；PMD / SpotBugs 不在毕设范围内实现。

### 9.2 定位

```text
静态分析 → 确定性 Finding 候选（规则命中、位置、严重度）
          ↓
Review 阶段与 LLM Finding 合并展示
          ↓
用户确认/拒绝（Rejected 不进知识提炼）
```

- 静态分析负责"发现"，LLM 负责"理解与判断"；
- 静态规则输出可进一步被 METRIC_RULE 知识消费（度量阈值 → 静态分析器，形成闭环，后续版本）。

---

## 10. 资源限制（硬约束）

| 限制项 | 默认值（可配置） | 说明 |
| --- | --- | --- |
| max_impact_depth | 3 | 影响分析最大深度 |
| max_files | 50 | 单任务最多处理的文件数 |
| max_code_fragments | 100 | 进入 Context 的代码片段上限 |
| max_context_tokens | 12000 | 代码上下文 Token 上限 |
| max_llm_calls | 5 | 单任务 LLM 调用次数上限 |
| task_timeout | 10 min | 单任务超时 |

- 超限策略：截断 + 标记 `PARTIAL`（部分审查），而不是失败；
- 配置走 Nacos，按项目可覆盖（Context 自定义模式受同一上限约束）。

---

## 11. 数据模型（字段级）

### analysis_tasks

```text
id              BIGINT PK
project_id      BIGINT FK
repository_id   BIGINT FK NULL
branch_id       BIGINT FK NULL     -- 任务归属分支（Commit 合并 / 并发控制键）
type            VARCHAR(30)   -- PROJECT_INITIALIZATION / COMMIT_ANALYSIS / BRANCH_ANALYSIS / MODULE_ANALYSIS / PROJECT_ASSESSMENT
ref             VARCHAR(128)  -- 分支名 / commit sha / 模块路径
target_sha      VARCHAR(64) NULL   -- Commit 任务目标 SHA（时间窗口内滚动到最新）
pending_sha     VARCHAR(64) NULL   -- RUNNING 期间到达的最新 SHA（完成后接力追赶）
status          VARCHAR(20)   -- QUEUED / RUNNING / COMPLETED / FAILED / FAILED_RETRYABLE / CANCELLED（COMPLETED/FAILED/CANCELLED 为终态）
next_run_at     TIMESTAMP NULL     -- 到期时间（时间窗口 / 指数退避）
retries         INT
result_ref      VARCHAR(255) NULL   -- 结果位置（如 snapshot id / review id）
error           TEXT NULL
created_at / started_at / finished_at / updated_at

-- 合并去重：同一分支同一类型最多一个 QUEUED 任务
UNIQUE (project_id, repository_id, branch_id, type) WHERE status = 'QUEUED'
```

### commits / commit_changes

```text
commits:
 id, project_id, repository_id, sha(UNIQUE per repo), message,
 author_name, author_email, authored_at, parents(TEXT[]), merge_flag BOOLEAN,
 stats JSONB, synced_at

commit_changes:
 id, commit_id FK, file_path, status(ADD/MODIFY/RENAME/DELETE),
 additions, deletions, before_sha, after_sha, UNIQUE(commit_id, file_path)
```

### code_symbols / code_relations / analysis_snapshots

```text
code_symbols:
 id, project_id, repository_id, snapshot_id,
 file_path, symbol_type, name, qualified_name, signature,
 line_start, line_end, visibility, modifiers(TEXT[]),
 version_sha, valid_from, valid_until NULL, language
 INDEX(project_id, file_path), INDEX(qualified_name)

code_relations:
 id, project_id, snapshot_id,
 source_symbol_id FK, target_symbol_id FK,
 relation_type(CALLS/EXTENDS/IMPLEMENTS/FIELD_TYPE/IMPORT/CONTAINS/REFERENCES),
 confidence(EXTRACTED/INFERRED/AMBIGUOUS),
 valid_from, valid_until NULL

analysis_snapshots:
 id, project_id, repository_id, branch_id,
 sha, kind(FULL/INCREMENTAL),
 current_graph_commit, prev_graph_commit NULL,
 symbol_count, relation_count, created_at
```

---

## 12. 接口设计

| 方法/路径 | 说明 | 鉴权 |
| --- | --- | --- |
| `POST /api/analysis/projects/{id}/initialize` | 触发首次分析（由 Project 创建后调用） | 项目 MEMBER+ |
| `GET /api/analysis/tasks/{taskId}` | 任务状态与进度 | 项目成员 |
| `GET /api/analysis/projects/{id}/codegraph/symbols?file=&q=` | 查询符号 | 项目成员 |
| `GET /api/analysis/projects/{id}/codegraph/symbols/{sid}/relations` | 符号关系展开 | 项目成员 |
| `POST /api/analysis/projects/{id}/branches/{branch}/analyze` | Branch 整体分析（异步） | MEMBER+ |
| `POST /api/analysis/projects/{id}/modules/{path}/analyze` | 模块分析（异步） | MEMBER+ |
| `POST /api/analysis/projects/{id}/assessment` | 整体评估（异步、限频） | MEMBER+ |
| `GET /internal/analysis/context?commitId=` | 为 Review 产出 AnalysisContext（含静态分析） | 内部服务令牌 |
| `GET /internal/analysis/tasks/status?taskId=` | Review 轮询任务 | 内部服务令牌 |

---

## 13. 与其他服务关系

```text
Project ──→ Analysis（触发初始化/评估；读任务状态）
Review ──→ Analysis（同部署单元：Commit/Diff 与上下文获取一律经本模块，Review 不直接调用 GitHub Integration）
Analysis ──→ GitHub Integration（Commit/Diff/Tree/Blob，跨部署单元 HTTP 接口 + 内部令牌）
Analysis ──→ Knowledge（仅初始化阶段提交初始知识候选；不依赖其可用性）
```

- **硬约束：Analysis 是代码理解唯一出口**——Review 的 Commit/Diff 数据获取必须经本模块（同进程），不允许 Review 直接跨进程调用 GitHub Integration；
- Analysis **不依赖 RAG/Knowledge 完成核心分析**（RAG 挂 → Review 降级，但代码理解仍可用）；
- Analysis **不调用 MCP**；
- 与 Review 同部署单元：Context 传递走进程内调用，不落跨进程调用。

---

## 14. 异常处理

| 场景 | 处理 |
| --- | --- |
| GitHub 拉取失败 | 任务 FAILED_RETRYABLE（结合 Integration 退避），连续失败置 FAILED |
| 仓库/分支被删除 | 标记 UNREACHABLE，任务 FAILED 并记录原因 |
| 大 Commit 超限 | 截断 + PARTIAL 标记，不失败 |
| Parser 失败（语法错误/不支持的语法） | 跳过该文件并记录，不阻塞任务 |
| Code Graph 快照冲突 | 以 commit sha 版本化隔离，异常时回滚到上一快照 |
| 静态分析工具缺失/超时 | 静态分析降级（跳过该工具），代码分析不中断 |

---

## 15. 异步流程

```text
Commit 事件（Webhook / 分支轮询 / 模拟事件，见 15.1）→ 发布（任务表合并/插入）
 → Scheduler 订阅（轮询到期任务，见 15.2）
 → Worker 执行（拉取 → 解析 → 建图/增量 → Context/Profile → 结果落库）
 → 状态更新（QUEUED → RUNNING → COMPLETED/FAILED）
 → 前端轮询获取进度（V1；SSE 为 V2 升级点）
```

- 首次分析、Branch/Module 分析、评估全部异步；
- Commit 分析由 Review 任务编排（Review 域内发起，内部走 COMMIT_ANALYSIS 子流程）；
- 知识蒸馏、Embedding 等下游任务共用同一调度器与合并/并发规则（见模块 06 §14）。

### 15.1 发布-订阅语义与时间窗口（任务合并）

**发布-订阅语义由 PostgreSQL 任务表 + Scheduler 实现，不引入消息队列**（发布 = 事件落任务表，订阅 = 调度器消费；可选 PG LISTEN/NOTIFY 做即时唤醒优化，默认 5s 轮询；v3.3 复审评估记录见项目定义 §14.4.1）。

Commit 事件入队采用**按分支合并 + 时间窗口（防抖）**：

事件源（V1 定案，双模式）：

```text
Webhook（生产/演示默认）：GitHub App Webhook → HMAC 验签（Webhook Secret）+ 防重放 → 发布事件
分支轮询（开发兜底）：默认 60s（Nacos 可配置），命中即发布——本地无公网域名时可独立调试
模拟事件（调试入口）：本地/测试注入模拟 Push 事件（仅 dev profile 开放，不进入生产白名单）
```

- 配置 `codecycle.analysis.event-source=polling|webhook`（dev 默认 polling，prod 默认 webhook），见"扩展点与配置开关"；
- Webhook 路由统一为 `POST /api/webhooks/github`：Gateway 白名单接收，Auth 应用层 HMAC 验签与防重放，Analysis 校验项目/仓库后创建任务；
- 本地调试不依赖生产域名：无公网回调时用分支轮询或模拟事件即可完成全链路测试；
- 调度器轮询（5s）与分支轮询（60s）是两个独立频率。

```text
事件：新 Commit（project, repository, branch, sha）
 ├─ 该分支无 QUEUED 任务 → 新建：target_sha=sha, next_run_at=now+WINDOW
 ├─ 已有 QUEUED 任务 → 滚动合并：target_sha=最新 sha, next_run_at 顺延
 └─ 已有 RUNNING 任务 → 不打断，记录 pending_sha（完成后接力追赶）
窗口结束 → 任务开始，审查范围 = 上次成功点 → target_sha（按父链顺序逐个处理）
```

- WINDOW 默认 90s（可配置）：把短时间内多次 Push 合并为一次范围 Review，避免"审到一半又来新 Commit"；
- MAX_WAIT 默认 5min：慢速推送不无限拖延，`next_run_at = min(now+WINDOW, created_at+MAX_WAIT)`；
- 手动"立即审查"可绕过窗口（next_run_at=now），但分支串行与全局并发上限仍然生效；
- 范围内 Merge Commit 沿用既有规则：标记 MERGE_COMMIT、跳过增量 Review、记录合并信息。

### 15.2 并发限制

```text
分支级串行：同一 (project, repository, branch, type) 同时最多一个 RUNNING 任务
           （QUEUED 部分唯一索引 + 任务认领 FOR UPDATE SKIP LOCKED）
全局上限：analysis.concurrency-limit（默认 2）
           → Scheduler 最多同时运行 N 个分析任务（Java 21 虚拟线程 Worker 数 = N）
资源预算：单任务 max_llm_calls=5、task_timeout=10min
           → 全局在途 LLM 调用 ≤ N × max_llm_calls
失败重试：指数退避（1s→2s→4s…上限 60s），恢复后从上次成功点处理到最新（范围追赶）
```

- 蒸馏/Embedding 任务类型可独立配置并发上限（默认与分析任务共用调度池，见模块 06 §14）；
- GitHub 限流仍以 `X-RateLimit-*` 响应头实时纳入调度（见模块 03），任务失败按 FAILED_RETRYABLE 退避。

---

## 16. AI 调用点

1. **Project Profile 生成**（首次分析，Structured Output）；
2. **Context 相关性选择**（默认模式，AI 判断哪些上下文相关，可选）；
3. 技术栈识别：规则优先，规则不足时 AI 兜底。

约束：

- 均走 Spring AI 抽象（ChatModel，用户自配）；
- 计入 `max_llm_calls` 限额；
- Profile 输出必须结构化，且**不能直接标记为 Published Knowledge**。

---

## 17. 权限要求

- 查询 Code Graph / 触发分析需要项目角色（MEMBER+）；
- `/internal/analysis/**` 仅服务网络可达；
- Code Graph 数据按 project_id 隔离，禁止跨项目访问。

---

## 18. 技术实现建议

- **Parser**：JavaParser（Java 21 语法支持），AST → 符号/关系提取；
- **静态分析**：Checkstyle 以 Java 库/CLI 嵌入（基础规则集）；PMD/SpotBugs 后续引入（字节码级较重，非毕设范围）；
- **存储**：PostgreSQL 关系表 + JSONB（stats/关系元信息）；不引入图数据库；
- **HTTP**：Analysis → GitHub Integration 用 HTTP 接口 + 内部令牌；
- **调度**：虚拟线程（Java 21）执行任务；PostgreSQL 任务表 + Scheduler 轮询（status / next_run_at / 指数退避，见项目定义 §14.4）；`FAILED_RETRYABLE` 可回到 `QUEUED`，不属于终态；
- **配置**：资源限制、超时、静态分析规则集全部 Nacos 可配。

---

## 架构约束（P0）

1. **Code Graph 一致性锚点**：Graph State 由 **(project_id, repository_id, branch_id)** 三元组唯一确定，各分支独立维护 `current_graph_commit`，**严禁跨分支复用图状态**；增量必须按父链顺序补齐（C→D→E），检测到缺口先补增量或重建，**禁止直接跳变**；
2. **Context 优先级硬约束**：P0 当前 Diff → P1 当前方法/类 → P2 直接调用方 → P3 强类型依赖 → P4 项目规则 → P5 历史 Finding → P6 用户指定；按 Token 预算裁剪，禁止全量拼接；
3. **Analysis 是代码理解唯一出口**：Review 的 Commit/Diff 必须经本模块获取，不允许跨过本模块直连 GitHub Integration；
4. **源码不落盘（工程化表述）**：应用层禁止主动持久化完整源码至业务数据库、对象存储及普通日志；分析任务允许使用内存或受控临时空间，任务结束立即清理；
5. **异步任务统一走 PostgreSQL 任务表 + Scheduler**，不引入消息队列；
6. **Merge Commit 不增量 Review**：parents[] 多父提交仅记录合并信息；
7. **V1 语言边界**：仅 **Java** 构建完整 Code Graph 与静态分析；Vue/JS/TS/YAML/SQL 等仅作为项目上下文元数据处理，不构建语言级图谱。
8. **扩展点装配（Spring @Conditional 硬约束，v3.4）**：本模块扩展点（Parser/静态分析工具/事件源/评估产出）一律通过 `@ConditionalOnProperty` 按需装配，未启用不加载；application.yml 声明开关并附注释（默认值/可选值/启用影响），禁止 if-else 硬编码扩展；规范见项目定义 §3.6。

## 设计细则（P1 必检项）

- Impact 强弱区分：Strong（CALLS/OVERRIDES/IMPLEMENTS/FIELD_TYPE）高优先级；Weak（IMPORT/REFERENCES/CONTAINS）低优先级、按预算裁剪；
- 变更语义：ADD 新增节点 / MODIFY 更新节点 / RENAME 同一 Symbol 位置迁移 / DELETE 逻辑删除（valid_until=当前 commit，禁止物理删除）；
- **自定义上下文 = Hard Include**：用户指定文件/符号强制包含，不参与优先级排序；剩余预算按 P0~P5 填充；
- 快照记录 `current_graph_commit` / `prev_graph_commit`，异常可回滚到上一快照；
- 资源限制：影响深度 / 文件数 / 片段数 / Context Token / LLM 调用次数 / 超时六项硬约束，超限截断标 PARTIAL 不失败；
- 静态分析负责"发现"，LLM 负责"理解与判断"，规则输出与 LLM Finding 合并展示。

### 扩展点与配置开关（v3.4）

| 扩展点 | V1 实现（默认） | 未来扩展 | 配置键（application.yml） | 默认值 |
| --- | --- | --- | --- | --- |
| Parser 实现 | JavaParser | Tree-sitter / 开源计算引擎（多语言/本地化，见 §6.1.2） | `codecycle.analysis.parser` | javaparser |
| 静态分析扩展工具 | JavaParser 结构规则 + Checkstyle（必做） | PMD / SpotBugs | `codecycle.analysis.static-analysis.pmd-enabled` / `spotbugs-enabled` | false / false |
| Commit 事件源 | Webhook（验签 + 防重放）+ 分支轮询/模拟事件（开发兜底） | Webhook 驱动自动 Review（review_change，V2） | `codecycle.analysis.event-source` | webhook（prod）/ polling（dev） |
| 项目评估产出 | 结构指标（C 方案 V1） | 评分聚合（V2） | `codecycle.analysis.assessment.score-enabled` | false |

- 开关必须带注释：默认值、可选值、启用影响（如 pmd-enabled=true 时装配 PMD 规则引擎并入 Finding 候选）；
- 未启用实现不装配，主链路（JavaParser/Checkstyle/轮询/结构指标）零额外依赖。

## 19. 实现优先级

### 【毕设必须】

- 首次分析（README/构建文件/目录树 → 技术栈 → Project Profile）
- Code Graph 冷启动（JavaParser：类/方法/字段/关系）
- Commit 增量（diff → changed symbols → impact）
- Context Builder（默认模式 + 资源限制）
- 静态分析（JavaParser 结构规则 + Checkstyle 基础规则集）
- analysis_tasks 状态机与前端进度
- AnalysisContext 输出（供 Review）

### 【建议实现】

- 自定义模式（用户指定上下文）
- Branch / Module 分析
- 删除符号的版本化（valid_until）
- 静态规则集按项目配置

### 【可选扩展】

- PMD / SpotBugs
- Project Assessment 评分产出（C 方案结构）

### 【暂不实现】

- 多语言解析（仅 Java）
- Python / Tree-sitter 计算引擎（计算层全 Java，不引入 Python 服务）
- 图数据库 / Graph RAG
- 复杂控制流/数据流分析

---

## 20. 后续扩展

1. **多语言**：Parser 抽象（JavaParser → Tree-sitter 等），Code Graph 通用化；
2. **度量闭环**：METRIC_RULE 知识 → 静态分析规则动态生效；
3. **Impact 自适应**：按模块规模动态调整深度；
4. **Graph RAG**（V2 后评估）：代码结构语义检索，作为知识检索的增强。
5. **本地化 / C/S 转型**：引入成熟开源计算引擎（如 code-review-graph）替代自研图构建（V1 不引入，见 §6.1.2）。

---

## 21. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| 分析语言 | V1 仅 Java（JavaParser）；多语言预留 | 已定 |
| Code Graph 方案 | 参考 codegraph 开源思路，关系表存储，不拆服务、不上图数据库 | 已定 |
| 增量策略 | Diff → Changed Symbols → Impact，只审影响范围；超限截断标 PARTIAL | 已定 |
| Context 归属 | 代码上下文在本模块；知识上下文由 Review 阶段合并 RAG（解耦） | 已定 |
| 静态分析 | V1：JavaParser 结构规则 + Checkstyle 基础规则集（必须）；PMD/SpotBugs 后续 | 已定 |
| 资源限制 | 深度/文件/片段/Token/LLM 次数/超时六项硬约束 | 已定 |
| Graph 状态 | (project_id, repository_id, branch_id) 三元组唯一确定，分支间严格隔离 | 已定 |
| Merge Commit | parents[] 多父提交标记 MERGE_COMMIT，仅记录合并信息，不增量 Review | 已定 |
| 变更语义 | ADD / MODIFY / RENAME / DELETE（DELETE 逻辑删除，valid_until 标记） | 已定 |
| 自定义 Context | Hard Include 强制包含，不参与优先级排序 | 已定 |
| 语言边界 | V1 仅 Java 语言级图谱；其余语言作项目上下文元数据 | 已定 |
| 计算引擎 | 全 Java（JavaParser）；不引入 Python/Tree-sitter 服务；未来本地化再评估开源引擎 | 已定 |
| 边置信度 | EXTRACTED / INFERRED / AMBIGUOUS 三档，用于 Context 排序与裁剪 | 已定 |
| 图存储 | PostgreSQL 关系表（V1 不上图数据库）；规模/查询有界，压力可控（见 §6.1.1） | 已定 |
| 任务合并与并发 | 分支合并 + 时间窗口 + 全局并发上限（发布-订阅语义由任务表 + Scheduler 实现） | 已定 |
| Commit 事件源 | V1 双模式：Webhook（prod 默认，HMAC 验签 + 防重放）+ 分支轮询 / 模拟事件（dev 兜底，本地可测）；自动 Review V2 | 已定 |
