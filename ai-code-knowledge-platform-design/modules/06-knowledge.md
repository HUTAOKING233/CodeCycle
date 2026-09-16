# 06 Knowledge Service（知识资产与提炼）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写
> 部署：与 RAG Service 合并为部署单元 ⑤ Knowledge/RAG（逻辑上独立）

---

## 1. 模块职责

Knowledge Service 管理平台的**核心资产——工程知识**：

- 两类知识空间：Project Knowledge（项目共享）与 Personal Knowledge（个人专属）；
- 知识生命周期：提炼 → 候选 → 风险分级 → 自动发布 / 用户审核 → Published → 版本/失效；
- 知识类型（7 种）与结构化内容管理；
- 知识关系、引用（Reference）与复制（Copy）；
- 知识导入导出（Markdown / JSON / YAML，不上传 GitHub）；
- 为 RAG / MCP 提供元数据与权限过滤基础。

核心原则：

> **Review Finding ≠ Knowledge。** 知识是抽象后的可复用资产；RAG 只存知识，不存大量源码；模型输出不能直接成为 Published 知识。

---

## 2. 为什么存在

- 项目区别于普通 AI Code Review 的核心：让工程经验"沉淀"并"反哺"后续开发；
- 知识有生命周期、权限、版本、关系，不能是一堆散落的向量文本；
- 知识是独立资产：Review 挂掉、MCP 挂掉都不影响已有知识（解耦基础）。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| 知识 CRUD、生命周期、版本、关系、引用 | Embedding 与语义检索（RAG） |
| 知识提炼（Review → Candidate） | LLM Review 与 Finding（Review） |
| 风险分级与发布策略 | GitHub 数据获取（GitHub Integration） |
| 知识导出/导入 | MCP 协议适配（MCP） |
| MCP Profile 定义（知识范围） | MCP Token 签发（Auth） |

---

## 4. 知识空间模型

```text
Knowledge
 ├── Project Knowledge（scope=PROJECT）
 │     ├── project_id 非空
 │     └── 项目成员共享（按 project_members 角色控制）
 └── Personal Knowledge（scope=PERSONAL）
       ├── owner_user_id 非空
       └── 仅用户本人可用
```

### 4.1 Reference（引用，推荐）

个人知识可**引用**多个项目知识：

```text
Personal Knowledge
 ├── Reference → Project A / Knowledge X（不复制正文与 embedding）
 ├── Reference → Project B / Knowledge Y
 └── Own        → Personal Z
```

- 引用不复制内容、不重复 embedding；
- 原知识更新后引用自动同步；
- 用户可随时取消引用；
- 查询个人范围时：本人知识 + 被引用知识。
- **硬约束：Reference 不复制权限**——项目权限失效（移出成员 / 项目归档 / 知识失效）后，个人引用同步失效，检索时排除；需要永久保留的项目知识，使用 Copy/Fork 脱离原项目（见项目定义 §5.2）。

### 4.2 Copy（复制）

```text
Project A / Knowledge X → Copy → Project B / Knowledge X'
```

- 生成**新的独立知识资产**（新 id、project_id、版本号从 1 开始）；
- 重新生成 embedding；
- 复制后两个项目互不影响。

---

## 5. 知识类型（7 种）

| 类型 | 中文说明 | 典型来源 | 结构化字段（payload） |
| --- | --- | --- | --- |
| BUG_AVOIDANCE | 通用规避策略（Bug 提炼） | Review Bug | cause / pattern / avoidance / applicable |
| PERFORMANCE_STRATEGY | 通用性能策略（Performance 提炼） | Review Performance | cause / pattern / strategy / applicable |
| SECURITY_RULE | 安全规则（全量提炼） | Review Security | rule / constraint / scope |
| ARCHITECTURE_RULE | 架构规则（全量提炼，含 Architecture Decision 记录） | Review Architecture / 用户 | rule / constraint / scope / decision |
| METRIC_RULE | 度量阈值（Code Smell / Design 提炼） | Review Smell/Design / 用户 | rule / metric / threshold / condition / scope |
| GENERATION_TEMPLATE | 生成模板（Best Practice 提炼，约束"代码长什么样"） | Review Best Practice | template / constraints / validation / applicable |
| SKILL | 开发流程/步骤（可复用开发步骤，供 Agent 按流程执行） | 重复性流程 / 用户上传 | goal / prerequisites / steps / checklist / validation |

> 折中方案：Architecture Decision 不单独建类型，并入 ARCHITECTURE_RULE（decision 字段）；SKILL 与 GENERATION_TEMPLATE 分工——模板描述产物形态，SKILL 描述过程步骤。

> P0-6（已定）：payload 字段**按具体类型专用定义**，不使用跨类型公共字段集合（如 threshold 仅 METRIC_RULE 使用，SECURITY/ARCHITECTURE 无 threshold）。

### 5.1 知识类型分族（规则型 / 经验型 / 生成型）

```text
规则型（Rules）    ：SECURITY_RULE / ARCHITECTURE_RULE / METRIC_RULE
  → 提炼为确定性规则/阈值；检索命中后 Agent 必须遵守
经验型（Experience）：BUG_AVOIDANCE / PERFORMANCE_STRATEGY
  → 提炼为规避策略/经验；供 Agent 参考决策
生成型（Generation）：GENERATION_TEMPLATE / SKILL
  → 提炼为可执行模板/步骤；供 Agent 生成代码时遵循
```

| 知识族 | 类型 | 提炼策略 | 字段说明 |
| --- | --- | --- | --- |
| 规则型 | SECURITY / ARCHITECTURE / METRIC | 规则 → 约束 | SECURITY=rule/constraint/scope；ARCHITECTURE 另含 decision；METRIC 另含 metric/threshold/condition |
| 经验型 | BUG_AVOIDANCE / PERFORMANCE | 经验 → 因果 + 策略 | cause/pattern/avoidance 或 strategy/applicable（按类型专用） |
| 生成型 | GENERATION_TEMPLATE / SKILL | 生成 → 步骤 + 模板 + 验证 | 按类型专用（见 §5 表），不使用公共字段集合 |

- 三族对应不同提炼策略与检索优先级（规则型最高）；
- 发布策略天然对齐：**规则型一律人工审核**；经验型（低风险）与 GENERATION_TEMPLATE 可自动发布；**SKILL 默认人工审核**（见 §7）。

每条知识统一包含：

```text
title / summary / content（面向 Agent 的精炼文本）/ payload（类型化结构）/
source（Project/Commit/File/Symbol/少量 Code Fragment）/ embedding（RAG 域）
```

---

## 6. 知识提炼

### 6.1 提炼规则（按 Review 类型映射）

| Review 类型 | 提炼结果 | 发布方式 |
| --- | --- | --- |
| Bug | BUG_AVOIDANCE（通用规避策略） | 低风险自动（SUPPORTED）/ 高风险人工 |
| Performance | PERFORMANCE_STRATEGY（通用策略） | 低风险自动（SUPPORTED）/ 高风险人工 |
| Security | SECURITY_RULE（全量提炼） | 一律人工审核（PENDING_REVIEW） |
| Architecture | ARCHITECTURE_RULE（全量提炼） | 一律人工审核（PENDING_REVIEW） |
| Code Smell | METRIC_RULE（度量阈值） | 一律人工审核（PENDING_REVIEW） |
| Design | METRIC_RULE（度量阈值） | 一律人工审核（PENDING_REVIEW） |
| Best Practice | GENERATION_TEMPLATE（生成模板） | 低风险自动（SUPPORTED）/ 高风险人工 |
| 重复性开发流程 | SKILL（开发流程步骤） | 默认人工审核（影响 Agent 行为） |
| 具体 Bug 补丁 | **不提炼** | — |
| 单次 Commit 修改内容 | **不提炼** | — |

### 6.2 知识蒸馏流水线（四阶段，核心）

**计算引擎（V1 定案）**：蒸馏全链路 Java 实现（JavaParser 解析 + Spring AI 调 LLM + pgvector），**不引入 Python / FastAPI / Tree-sitter 服务**；未来转型 C/S 或本地化项目时再评估直接引入成熟开源引擎（见模块 04 §6.1.2）。

```text
阶段一 · Evidence Preparation（限界上下文）
  Review Finding（CONFIRMED / OPEN，Rejected 排除）
  → 只读取 Finding 相关的有限证据，**不扫描整个代码库**（五层 Evidence，见下表）
  → 输出 EvidencePackage（结构化为 LLM 输入）
  Review 与蒸馏解耦：独立异步任务，不占用 Review 调用

阶段二 · Candidate Extraction（一次 LLM 调用）
  → Classification（问题类型）→ Extraction Policy（按类型/分族映射知识形态）
  → LLM 基于 EvidencePackage 抽象提炼（去项目特有命名 / 不输出补丁）→ Knowledge Candidate（JSON）
  → 落库 knowledge_candidate（记录 finding_id + 证据摘要）
  → **立即向量化（一步到位）**：写入独立候选向量集合 candidate_vectors（供阶段三相似召回）

阶段三 · Consolidation（归并 / 抽象 / 版本演化，一次 LLM 调用）
  → pgvector 相似候选召回（Candidate + Published，TopK）
  → LLM 归并判断（仅四种决策）：
      NEW       → 无已有知识覆盖，创建新 Knowledge
      MERGE     → 多个 Candidate 同一主题，合并
      REVISION  → 新证据修正已有知识，版本演化（旧版 SUPERSEDED）
      DUPLICATE → 已有相同知识，仅追加证据，不发布
  → 输出 Consolidated Candidate（进入发布控制）

阶段四 · Publication（风险控制 + 审核）
  → Risk / Confidence 评估（evidence_count / commit_count / module_count / confirmation_rate）
  → 规则型 / SKILL → PENDING_REVIEW 人工审核；经验型（低风险，conf≥0.8）与 TEMPLATE → Auto Publish
  → Published Knowledge（写 knowledge 表，记录 candidate_id；入队 Embedding 到 knowledge_vectors）
```

**五层 Evidence 模型（EvidencePackage）**：

| 层级 | 内容 | 是否必取 | 说明 |
| :---: | --- | :---: | --- |
| P0 | Finding（标题/类型/严重度/描述/行号锚点） | 必取 | 蒸馏的起点 |
| P1 | Diff（changed files / lines / patch） | 必取 | 变更直接证据 |
| P2 | Local Context（Method/Class/Field/Annotation） | 必取 | 当前符号局部范围 |
| P3 | Impact Context（Caller/Callee/Dependency/Config） | 按需 | 沿 Code Graph 扩展 |
| P4 | Project Context（Profile/ArchRules/TechStack） | 按需 | 项目级固定信息 |

**硬预算（Q3 定案，可配置）**：

| 维度 | 预算 |
| :--- | :---: |
| 最大文件数 | 10 |
| 最大代码片段数 | 20 |
| 最大 Graph 节点 | 50 |
| 最大扩展深度 | 2 |
| 最大上下文 Token | 12,000 |

- 预算为**硬上限**，超过时按 P0 > P1 > P2 > P3 > P4 截断（不交给 LLM 自行扩展）；
- **确定性 Token 压缩（非 LLM 摘要）**：Graph Filtering（只留相关符号）→ Symbol Selection（去重/裁剪边界）→ AST/Code 压缩（删注释/空白/无关方法体）；可解释、可复现；
- 按知识类型扩展：涉及状态流转 + Caller/Callee；涉及 DB/RPC + 对应依赖；涉及认证/权限 + Security Config；生成型 + 成功实现参考 / 多 Finding 操作序列。

**演进链路（硬约束）**：`finding → candidate → consolidation → knowledge`；数据库保留 `finding_id / candidate_id / knowledge_id` 关联，支持知识全程溯源。

**Candidate 与 Published 分库（硬约束）**：未经验证/归并的知识只存在于 `knowledge_candidate`（独立向量集合 `candidate_vectors`），**不进入 RAG/MCP 检索路径**（Published 集合 `knowledge_vectors`）。

### 6.3 去重与归并（两层，防知识库膨胀）

```text
第一层 · pgvector 相似召回：同项目 + 同类型/分族 + 相似度阈值 → 候选集
第二层 · LLM Consolidation：语义归并判断
  ├── 同主题 → 合并（更新计数与证据，不新建）
  ├── 部分重叠 → 抽象提升（生成更高层规则/策略）
  └── 新主题 → 保持独立
计数聚合：occurrences / evidence_count / commit_count / module_count
```

知识记录：

```text
出现次数（occurrences）、首次/最近发现 Commit、相关项目数、
证据计数（evidence_count）、涉及 Commit 数（commit_count）、涉及模块数（module_count）
```

**原则：10 次相同的 Commit 错误 → 1 条知识 + 计数，而不是 10 条知识。**

### 6.4 Candidate 粒度（开放问题定案）

```text
Candidate 粒度 = Finding 粒度（最小生成单元）
  → 每个已确认 Finding 独立生成 Candidate（证据上下文有限、可溯源）
Published 粒度 = 知识主题粒度
  → Consolidation 阶段按"规则意图/主题"聚类归并：
     一条 Published 知识表达一个可复用规则/策略/模板
理由：MCP 消费按主题检索，Agent 需要"一条知识 = 一个决策点"；
     避免 PR 粒度过粗（一条知识混杂多主题）、规则粒度过碎（检索碎片化）
```

### 6.5 动态置信度

```text
confidence = f(evidence_count, commit_count, module_count, confirmation_rate)
  - 证据越充分（多 Commit、多模块、多 Finding 一致）→ 置信度越高
  - 程序计算，非 LLM 主观判断（LLM 的 extraction_confidence 仅作参考）
```

**发布门槛（P0-4 最终统一）**：

| 知识类型 | 风险定性 | 发布方式 |
| --- | --- | --- |
| SECURITY_RULE / ARCHITECTURE_RULE / METRIC_RULE | 一律视为高风险 | PENDING_REVIEW 人工审核，不自动发布 |
| BUG_AVOIDANCE / PERFORMANCE_STRATEGY | 低风险 | 自动发布（需 SUPPORTED 且 confidence ≥ 0.8）；高风险人工 |
| GENERATION_TEMPLATE | 低风险 | 自动发布（需 SUPPORTED） |
| SKILL | 默认高风险（影响 Agent 行为） | PENDING_REVIEW 人工审核 |
| 任何类型 | RISK_CRITICAL | 强制人工审核 + 风险标注 |

- **SUPPORTED 证据门槛（默认，可配置）**：≥ 3 个独立 Finding、≥ 2 个 Module、confidence ≥ 0.8；未达标候选停留在 CANDIDATE 持续累积证据，不进入发布路径；
- **人工审核入口不依赖 SUPPORTED（v3.5 定案）**：任何候选（含单 Finding、未达证据门槛）均可由用户从 Web 转入人工审核，审核通过可直接发布（风险由人承担）；自动发布路径仍要求 SUPPORTED；
- 风险标注（risk_level：LOW / HIGH / CRITICAL）由规则判定（类型 + 敏感操作/权限/资金/核心链路）；
- 规则型与 SKILL 即使满足自动条件也不自动发布（人工审核兜底）。

---

## 7. 发布策略（风险分级）

### 7.1 风险级别与发布路径（P0-4 最终统一）

```text
RISK_LOW      → Auto Publish（仅 BUG/PERFORMANCE 低风险且 conf≥0.8、GENERATION_TEMPLATE 低风险）
RISK_HIGH     → PENDING_REVIEW（用户审核后发布/拒绝）
RISK_CRITICAL → 强制人工审核 + 风险标注（Web 展示，不进入 MCP 默认检索）
```

| 知识类型 | 风险定性 | 发布路径 |
| --- | --- | --- |
| SECURITY_RULE / ARCHITECTURE_RULE / METRIC_RULE | 一律视为高风险 | PENDING_REVIEW 人工审核（不自动发布） |
| BUG_AVOIDANCE / PERFORMANCE_STRATEGY | 低风险 | 自动发布（SUPPORTED 且 confidence ≥ 0.8）；高风险人工 |
| GENERATION_TEMPLATE | 低风险 | 自动发布（SUPPORTED） |
| SKILL | 默认高风险（影响 Agent 行为） | PENDING_REVIEW 人工审核 |
| 任何类型 | RISK_CRITICAL | 强制人工审核 + 风险标注 |

### 风险标注

- 每条知识在提炼时由规则判定 risk_level（类型 + 关键词/规则命中 + 涉及依赖），供前端与发布控制展示；
- 待人工审核知识（规则型 / SKILL / 高风险）Web 可见（带"待审核"与风险标签），MCP 不可检索。

### 7.2 状态机

```text
OBSERVED      → Finding 已确认，尚未进入蒸馏（Review 域状态）
    ↓（入队蒸馏）
CANDIDATE     → Extraction 完成，存入 candidate_vectors
    ↓（Consolidation）
SUPPORTED     → 满足证据门槛（默认 ≥3 独立 Finding、≥2 Module、conf≥0.8，可配置）→ 进入发布控制
    ├─ 经验型（低风险）与 TEMPLATE → Auto Publish
    └─ 规则型 / SKILL / 高风险 → PENDING_REVIEW（用户审核）
             ├→ PUBLISHED（进入 knowledge_vectors，RAG/MCP 可用）
             └→ REJECTED（不发布）
PUBLISHED → SUPERSEDED（被新版本替代，版本演化）
PUBLISHED → OUTDATED（来源变更检测出可能失效，待复核 → 新版本或 ARCHIVED）
```

- 用户可见**每一条知识**（含 Candidate），可查看提炼来源；
- 模型输出永远只是 Candidate，不能直接成为 Published（硬约束）；
- Candidate 状态子集：**CANDIDATE / SUPPORTED / MERGED / REJECTED（不含 PUBLISHED）**；Published Topic 状态（PUBLISHED / SUPERSEDED / OUTDATED）独立存在于 knowledge.status（见 §8）。

---

## 8. 数据模型（字段级）

### knowledge

```text
id                BIGINT PK
candidate_id      BIGINT FK NULL      -- 来源候选（knowledge_candidate.id）
project_id        BIGINT FK NULL      -- scope=PROJECT 时非空
owner_user_id     BIGINT FK NULL      -- scope=PERSONAL 时非空
scope             VARCHAR(20)         -- PROJECT / PERSONAL
type              VARCHAR(30)         -- 7 种类型之一
title             VARCHAR(200)
summary           VARCHAR(500)
content           TEXT                -- 面向 Agent 的精炼文本（入库前已压缩）
payload           JSONB               -- 类型化结构化字段（见 §5）
status            VARCHAR(30)         -- PENDING_REVIEW / PUBLISHED / REJECTED / OUTDATED / SUPERSEDED / ARCHIVED（候选态在 knowledge_candidate）
risk_level        VARCHAR(10)         -- LOW / HIGH / CRITICAL
confidence        NUMERIC(4,3) NULL
version           INT DEFAULT 1
row_version       BIGINT NOT NULL DEFAULT 0  -- 乐观锁（version 为业务版本号，配合 knowledge_versions）
occurrences       INT DEFAULT 1
first_seen_commit VARCHAR(64) NULL
last_seen_commit  VARCHAR(64) NULL
related_projects  JSONB               -- 相关项目 id 列表
source            JSONB               -- {project, commit, file, symbol, codeFragment}
embedding_status  VARCHAR(20)         -- PENDING / COMPLETED / FAILED / OUTDATED / REBUILDING
source_version    INT DEFAULT 1       -- 来源 Commit 版本（禁止直接覆盖更新）
created_by        BIGINT FK
created_at / updated_at / published_at
INDEX (project_id, scope, type, status)
```

### knowledge_candidate（中间表）

```text
id                     BIGINT PK
finding_id             BIGINT FK             -- 来源 Finding（Review 域）
knowledge_id           BIGINT FK NULL        -- Consolidation/Publication 后回填：该 Candidate 最终归属的 Published Topic
project_id             BIGINT FK
type                   VARCHAR(30)
title                  VARCHAR(200)
content                TEXT                  -- 提炼后的精炼文本
payload                JSONB                 -- 分族字段（§5.1）
stage                  VARCHAR(20)           -- CANDIDATE（单 Finding 产生）/ SUPPORTED（证据门槛：≥3 Finding、≥2 Module、conf≥0.8）/ MERGED（并入其他候选）/ REJECTED（不采纳）
consolidation_decision VARCHAR(20) NULL      -- NEW / MERGE / REVISION / DUPLICATE（阶段三决策）
risk_level             VARCHAR(10)           -- LOW / HIGH / CRITICAL
confidence             NUMERIC(4,3) NULL
evidence_count         INT DEFAULT 1         -- 证据账本：支撑 Finding/片段数
commit_count           INT DEFAULT 1         -- 涉及 Commit 数
module_count           INT DEFAULT 1         -- 涉及模块数
confirmation_rate      NUMERIC(4,3) NULL     -- Finding 确认率（动态置信度输入）
recall                 JSONB                 -- 阶段三相似召回命中摘要（similarity / 命中 id）
created_at / updated_at
INDEX (finding_id), INDEX (knowledge_id)
```

### knowledge_tasks（知识域任务表）

```text
id              BIGINT PK
project_id      BIGINT FK
repository_id   BIGINT FK NULL
branch_id       BIGINT FK NULL
type            VARCHAR(30)   -- DISTILLATION（蒸馏）/ EMBEDDING / REINDEX
ref             VARCHAR(128)  -- finding_id / knowledge_id / 批号
target_ref      VARCHAR(128) NULL  -- 滚动到最新目标（蒸馏按分支合并时用）
status          VARCHAR(20)   -- QUEUED / RUNNING / COMPLETED / FAILED / FAILED_RETRYABLE / CANCELLED（COMPLETED/FAILED/CANCELLED 为终态）
next_run_at     TIMESTAMP NULL
retries         INT
result_ref      VARCHAR(255) NULL   -- 结果位置（knowledge_id / candidate_id）
error           TEXT NULL
created_at / started_at / finished_at / updated_at

-- 字段约定与调度器接口与 analysis_tasks 完全一致（见模块 04 §11）；域内事务不跨域
UNIQUE (project_id, repository_id, branch_id, type) WHERE status = 'QUEUED'
```

### knowledge_versions

```text
id              BIGINT PK
knowledge_id    BIGINT FK
version         INT
content_hash    CHAR(64)
content         TEXT
payload         JSONB
reason          VARCHAR(500)          -- 版本变更原因（如架构变化）
superseded_by   BIGINT NULL           -- 被哪个版本替代
created_at      TIMESTAMP
```

### knowledge_relations

```text
id                   BIGINT PK
source_knowledge_id  BIGINT FK
target_knowledge_id  BIGINT FK
relation_type        VARCHAR(20)      -- RELATED_TO / DERIVED_FROM / SUPERSEDES / APPLIES_TO
created_at           TIMESTAMP
```

### knowledge_references

```text
id           BIGINT PK
user_id      BIGINT FK
knowledge_id BIGINT FK
created_at   TIMESTAMP
UNIQUE (user_id, knowledge_id)
```

### mcp_profiles（知识范围定义）

```text
id               BIGINT PK
user_id          BIGINT FK
name             VARCHAR(100)
project_ids      JSONB               -- 选择的 Project Knowledge
include_personal BOOLEAN DEFAULT false   -- v3.5：默认不混合个人知识，显式开启
description      VARCHAR(500) NULL
created_at / updated_at
```

> MCP Token 在 Auth 域（模块 01），Profile 在 Knowledge 域，通过 `profile_id` 关联。

---

## 9. 知识关系与版本

### 9.1 关系类型

```text
RELATED_TO    A 与 B 相关（如 N+1 Query ↔ 批量查询）
DERIVED_FROM  知识源自 Commit/Review 或另一条知识
SUPERSEDES    新版本替代旧版本
APPLIES_TO    知识适用于某项目/模块/技术栈
```

- 关系存 PostgreSQL 关系表，**不引入图数据库**；
- V1 关系由提炼时自动建立（derived_from）+ 用户手动补充；
- RAG 检索命中一条知识时，可沿 RELATED_TO 扩展候选（后续增强）。

### 9.2 版本与失效检测（Knowledge Validation）

```text
Commit 重大变化（架构/技术栈变更，Analysis 影响分析发现）
 → 匹配项目知识
 → 检测旧知识可能失效 → 标记 OUTDATED（Potentially Outdated）
 → 生成修改建议（LLM）
 → 用户确认 → 创建新版本（version+1，supersedes 旧版本）
```

- **版本硬约束**：知识记录 `source_version`（来源 Commit 版本）；来源变化后旧知识标记 OUTDATED，新知识 supersedes 旧版本，**禁止直接 UPDATE 覆盖**；
- 旧版本保留可回溯（knowledge_versions），不物理删除；
- 失效知识不再进入 RAG 检索默认结果（可配置是否包含 OUTDATED）。

---

## 10. 导入导出

```text
Export（Markdown / JSON / YAML）
knowledge/
 ├── rules/
 ├── skills/
 ├── patterns/
 ├── mistakes/
 └── decisions/
```

- 导出内容：title / type / content / payload / source 摘要（**不含 embedding 与内部元数据**）；
- 导出供用户自托管共享（GitHub 仓库、团队导入）；
- Import：按类型重建知识，**重新生成 embedding**；
- **平台不自动上传 GitHub、不自动 Push**（用户自行托管）。

---

## 11. 接口设计

| 方法/路径 | 说明 | 鉴权 |
| --- | --- | --- |
| `GET /api/knowledge?projectId=&scope=&type=&status=&page=` | 知识列表（含待审核） | 项目成员 / 本人 |
| `GET /api/knowledge/{id}` | 知识详情（含来源与关系） | 项目成员 / 本人 |
| `POST /api/knowledge` | 手动创建/上传知识（用户知识） | 本人 / 项目 MEMBER+ |
| `PATCH /api/knowledge/{id}` | 编辑（状态允许时） | 本人 / 项目 MEMBER+ |
| `DELETE /api/knowledge/{id}` | 归档（软删除） | 本人 / 项目 MEMBER+ |
| `POST /api/knowledge/{id}/review` | 审核：通过/拒绝（高风险知识） | 本人 / 项目 MEMBER+ |
| `POST /api/knowledge/{id}/reference` | 个人引用（不复制） | 本人 |
| `DELETE /api/knowledge/{id}/reference` | 取消引用 | 本人 |
| `POST /api/knowledge/{id}/copy?targetProjectId=` | 复制到目标项目（新资产） | 目标项目 MEMBER+ |
| `POST /api/knowledge/{id}/version` | 生成新版本（supersedes） | 项目 MEMBER+ |
| `GET /api/knowledge/{id}/relations` | 关系查询 | 项目成员 / 本人 |
| `POST /api/knowledge/{id}/relations` | 手动建立关系 | MEMBER+ |
| `POST /api/knowledge/export?ids=&format=` | 导出（md/json/yaml） | 项目成员 / 本人 |
| `POST /api/knowledge/import` | 导入（重建 + 重新 embedding） | 本人 / 项目 MEMBER+ |
| `GET /api/knowledge/pending-review` | 待审核列表 | 本人 / 项目 MEMBER+ |
| `POST /internal/knowledge/candidates` | Review 交接知识提炼（入队） | 内部服务令牌 |
| `GET /internal/knowledge/scoped-ids?profileId=` | MCP Profile 对应的知识 id 集合（RAG 过滤用） | 内部服务令牌 |

---

## 12. 与其他服务关系

```text
Review ──→ Knowledge（异步交接 Candidate，单向产出关系）
RAG ──→ Knowledge（同部署单元：元数据/权限过滤、检索结果回填）
MCP ──→ Knowledge（经 RAG 检索；Profile 解析知识范围）
Analysis ──→ Knowledge（初始化阶段提交初始知识候选）
Project ──→ Knowledge（项目知识空间上下文；角色校验走 /internal/project）
```

解耦验证：

```text
Review 挂 → Knowledge 已有资产仍可查询、MCP 仍可用
RAG 挂   → 知识管理（CRUD/审核）正常，检索不可用
MCP 挂   → Knowledge 完全正常
```

---

## 13. 异常处理

| 场景 | 处理 |
| --- | --- |
| 提炼 LLM 输出非法 | 结构化校验重试（计入调用次数），失败转 PENDING_REVIEW 人工处理 |
| Embedding 失败 | embedding_status=FAILED，重试；知识本身可查看与导出，仅不可检索 |
| 去重判定模糊 | 生成"重复候选"提示用户，不自动合并 |
| 引用目标被删除/归档 | 引用保留但标记"源已归档"，检索时排除或降权 |
| 并发审核冲突 | 乐观锁（row_version 字段；version 为业务版本号） |
| 导出/导入格式错误 | 返回逐条错误行，部分成功可继续 |

---

## 14. 异步流程

```text
Commit 事件 → Analysis/Review（生产链）→ Confirmed Finding
 → 蒸馏事件发布（knowledge_tasks，按分支合并 + 时间窗口，见模块 04 §15）
 → Scheduler 订阅 → Evidence Preparation → Extraction（+ 候选向量化）
 → Consolidation → 发布控制 → 写入 knowledge → 入队 Embedding
 → 完成（embedding_status=COMPLETED）
```

- 提炼与 Embedding 均为异步；
- **任务合并与并发**：蒸馏任务与 Commit 分析共用同一调度语义——按分支合并 + 时间窗口（默认 90s）+ 分支级串行 + 全局并发上限（默认 2，可独立配置 `distill.concurrency-limit`）；RUNNING 期间到达的新 Finding 记录 pending 引用，完成后接力处理；
- 任务机制：知识域任务统一落 **knowledge_tasks**（DISTILLATION / EMBEDDING / REINDEX），与分析域 analysis_tasks 共享同一调度器接口与字段约定（见项目定义 §14.4 与模块 04 §15.1/15.2）。

---

## 15. AI 调用点

1. **知识提炼**（Review → 抽象知识，Structured Output）；
2. **去重/合并判断**（可选，规则优先）；
3. **失效检测建议**（重大变化 → 修改建议）；
4. **SKILL 生成**（重复性流程 → 结构化步骤）。

约束：

- 全部走 Spring AI；
- 模型输出一律为 Candidate / 建议，**不能直接标记 Published**（硬约束）；
- 提炼文本在入库前完成**第一层压缩**（防 Token 膨胀，配合 RAG 第二层压缩）。

---

## 16. 权限要求

- Project 知识：项目成员查看，MEMBER+ 管理（确认/编辑/导出），VIEWER 只读；
- Personal 知识：仅本人（管理、引用、导出）；
- 跨项目：只能通过 Copy（新资产）或导出/导入，不能直接读取他项目知识；
- `/internal/knowledge/**` 仅服务网络可达。

---

## 17. 技术实现建议

- ORM：MyBatis-Flex（knowledge schema）；
- 类型化内容：`content`（精炼文本）+ `payload`（JSONB 结构化字段），避免 7 类各自建表；
- 去重：embedding 相似度（RAG 域） + 标题/同类型规则 双通道；
- 版本：`knowledge_versions` 全量版本记录（内容量小，不做 diff）；
- 导出：模板渲染 Markdown / JSON / YAML；
- 乐观锁：审核与编辑用 row_version 字段（version 为业务版本号，配合 knowledge_versions）。
- **计算引擎全 Java**：JavaParser（AST/符号/关系）+ Spring AI（LLM Structured Output）+ pgvector（候选/知识向量）；不引入 Python / FastAPI / Tree-sitter；
- **蒸馏两次 LLM 调用**：Extraction + Consolidation，各计入任务 LLM 调用预算；规则预过滤可降低调用量；
- **候选向量化一步到位**：Extraction 完成即写 candidate_vectors，Consolidation 相似召回直接可用，不延迟向量化。

---

## 架构约束（P0）

1. **Reference 不复制权限**：项目权限失效后个人引用同步失效，检索排除；永久保留用 Copy/Fork；
2. **版本禁止覆盖**：知识记录 `source_version`，来源变化后旧知识 OUTDATED、新知识 supersedes，禁止直接 UPDATE 覆盖；
3. **模型输出只是 Candidate**：不能直接标记 Published，必须经 Evidence Preparation → Extraction → Consolidation → 风险分级 → 发布流程；
4. **Rejected Finding 不进提炼**：Review 交接时已排除，Knowledge 域不得绕过；
5. **异步任务统一走 PostgreSQL 任务表 + Scheduler**，不引入消息队列。
6. **知识演进链路**：`finding → candidate → consolidation → knowledge`，中间表 `knowledge_candidate` 保留 finding_id / knowledge_id 关联，禁止跳过候选直接入库；
7. **蒸馏不扫描整个代码库**：Evidence Preparation 只读取 Finding 相关有限证据（五层 P0~P4 + 硬预算：文件≤10 / 片段≤20 / 节点≤50 / 深度≤2 / Token≤12000），全库蒸馏禁止；
8. **Candidate 与 Published 分库/分向量集合**：未验证知识不进入 RAG/MCP 检索路径；
9. **规则型与 SKILL 一律人工审核**：SECURITY/ARCHITECTURE/METRIC 与 SKILL 不自动发布，进入 PENDING_REVIEW 由用户审核，并标注风险等级。

## 设计细则（P1 必检项）

- Embedding 生命周期：PENDING → COMPLETED / FAILED / OUTDATED / REBUILDING；知识已发布但 Embedding 未完成时 **Web 可见、MCP 暂不可检索**（见模块 07）；
- 去重归并：pgvector 相似召回（Candidate + Published）+ LLM Consolidation 双层归并，计数聚合（occurrences / evidence_count / commit_count / module_count）；
- Candidate 粒度：Finding 粒度生成，Published 按知识主题粒度（一条知识 = 一个可复用规则/策略/模板）；
- 动态置信度：confidence = f(evidence_count, commit_count, module_count, Finding 确认率)；
- 知识分族：规则型 / 经验型 / 生成型，分别对应约束、因果、步骤三种提炼策略与检索优先级；
- 提炼文本入库前完成第一层压缩（防 Token 膨胀），原始 Review 全文不入库；
- 关系（RELATED_TO / DERIVED_FROM / SUPERSEDES / APPLIES_TO）存 PostgreSQL 表，不引入图数据库；
- 计算引擎全 Java（JavaParser + Spring AI），不引入 Python/Tree-sitter/FastAPI；未来本地化再评估开源引擎（模块 04 §6.1.2）；
- 五层 Evidence 硬预算（文件≤10 / 片段≤20 / 节点≤50 / 深度≤2 / Token≤12000），超限按 P0>P1>P2>P3>P4 截断；确定性 Token 压缩，不用 LLM 摘要；
- 两次 LLM 调用：Extraction → Candidate（立即向量化）+ Consolidation（NEW / MERGE / REVISION / DUPLICATE）；
- 发布门槛：规则型与 SKILL 一律人工（PENDING_REVIEW）；经验型低风险（conf≥0.8）与 TEMPLATE 低风险自动；SUPPORTED 证据门槛默认 ≥3 Finding、≥2 Module、conf≥0.8；
- Candidate 状态：CANDIDATE / SUPPORTED / MERGED / REJECTED，不含 PUBLISHED；归属 Topic 由 knowledge_candidate.knowledge_id 回填；
- 蒸馏任务与 Commit 分析共用合并/并发规则（时间窗口 + 分支级串行 + 全局上限，见模块 04 §15）。

## 18. 实现优先级

### 【毕设必须】

- 知识 CRUD（两类空间）
- 知识提炼（7 类映射 + 生命周期 + 风险分级发布）
- 去重合并基础（计数/首末 Commit/相关项目）
- Reference（个人引用，不复制 embedding）
- 来源回溯（Project/Commit/File/Symbol/少量片段）
- 待审核列表与审核操作
- 导出（Markdown/JSON/YAML）

### 【建议实现】

- Copy（跨项目复制）
- 版本与 supersedes
- 关系（RELATED_TO / DERIVED_FROM，含自动 derived_from）
- Import
- 失效检测（Potentially Outdated + 修改建议）

### 【可选扩展】

- 手动关系编辑
- 批量导出 / 模板化导出为 AGENTS.md/SKILL.md
- 知识统计（增长趋势、失效数）

### 【暂不实现】

- 上传 GitHub 自动同步
- 公共知识库 / 知识市场
- 多租户知识隔离（复杂 RBAC）

---

## 19. 后续扩展

1. **知识 → 静态分析闭环**：METRIC_RULE 度量阈值动态生效到 Checkstyle/PMD；
2. **Agent 可消费格式**：导出为 `.claude/`、AGENTS.md、SKILL.md（V2）；
3. **知识网络检索**：RAG 命中后沿 RELATED_TO 扩展（V2 评估）；
4. **开放平台**：知识授权共享（V3）。

---

## 20. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| 知识空间 | 仅 Project / Personal 两类；团队共享 = 项目知识 + 复制/导入导出 | 已定 |
| 知识类型 | 7 种（含 SKILL；ADR 并入 ARCHITECTURE_RULE） | 已定 |
| 提炼规则 | 按 Review 类型映射；具体补丁/单次修改不提炼 | 已定 |
| 发布策略 | 规则型与 SKILL 一律人工（PENDING_REVIEW）；经验型低风险（conf≥0.8）与 TEMPLATE 自动 | 已定 |
| 去重 | 合并计数，防知识库膨胀 | 已定 |
| 引用/复制 | Reference 不复制 embedding；Copy 新资产重新 embedding | 已定 |
| 版本 | 全量版本记录 + supersedes；重大变化触发失效检测 | 已定 |
| 导出 | Markdown/JSON/YAML，不上传 GitHub | 已定 |
| 知识链路 | finding → candidate → consolidation → knowledge，四阶段蒸馏，三表关联溯源 | 已定 |
| 蒸馏范围 | Evidence Preparation 五层限界证据（P0~P4，文件≤10 / 片段≤20 / 节点≤50 / 深度≤2 / Token≤12000），不扫描全库 | 已定 |
| 候选粒度 | Candidate=Finding 粒度；Published=知识主题粒度（一条知识=一个决策点） | 已定 |
| 计算引擎 | 全 Java（JavaParser + Spring AI），不引入 Python/Tree-sitter 服务 | 已定 |
| 蒸馏状态机 | 按附件：OBSERVED → CANDIDATE → SUPPORTED → PUBLISHED → SUPERSEDED（+ PENDING_REVIEW / OUTDATED 分支） | 已定 |
| 知识字段 | payload 按具体类型专用（P0-6），不使用公共字段集合 | 已定 |
| 发布门槛 | 规则型与 SKILL 一律人工；经验型低风险（conf≥0.8）与 TEMPLATE 自动 | 已定 |
| 候选状态 | CANDIDATE / SUPPORTED / MERGED / REJECTED（不含 PUBLISHED；Published 状态在 knowledge.status） | 已定 |
| 人工审核入口 | 任何候选（含单 Finding / 未达门槛）可由用户提前转人工审核并发布（风险由人承担）；自动发布仍需 SUPPORTED | 已定 |
| 候选向量化 | Extraction 完成立即向量化（一步到位），独立候选集合 | 已定 |
| 蒸馏任务并发 | 时间窗口合并 + 分支级串行 + 全局并发上限（任务表 + Scheduler，无 MQ） | 已定 |
| 任务表 | 每域一表：analysis_tasks（分析域）/ knowledge_tasks（蒸馏、Embedding、重建）；共享调度器接口与字段约定 | 已定 |
| 去重归并 | pgvector 相似召回 + LLM Consolidation 双层归并 | 已定 |
| 知识分族 | 规则型 / 经验型 / 生成型，对应不同提炼策略与检索优先级 | 已定 |
| 动态置信度 | confidence = f(evidence_count, commit_count, module_count, 确认率) | 已定 |
| 分库隔离 | Candidate 独立向量集合，不进入 RAG/MCP 检索路径 | 已定 |
