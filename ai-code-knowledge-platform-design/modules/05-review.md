# 05 Review Service（AI 审查与 Finding）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写
> 部署：与 Code Analysis Service 合并为部署单元 ④ Analysis/Review（逻辑上独立，同进程内编排）

---

## 1. 模块职责

Review Service 是"审查流水线"的**判断层**：

- 基于 Analysis Context 与 RAG 知识执行 AI Review，产出 Finding；
- 动态制定审查计划（不固定模板）；
- 管理 Finding 生命周期（Status：OPEN → CONFIRMED / REJECTED / IGNORED；Source：LLM / STATIC_ANALYSIS / RULE 解耦，见项目定义 §8.1）；
- 将高价值 Finding 异步交接给 Knowledge 域做知识提炼；
- 产出整体评估报告的 AI 总结部分（评分聚合为 C 方案，V2）。

核心原则：

> Review 是可见的"发现"，Knowledge 是筛选后的"沉淀"；Rejected Finding 不进知识提炼；Review 是知识生产器，不是最终目的。

---

## 2. 为什么存在

- Commit Review 是平台最核心、最频繁的分析单位，需要独立业务域承载"判断"；
- 与 Code Analysis 分离：理解（结构/静态）与判断（LLM）职责不同、可分别降级；
- Finding 状态管理是知识质量的门槛（用户确认/拒绝直接决定知识提炼）；
- Review → Knowledge 是**产出关系**（异步），保证 MCP / RAG 不依赖 Review 实时可用。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| Commit Review 编排与 LLM 判断 | 代码结构理解 / Code Graph / 静态分析（Code Analysis） |
| Finding 生成、展示、筛选、状态管理 | 知识提炼与发布（Knowledge） |
| 动态审查计划 | 知识检索（RAG，Review 阶段消费） |
| 整体评估报告的 AI 总结 | 评估指标聚合计算（Analysis） |
| 与用户确认/拒绝交互 | GitHub 数据获取（GitHub Integration） |

---

## 4. 核心流程（Commit Review）

```text
用户选择 Commit（或系统任务触发）
 → 创建 review 任务（review 状态机）
 → 请求 Analysis 获取 Commit + Parent + Diff 并产出 AnalysisContext（模块 04；Review 不直接调用 GitHub Integration）
 → RAG 检索项目规则 / 历史错误 / 设计经验（Knowledge，可降级）
 → 制定 Dynamic Review Plan
 → LLM 多轮审查（受控扩展）
 → Finding 生成（结构化输出）
 → 静态 Finding 合并（来源标注 STATIC_ANALYSIS）
 → Review 结果落库，前端展示
 → 用户确认/拒绝/忽略
 → 符合条件的 Finding 异步交接 Knowledge 提炼
```

状态机：

```text
QUEUED → ANALYZING → REVIEWING → COMPLETED / FAILED
```

---

## 5. 动态审查计划（Dynamic Review Plan）

**不使用固定审查模板**，审查重点由上下文动态决定：

```text
输入：Commit Message + 变更文件 + Project Profile + 项目规则 + 历史 Finding
  ↓
生成 ReviewPlan：
{
  "focusAreas": ["事务边界", "幂等性", "订单状态一致性"],
  "hardRules": ["Controller 不得直接访问 Mapper（项目既定规范，LLM 必须遵守，不可忽略）"],
  "suggestedChecks": ["跨资源写操作是否在事务内", "退款是否幂等"],
  "depth": 2,
  "constraints": {"maxTokens": 12000, "maxCalls": 5}
}
```

- **hardRules**：项目既定规范，LLM 必须遵守，不可忽略；
- **suggestedChecks**：AI 自主建议的检查项，可因上下文调整。

示例：

- Commit `feat: add order refund` + Spring Boot 项目 → 重点检查退款流程、订单/支付状态一致性、事务、幂等、异常；
- Commit `refactor: extract payment strategy` → 重点检查架构分层、依赖方向、兼容性；
- 文档类变更 → 通常跳过 LLM 审查（仅静态检查）；
- 配置 / 构建 / Docker / CI 类 → **按敏感性与影响面动态判断**，必要时触发 Security / Infrastructure Review；
- 是否跳过由 Dynamic Review Plan 判定，**不按扩展名一刀切**。

---

## 6. 多轮审查（受控扩展）

```text
Round 1：AnalysisContext + RAG 知识 → LLM 初步 Finding
  ↓（需要更多上下文时）
请求 Analysis 扩展（调用方/被调用方/类型定义，受 max_impact_depth 限制）
  ↓
Round N：补充上下文 → 修正/补充 Finding
  ↓
达到 max_llm_calls / max_context_tokens → 停止，标记 PARTIAL
```

- 每轮扩展必须**显式记录**（review.extensions），便于审计与成本分析；
- 超限时输出已得结论并标记 `PARTIAL_REVIEW`，不失败；
- 默认 1 轮为主，多轮仅在模型明确"需要更多信息"时触发。

---

## 7. Finding 模型

### 7.1 类型

```text
BUG / PERFORMANCE / SECURITY / ARCHITECTURE / CODE_SMELL / DESIGN / BEST_PRACTICE
```

### 7.2 结构（结构化输出）

```json
{
  "type": "ARCHITECTURE",
  "severity": "HIGH",
  "risk": "HIGH",
  "title": "Controller 直接访问 Mapper",
  "description": "OrderController 绕过 Service 直接调用 OrderMapper，违反项目分层规范。",
  "repository": "owner/repo",
  "commitSha": "a83f92d",
  "filePath": "OrderController.java",
  "side": "NEW",
  "lineStart": 42,
  "lineEnd": 45,
  "evidence": [
    {"source": "RAG", "text": "项目规范：Controller 不得直接访问 Mapper"},
    {"source": "CODE_GRAPH", "text": "OrderController → OrderMapper（直接调用）"},
    {"source": "STATIC_ANALYSIS", "text": "checkstyle: LayeredArchitecture 违反"}
  ],
  "suggestion": "改为 OrderController → OrderService → OrderMapper",
  "source": "LLM",
  "confidence": 0.92
}
```

### 7.3 来源（source）

```text
LLM（AI 判断）
STATIC_ANALYSIS（规则命中，确定性）
RULE（项目规则直接命中）
```

### 7.4 状态与用户操作

**Status 与 Source 为两个独立维度，互不耦合**：

```text
Status：OPEN → CONFIRMED / REJECTED / IGNORED
Source：LLM / STATIC_ANALYSIS / RULE（仅作溯源，不参与统计）
```

- 用户可修正严重度/类型（修正后参与统计）；
- **统计口径按 Status 聚合**：OPEN + CONFIRMED 计入，REJECTED 排除；Source 仅作溯源展示；
- **Rejected Finding 默认不得进入知识提炼**（硬约束）；
- 用户可在任何时间改回（误操作恢复）。

---

## 8. Review 结果结构

```text
Review
 ├── commit 摘要（sha、message、作者、时间）
 ├── 变更概览（文件数、增删行）
 ├── ReviewPlan（focusAreas、depth、constraints）
 ├── 统计：按类型 × 严重度计数
 ├── Findings[]
 ├── Suggestions（AI 总结建议）
 ├── 标记：PARTIAL_REVIEW / COMPLETE
 └── 成本：llm_calls、context_tokens_used
```

趋势与聚合数据（供 Project Assessment / 健康度）：

```text
OPEN + CONFIRMED 计入统计；REJECTED 排除（Source 仅溯源，不参与统计）
```

---

## 9. Knowledge 提炼交接（产出关系）

```text
Review COMPLETED
 → 用户确认/拒绝（部分或全部 Finding）
 → 对 CONFIRMED + OPEN（未处理）的 Finding：
     按类型/分族映射知识形态（BUG_AVOIDANCE / PERFORMANCE_STRATEGY /
     SECURITY_RULE / ARCHITECTURE_RULE / METRIC_RULE / GENERATION_TEMPLATE / SKILL）
 → 异步交接 Knowledge 域，进入**四阶段蒸馏流水线**（见模块 06 §6.2）：
     Evidence Preparation（限界上下文，不扫描全库）
     → Candidate Extraction（Finding 粒度）
     → Consolidation（pgvector 相似召回 + LLM 归并）
     → Publication（Risk/Confidence → 发布）
 → 链路：finding → candidate → consolidation → knowledge（三表关联溯源）
 → 发布控制：按类型/风险决定 Auto Publish / PENDING_REVIEW（规则型与 SKILL 一律人工；经验型低风险与 TEMPLATE 低风险自动，见模块 06 §7.1/§7.2）
```

- 交接是**异步、单向**的：Review 不等待 Knowledge 结果，Knowledge 挂掉不影响 Review；
- 提炼规则细节见模块 06（Knowledge）。

---

## 10. Project Assessment（整体评估）

分工约定：

```text
Analysis（模块 04）：
  PROJECT_ASSESSMENT 任务 → 聚合指标
  （Confirmed Finding 统计、静态分析违反数、Code Graph 度量）
Review（本模块）：
  接收聚合指标 → LLM 生成整体评估报告（结构化摘要 + 建议）
```

- 用户手动触发（MEMBER+）、异步、限频（如 24h 一次）；
- **不因每个 Commit 自动重新全量评估**；
- 评分聚合（Architecture / Code Quality / Maintainability / Security）为 **C 方案**：V1 仅预留 `assessment_scores` 结构，V2 实现（见项目定义 §8.3）。

---

## 11. 接口设计

| 方法/路径 | 说明 | 鉴权 |
| --- | --- | --- |
| `POST /api/review/commits/{commitId}` | 触发 Commit Review（异步，返回任务 id） | 项目 MEMBER+ |
| `GET /api/review/commits/{commitId}` | 查看 Commit Review 结果 | 项目成员 |
| `GET /api/review/findings?commitId=&type=&severity=&status=&page=` | Finding 筛选查询 | 项目成员 |
| `PATCH /api/review/findings/{id}` | 确认/拒绝/忽略、修正类型/严重度 | 项目成员 |
| `POST /api/review/findings/{id}/re-extract` | 手动重新发起该 Finding 的知识提炼（可选） | MEMBER+ |
| `GET /api/review/projects/{id}/assessment-report` | 查看整体评估报告 | 项目成员 |
| `GET /internal/review/findings?commitId=&status=` | 供 Knowledge 域读取待提炼 Finding | 内部服务令牌 |
| `GET /internal/review/assessments/{taskId}` | 供 Analysis 交接评估总结结果 | 内部服务令牌 |

---

## 12. 数据模型（字段级）

### reviews

```text
id                 BIGINT PK
project_id         BIGINT FK
repository_id      BIGINT FK
commit_id          BIGINT FK → commits.id
status             VARCHAR(20)   -- QUEUED / ANALYZING / REVIEWING / COMPLETED / FAILED
review_plan        JSONB         -- focusAreas / checks / depth / constraints
summary            TEXT NULL
stats              JSONB         -- 按类型 × 严重度计数
mark               VARCHAR(20)   -- COMPLETE / PARTIAL_REVIEW
extensions         JSONB         -- 多轮扩展记录
llm_calls          INT
context_tokens_used INT
created_at / updated_at / completed_at
UNIQUE (project_id, commit_id)
```

### review_findings

```text
id              BIGINT PK
review_id       BIGINT FK
project_id      BIGINT FK
commit_id       BIGINT FK
type            VARCHAR(30)
severity        VARCHAR(10)   -- CRITICAL / HIGH / MEDIUM / LOW / INFO
risk            VARCHAR(10)   -- HIGH / MEDIUM / LOW
title           VARCHAR(200)
description     TEXT
repository      VARCHAR(200)
commit_sha      VARCHAR(64)
file_path       VARCHAR(512)
side            VARCHAR(10)   -- OLD / NEW
line_start      INT NULL
line_end        INT NULL
evidence        JSONB         -- [{source, text}]
suggestion      TEXT NULL
source          VARCHAR(20)   -- LLM / STATIC_ANALYSIS / RULE
status          VARCHAR(20)   -- OPEN / CONFIRMED / REJECTED / IGNORED
confidence      NUMERIC(4,3) NULL
created_at / updated_at / confirmed_at
INDEX (project_id, commit_id, type, severity, status)
```

---

## 13. 与其他服务关系

```text
Review ──→ Analysis（同部署单元：取 AnalysisContext、请求扩展上下文）
Review ──→ RAG/Knowledge（检索项目规则/历史错误；可降级）
Review ──→ Knowledge（异步交接 Knowledge Candidate，单向）
Project ──→ Review（触发/查看，项目角色校验）
```

- **硬约束：Review 不直接调用 GitHub Integration**——Commit/Diff 一律经 Analysis（同进程）获取，Analysis 是代码理解唯一出口，保证上下文管控不被绕过；

解耦验证：

```text
Review 挂 → MCP 知识查询 / 历史 Review 查看正常（知识为独立资产）
RAG 挂   → Review 降级（无知识上下文，静态分析 + LLM 仍可审）
MCP 挂   → Review 完全不受影响
```

---

## 14. 异常处理

| 场景 | 处理 |
| --- | --- |
| LLM 输出非法（非 JSON / 缺字段） | Structured Output 校验 + 自纠重试（计入调用次数），超限标记 FAILED |
| LLM Provider 不可用 | 任务 FAILED_RETRYABLE；若已配置静态分析，可先行展示静态 Finding |
| 大 Commit 超限 | 截断 + PARTIAL_REVIEW 标记 |
| 知识交接失败 | 入队重试；Review 结果不受影响 |
| 用户确认冲突（并发） | 乐观锁（version 字段），失败提示刷新 |
| 重复触发同一 Commit Review | 返回已有结果（UNIQUE 约束）或允许强制重审（新版本记录） |

---

## 15. 异步流程

```text
POST /api/review/commits/{id}
 → 创建 review + 任务（QUEUED）
 → Worker：ANALYZING（Analysis）→ REVIEWING（LLM）→ COMPLETED
 → 前端轮询获取进度（V1；SSE 为 V2 升级点）
 → COMPLETED 后触发 Knowledge Candidate 交接（异步）
```

- Commit Review、整体评估均为异步；
- 任务调度：PostgreSQL 任务表 + Scheduler（status / next_run_at / 指数退避，见项目定义 §14.4）。

---

## 16. AI 调用点

1. **Dynamic Review Plan 生成**（可选，规则可先行）；
2. **LLM Review**（主调用，多轮受控）；
3. **整体评估报告总结**；
4. Structured Output 校验失败时的**自纠**（计入调用次数）。

约束：

- 全部走 Spring AI（ChatModel + ChatClient + StructuredOutputValidationAdvisor）；
- 计入 `max_llm_calls` / `max_context_tokens`；
- 模型输出**不能直接成为 Knowledge**，必须经 Knowledge 域流程。

---

## 17. 权限要求

- 触发 Review 需项目 MEMBER+；查看需项目成员；
- Finding 状态修改需项目成员（建议 MEMBER+）；
- `/internal/review/**` 仅服务网络可达；
- Finding 按 project_id 隔离，禁止跨项目访问。

---

## 18. 技术实现建议

- **结构化输出**：Spring AI Structured Output + 校验 Advisor，非法输出自纠重试；
- **多轮控制**：Review 编排器记录轮次与 Token，遵守模块 04 资源限制；
- **合并展示**：LLM Finding 与静态 Finding 统一模型（source 区分），前端同列表展示可筛选；
- **统计**：Review 完成后异步聚合（统计口径排除 Rejected），供评估与趋势；
- **乐观锁**：Finding 状态更新用 version 字段。

---

## 架构约束（P0）

1. **Review 不直接调用 GitHub Integration**：Commit/Diff 一律经 Analysis（同进程）获取，Analysis 是代码理解唯一出口，上下文管控不可绕过；
2. **Rejected Finding 不进知识提炼、不纳入统计**（硬约束）；
3. **Finding 必须携带行号锚点**：`repository + commit_sha + file_path + side(OLD/NEW) + line_start + line_end`，缺失锚点不得发布；
4. **异步任务统一走 PostgreSQL 任务表 + Scheduler**，不引入消息队列；
5. **Review → Knowledge 为异步单向产出关系**：Review 不等待 Knowledge 结果，Knowledge 挂不影响 Review。
6. **hardRules 与 suggestedChecks 分离**：项目既定规范（hardRules）LLM 必须遵守不可忽略；AI 建议（suggestedChecks）可调整；
7. **Status 与 Source 解耦**：统计按 Status（OPEN/CONFIRMED 计入，REJECTED 排除），Source 仅溯源。

## 设计细则（P1 必检项）

- 是否跳过 LLM：由 Dynamic Review Plan 动态判断（文档类通常跳过；配置/构建/Docker/CI 按敏感性与影响面决定 Security/Infrastructure Review），**不按扩展名一刀切**；
- 多轮扩展受 max_llm_calls / max_context_tokens / max_impact_depth 限制，超限标 PARTIAL_REVIEW；
- 统计口径：OPEN + CONFIRMED 计入，REJECTED 排除；
- **知识演进链路：finding → candidate → knowledge**（knowledge_candidate 中间表，finding_id / candidate_id / knowledge_id 关联溯源，见模块 06）；
- Merge Commit 不进行增量 Review（Analysis 标记 MERGE_COMMIT，仅记录合并信息）；
- LLM 输出经 Structured Output 校验，非法输出自纠重试（计入调用次数）；
- 并发状态更新用乐观锁（version 字段）。

## 19. 实现优先级

### 【毕设必须】

- Commit Review 触发（异步任务 + 进度）
- Dynamic Review Plan（简化版：规则关键词 + LLM 细化）
- LLM Review（单轮为主，Structured Output）
- Finding 生成、展示、筛选（类型/严重度/状态/文件）
- Finding 状态管理（Confirm / Reject / Ignore）
- 静态 Finding 合并展示（source=STATIC_ANALYSIS）
- Rejected 排除知识提炼（硬约束）
- 知识提炼交接（异步任务）

### 【建议实现】

- 多轮受控扩展（默认 1 轮，超限标 PARTIAL）
- 整体评估报告（LLM 总结）
- 趋势统计（为健康度做准备）

### 【可选扩展】

- Finding 手动重新提炼
- 评论式反馈（用户对 Finding 留言）

### 【暂不实现】

- PR Review / 回写 GitHub
- Webhook 自动 Review（V2）
- 评分聚合（Project Health C 方案，V2）

---

## 20. 后续扩展

1. **自动 Review**（V2）：GitHub Webhook 新 Commit → 自动触发（用户可配置）；
2. **评估评分聚合**（V2）：assessment_scores 计算与趋势展示；
3. **Review 对比**：同文件多次 Commit 的 Finding 演变；
4. **多 Agent 分工**（V2 后评估）：Bug / Security / Architecture 分路审查后合并。

---

## 21. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| 审查单位 | Commit 为最核心审查单位，只审变更影响范围 | 已定 |
| 审查模板 | 动态计划，不固定模板 | 已定 |
| 多轮推理 | 允许但受 max_llm_calls / Token / 深度限制，超限标 PARTIAL | 已定 |
| Finding 状态 | Status：OPEN → CONFIRMED / REJECTED / IGNORED；Source（LLM/STATIC_ANALYSIS/RULE）独立溯源 | 已定 |
| ReviewPlan | hardRules（必须遵守）与 suggestedChecks（AI 建议）分离 | 已定 |
| 知识链路 | finding → knowledge_candidate → knowledge，三表关联溯源 | 已定 |
| Rejected 处理 | 不进知识提炼、不纳入统计（硬约束） | 已定 |
| Knowledge 交接 | 异步单向产出关系，Knowledge 挂不影响 Review | 已定 |
| 整体评估 | Analysis 聚合指标 + Review AI 总结；评分聚合 C 方案 V2 | 已定 |
