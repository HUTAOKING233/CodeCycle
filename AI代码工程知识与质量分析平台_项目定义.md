# CodeCycle 项目定义（v3.6 最终基线）

> 文档状态：**v3.6 最终基线**（2026-09-04，变更记录见 CHANGELOG.md）
> 已废弃术语清单见 [CHANGELOG.md](./CHANGELOG.md)；正文不出现任何已废弃术语。

---

# 1. 项目定位

**CodeCycle：基于 RAG 与 MCP 的开发者代码知识迭代平台**——开源、可自部署的 B/S 架构个人/小团队代码知识与增量审查平台。

平台通过 GitHub 只读获取 Repository / Commit / Diff 信息，在本地完成增量代码分析与 AI 审查，将筛选后的工程经验沉淀为 Project / Personal Knowledge，并通过 RAG 与 MCP 提供给 Claude Code 等 AI Agent 使用。

代码审查只是**知识生产的入口**，工程知识是**核心资产**，MCP 是**知识消费出口**。

## 两条核心链

```text
生产链：GitHub → Analysis → Review → Knowledge
消费链：Knowledge → RAG → MCP → Agent
```

两条链**解耦**：生产链故障不影响消费链已有知识；消费链故障不阻断生产链。

---

# 2. 核心闭环

```text
GitHub Repository（GitHub App 只读）
        ↓
Branch / Commit / Diff
        ↓
增量分析（Code Graph / Impact / Context）
        ↓
AI Review → Finding → 用户确认
        ↓
Knowledge Extraction（风险分级）
        ↓
Project / Personal Knowledge
        ↓
RAG（Scope 过滤 + 检索 + 压缩）
        ↓
MCP（Gateway 鉴权）
        ↓
Claude Code / Agent → 新代码 → 新 Commit → 再次分析
```

---

# 3. 核心设计原则

## 3.1 Local-first / Private-first

- 平台可私有部署，数据可控；
- GitHub 代码**在线只读读取，不 Clone、不落盘源码**；
- 分析所需源码只作为任务上下文临时进入内存/临时空间，任务结束即清除；
- 长期保存的是分析结果与工程知识，不是完整项目源码；
- 模型可选用户自配 API 或本地模型（Ollama）。

## 3.2 GitHub 只读（细粒度最小权限）

认证与数据访问**分离**：

- **OAuth App**：仅登录身份（`read:user user:email`），**不申请仓库权限**；
- **GitHub App**：仓库只读，仅授予 `Contents: Read` + `Metadata: Read`，**按仓库粒度安装授权**；
- 平台不拥有、不使用任何 GitHub 写权限（Push / Commit / PR / Issue / 仓库修改）。

## 3.3 生产链与消费链解耦

```text
MCP 挂掉   → Web / Review / Knowledge 全部正常
Review 挂  → 已有 Knowledge 的 MCP 查询正常
RAG 挂     → Review 降级（无知识上下文，静态分析 + LLM 仍可审）
```

关键约定：

- MCP 是 RAG 下游协议适配层，不依赖实时 Review；
- Review → Knowledge 是**异步产出关系**，知识一旦形成即为独立资产；
- Analysis、Review、Knowledge、RAG、MCP 之间不形成强耦合调用链。

## 3.4 RAG 只存知识，不存源码（数据持久化边界）

| 类别 | 内容 | 处理 |
| --- | --- | --- |
| 允许持久化 | Commit 元数据、变更统计、Diff 元数据、Review 结果、Knowledge 来源（Project/Commit/File/Symbol/少量片段） | 落库 |
| 禁止持久化 | 完整 Blob、完整 Tree、完整变更前后源码 | 不落库 |
| 短期缓存 | 当前任务所需的 AST / 代码片段 | 任务结束后清除 |

**工程化表述**：应用层禁止主动持久化完整源码至业务数据库、对象存储及普通日志；分析任务允许使用内存或受控临时空间，任务结束立即清理。

## 3.5 业务边界 ≠ 进程边界

- **逻辑服务**按业务边界拆分；
- **部署进程**按故障隔离与共享生命周期合并；
- 服务间调用采用 HTTP 接口 + 内部令牌；跨服务 HTTP 客户端统一 OpenFeign（版本由 Spring Cloud 2025.1.3 BOM 管理，见 §13）。

## 3.6 扩展点装配：Spring @Conditional 硬约束（v3.4）

凡是为后续版本预留的**扩展点**（可切换实现 / 渐进增强 / 预留通道），必须以 Spring `@Conditional` 系列注解（`@ConditionalOnProperty` / `@ConditionalOnClass` / `@ConditionalOnBean`）实现**按需装配**，禁止通过改造既有类或运行时 if-else 硬编码分支来实现扩展：

- 每个含扩展点的模块，必须在 `application.yml`（及 `application-dev/prod`）声明对应配置开关，并写注释：默认值、可选值、启用影响、关联模块/文档；
- 配置键规范：开关 `codecycle.<module>.<feature>.enabled`（true/false）；实现选择 `codecycle.<module>.<feature>.provider`（如 pgvector/milvus）；
- 扩展未启用时对应 Bean **不装配**，主链路零依赖、零额外开销；切换实现不修改既有代码；
- 各模块维护“扩展点与配置开关”清单（当前含扩展点的模块：03 GitHub Integration、04 Code Analysis、07 RAG；其余模块新增扩展功能时同样遵循本约束并补清单）；
- 示例（实际清单见各模块）：

```yaml
codecycle:
  github:
    channel: rest          # 数据通道：rest（V1）；graphql 为 P2 预留 → @ConditionalOnProperty 装配
  analysis:
    parser: javaparser     # Parser：javaparser（V1）；未来多语言/本地化引擎为可选 provider
    static-analysis:
      pmd-enabled: false   # 默认 false；true 时装配 PMD 规则引擎
    assessment:
      score-enabled: false # C 方案：V1 仅结构指标；true 后启用评分产出
  rag:
    vectorstore: pgvector  # pgvector（V1）；milvus/qdrant 为未来可选 provider
    reranker: rules        # rules（V1 规则评分）；model 为增强版 Reranker
    keyword-enabled: false # PostgreSQL 全文检索（增强版，V1 关闭）
```

---

# 4. 目标用户

- 个人开发者：管理多个项目、分析过去代码问题、沉淀个人经验；
- 学生 / 毕设开发者：项目代码质量分析、Commit 阶段反馈、知识整理；
- 开源开发者：仓库架构演进与质量分析、规范提炼；
- 小型开发团队：以项目为单位共享知识，不建立复杂企业权限体系。

---

# 5. 用户与知识模型

```text
User
 ├── Personal Knowledge
 ├── Project A → Project Knowledge
 └── Project B → Project Knowledge
```

## 5.1 两类知识空间

- **Project Knowledge**：项目架构、技术栈、规范、设计原则、历史问题、Skill、最佳实践、Architecture Decision；项目成员共享；
- **Personal Knowledge**：用户自己的编码经验、通用规范、历史错误、Skill、设计原则、开发流程；仅本人可用。

团队共享 = 项目知识共享范围；跨项目复用通过**复制/导入导出**实现，不设独立团队知识域。

## 5.2 Reference（引用）与 Copy（复制）

### Reference（引用）

- 个人知识可引用多个项目知识；**不复制正文与 embedding**，原知识更新后引用同步；
- **硬约束：Reference 不复制权限**——项目权限失效（移出成员/项目归档/知识失效）后，个人引用同步失效，检索时排除；
- 需要永久保留的项目知识，使用 Copy/Fork 脱离原项目。

### Copy（复制）

- 生成新的独立知识资产（新 id、project_id、版本从 1 起），重新生成 embedding；
- 复制后与原项目互不影响。

---

# 6. GitHub 集成

## 6.1 双轨模型

```text
OAuth App（身份登录）
  scope: read:user user:email（无仓库权限）
  → 平台用户 + GitHub 账户关联

GitHub App（仓库只读）
  Repository permissions:
    Contents: Read（文件与 Commit 历史）
    Metadata: Read（仓库基础信息，自动授予）
  → 用户安装到账户/组织，选择仓库（All / Selected）
  → 平台以 installation token 读取仓库
```

## 6.2 令牌与凭证

- OAuth 身份令牌与 GitHub App 凭证分离；
- GitHub App 私钥（PEM）在 Auth 域加密保管；installation access token 短期（约 1 小时），每次任务按需换取；
- 仓库粒度：**只有 App 安装覆盖的仓库**可被选择与分析；
- REST 为 V1 主力；GraphQL 为 P2 优化通道（暂缓）。

## 6.3 不 Clone / 不落盘

```text
GitHub API → 响应（内存）→ 分析 → 结果落库 → 源码丢弃
```

持久化边界见 §3.4。

---

# 7. 分析体系

```text
Repository
 ├── Project Initialization（首次，冷启动）
 ├── Commit Review（核心，增量）
 ├── Branch / Module Analysis（异步）
 └── Project Assessment（整体评估，异步、限频）
```

## 7.1 首次接入

README / 构建文件 / 配置 / 目录树 → 技术栈识别 → JavaParser 建 Code Graph → AI 生成 Project Profile → 初始知识候选。

## 7.2 Commit Review（核心）

- 一个 Commit = 一次主要 Review；
- **只审变更影响范围，不重审全仓库**；
- 审查重点由 Dynamic Review Plan 动态决定（Commit Message + 变更文件 + 项目规范 + 技术栈），不固定模板；
- 允许多轮上下文扩展，受资源限制硬约束。

## 7.3 Code Graph 一致性锚点（硬约束）

- Graph State 由 **(project_id, repository_id, branch_id)** 三元组唯一确定，各分支独立维护 **`current_graph_commit`**，**严禁跨分支复用图状态**；
- 当前图对应 C，目标 Commit E：必须按父链顺序补齐中间增量（C→D→E，parents[] 取第一父为增量链）；
- **Merge Commit（parents 多于 1）**：标记 `MERGE_COMMIT`，仅记录合并信息，不进行增量 Review；
- 检测到缺口（父链不连续 / 快照丢失）时：**禁止直接跳变**，先补增量或重建该分支图；
- 每次增量写入 `analysis_snapshots`（sha → 图版本），支持回溯。

变更语义（符号级）：

```text
ADD    新增节点
MODIFY 更新节点
RENAME 同一 Symbol 的位置迁移
DELETE 逻辑删除（valid_until=当前 commit，禁止物理删除）
```

关系边置信度（V1 定案）：`EXTRACTED`（解析器确定）/ `INFERRED`（启发式推断）/ `AMBIGUOUS`（重载/泛型等歧义）；Context 展开按 Strong+EXTRACTED → INFERRED → Weak/AMBIGUOUS 排序裁剪（见模块 04 §6.3）。

## 7.4 Impact Analysis（强弱区分）

```text
Strong：CALLS / OVERRIDES / IMPLEMENTS / FIELD_TYPE（高优先级）
Weak  ：IMPORT / REFERENCES / CONTAINS（低优先级）
```

Context 构建时 Strong 关系优先展开，Weak 关系按 Token 预算裁剪。

## 7.5 Context Builder（优先级硬约束）

```text
P0 当前 Diff
P1 当前方法 / 类
P2 直接调用方
P3 强类型依赖（Strong）
P4 项目规则
P5 历史 Finding
P6 用户指定范围（Hard Include）
```

- 按 Token 预算顺序裁剪，**禁止全量拼接**；
- **用户指定范围 = Hard Include**：强制包含，不参与优先级排序；剩余预算按 P0~P5 填充；
- 输出模型无关的 AnalysisContext；知识侧上下文由 Review 阶段合并 RAG（Analysis 不依赖 RAG）。

**V1 语言边界**：仅 Java 构建完整 Code Graph 与静态分析；Vue/JS/TS/YAML/SQL 等仅作为项目上下文元数据处理，不构建语言级图谱。

## 7.6 静态分析（确定性检查层）

- JavaParser（结构/符号提取）+ Checkstyle（基础规则集）；PMD / SpotBugs 列为后续；
- 静态分析负责"发现"，LLM 负责"理解与判断"；
- 静态规则结果与 LLM Finding 合并展示，来源标注 STATIC_ANALYSIS。

## 7.7 资源限制

最大影响深度 / 最大文件数 / 最大代码片段数 / 最大 Context Token / 最大 LLM 调用次数 / 单任务超时；超限截断并标记 PARTIAL，不失败。

---

# 8. Review 结果

## 8.1 Finding

- 类型：Bug / Performance / Security / Architecture / Code Smell / Design / Best Practice；
- **Status 与 Source 解耦**：Status（OPEN → CONFIRMED / REJECTED / IGNORED）用于统计；Source（LLM / STATIC_ANALYSIS / RULE）仅溯源；
- **统计口径**：OPEN + CONFIRMED 计入，REJECTED 排除；**Rejected 不进知识提炼**（硬约束）；
- **行号锚点（硬约束）**：每条 Finding 记录 `repository + commit_sha + file_path + side(OLD/NEW) + line_start + line_end`，保证 Commit 变化后回溯不失效。

## 8.2 是否跳过 LLM（动态判断，不按扩展名一刀切）

- 文档类变更：通常跳过 LLM 审查（仅静态检查）；
- 配置 / 构建 / Docker / CI 类：**根据敏感性与影响面**决定是否触发 Security / Infrastructure Review；
- 判断由 Dynamic Review Plan 完成，不依据扩展名硬性规则。

## 8.3 整体评估

- 用户手动触发（MEMBER+）、异步、限频（如 24h 一次）；
- Analysis 聚合指标 + Review LLM 总结；
- 评分聚合（Architecture / Code Quality / Maintainability / Security）为 C 方案：V1 仅预留 `assessment_scores`，V2 实现。

---

# 9. 工程知识系统

## 9.1 知识类型（7 种）

```text
BUG_AVOIDANCE          通用规避策略（Bug 提炼）
PERFORMANCE_STRATEGY   通用性能策略（Performance 提炼）
SECURITY_RULE          安全规则（全量提炼）
ARCHITECTURE_RULE      架构规则（全量提炼，含 Architecture Decision）
METRIC_RULE            度量阈值（Code Smell / Design 提炼）
GENERATION_TEMPLATE    生成模板（Best Practice 提炼）
SKILL                  开发流程 / 步骤（可复用开发步骤）
```

知识分族（P0-6：payload 字段**按具体类型专用**，不使用公共字段集合）：

| 知识族 | 类型 | 提炼策略 |
| --- | --- | --- |
| 规则型 | SECURITY / ARCHITECTURE / METRIC | 规则 → 约束（SECURITY/ARCHITECTURE=rule/constraint/scope(+decision)；METRIC 另含 metric/threshold/condition） |
| 经验型 | BUG_AVOIDANCE / PERFORMANCE | 经验 → 因果 + 策略（cause/pattern/avoidance|strategy/applicable） |
| 生成型 | GENERATION_TEMPLATE / SKILL | 生成 → 步骤 + 模板 + 验证（按类型专用，见模块 06 §5） |

## 9.2 提炼规则

| Review 类型 | 提炼结果 | 发布方式 |
| --- | --- | --- |
| Bug | BUG_AVOIDANCE | 低风险自动（SUPPORTED）/ 高风险人工 |
| Performance | PERFORMANCE_STRATEGY | 低风险自动（SUPPORTED）/ 高风险人工 |
| Security | SECURITY_RULE | 一律人工审核（PENDING_REVIEW） |
| Architecture | ARCHITECTURE_RULE | 一律人工审核（PENDING_REVIEW） |
| Code Smell / Design | METRIC_RULE | 一律人工审核（PENDING_REVIEW） |
| Best Practice | GENERATION_TEMPLATE | 低风险自动（SUPPORTED）/ 高风险人工 |
| 重复性开发流程 | SKILL | 默认人工审核（影响 Agent 行为） |
| 具体补丁 / 单次修改 | 不提炼 | — |

> 发布策略最终统一表（P0-4）：规则型一律 PENDING_REVIEW；经验型低风险（conf≥0.8）自动；TEMPLATE 低风险自动；SKILL 默认人工（详见模块 06 §7.1）。

## 9.3 生命周期

```text
阶段一 Evidence Preparation：已确认 Finding → 五层限界证据（P0~P4，硬预算）
阶段二 Candidate Extraction：分类 + LLM 提炼 → knowledge_candidate（Finding 粒度）→ 立即向量化
阶段三 Consolidation：pgvector 相似召回 + LLM 归并（NEW / MERGE / REVISION / DUPLICATE）
阶段四 Publication：Confidence + Risk → 规则型/SKILL 人工审核 / 经验型（低风险）与 TEMPLATE 自动发布 → PUBLISHED
```

状态机（Q7 定案，按《知识蒸馏》附件）：

```text
OBSERVED → CANDIDATE → SUPPORTED → PUBLISHED → SUPERSEDED
                              └→ PENDING_REVIEW → PUBLISHED / REJECTED（规则型/SKILL/高风险）
PUBLISHED → OUTDATED（来源变更，待复核 → 新版本或 ARCHIVED）
```

Candidate 状态子集：CANDIDATE（单 Finding 产生）/ SUPPORTED（证据门槛：默认 ≥3 Finding、≥2 Module、conf≥0.8，可配置）/ MERGED / REJECTED；不含 PUBLISHED（Published 状态独立于 knowledge.status）。

- **演进链路（硬约束）**：`finding → candidate → consolidation → knowledge`，保留 finding_id / candidate_id / knowledge_id 关联，支持全程溯源；
- **蒸馏范围（硬约束）**：不扫描整个代码库，只取 Finding 相关有限证据（文件≤10 / 片段≤20 / 节点≤50 / 深度≤2 / Token≤12000，超限按 P0>P1>P2>P3>P4 截断）；
- **计算引擎（定案）**：蒸馏全链路 Java（JavaParser + Spring AI），不引入 Python / FastAPI / Tree-sitter；未来 C/S 或本地化项目再评估直接引入开源引擎（模块 04 §6.1.2）；
- **Candidate 与 Published 分库**：未验证知识不进入 RAG/MCP 检索路径（独立向量集合）；
- **粒度**：Candidate=Finding 粒度；Published=知识主题粒度（一条知识 = 一个可复用规则/策略/模板）；
- **去重归并**：pgvector 相似召回 + LLM Consolidation 双层；计数聚合（occurrences / evidence_count / commit_count / module_count）；
- **知识分族**：规则型 / 经验型 / 生成型，对应不同提炼策略与检索优先级；
- **动态置信度**：confidence = f(evidence_count, commit_count, module_count, confirmation_rate)，程序计算；
- **发布门槛**：规则型与 SKILL 一律人工（PENDING_REVIEW）；经验型低风险（conf≥0.8）与 TEMPLATE 低风险自动；SUPPORTED 证据门槛默认 ≥3 Finding、≥2 Module、conf≥0.8；
- **版本硬约束**：知识记录 `knowledge_source_version`（来源 Commit 版本）；来源变化后旧知识标记 OUTDATED，新知识 supersedes 旧版本，**禁止直接 UPDATE 覆盖**；
- 模型输出永远只是 Candidate，不能直接成为 Published（硬约束）。

## 9.4 关系与导出

- 关系：RELATED_TO / DERIVED_FROM / SUPERSEDES / APPLIES_TO（PostgreSQL 表，不引入图数据库）；
- 导出：Markdown / JSON / YAML（不含 embedding），**不上传 GitHub**；导入重新生成 embedding。

---

# 10. RAG

## 10.1 选型（已定）

- **PostgreSQL + pgvector**（HNSW）；Spring AI `PgVectorStore` + metadata 过滤；
- 通过 VectorStore 抽象保留未来切换 Milvus / Qdrant 的能力；V1 不引入其他向量/搜索组件。

## 10.2 Pipeline

```text
Query → Query Classification（关键词 / typeHint 规则匹配，纯规则不调 LLM）
      → Scope Filter（Profile / 权限，服务端强制）
      → Vector Search（TopK 候选）
      → Rerank（规则评分）
      → Context Compression（Top 3~8）
```

增强（P2）：关键词检索 + RRF + Reranker 模型。

## 10.3 Embedding 生命周期（硬约束）

```text
PENDING → COMPLETED / FAILED
        →（来源/模型变更）→ OUTDATED → REBUILDING → COMPLETED
```

- 知识已发布但 Embedding 未完成：**Web 可见，MCP 暂不可检索**；
- 失败保留可重试状态，不阻塞知识管理。

## 10.4 权限（硬约束）

- 检索与详情查询必须**同时校验知识归属范围**：`knowledge ∈ profile_scope`；
- 引用知识在项目权限失效后同步排除（§5.2）；
- 服务端强制过滤，前端/Agent 不可传任意 projectId 越权。

---

# 11. MCP

- 传输：远程 HTTPS **Streamable HTTP**，生产端点 `https://codecycle.me/mcp`；
- 鉴权：与其他微服务一致走 **Gateway**；**单一高熵 Token**（`ccmcp_`，Snowflake ID + SecureRandom 256-bit）与登录 Token 分离；
- **Token 只走 HTTP Authorization Header**，Tool 入参只含业务参数（硬约束）；
- **有效期与续期（窗口化滑动过期）**：固定 30 天，仅当剩余 TTL ≤ 7 天才重置 `now+30d`（避免每次请求无谓写）；连续 30 天未使用自动失效；无 Refresh 流程、无 Token Version、无换发；
- **存储**：PostgreSQL（事实源）与 Redis（运行时状态 `mcp:token:{sha256(token)}`，TTL 自动淘汰）**仅存 SHA-256 哈希**，绝不存 Token 原文；Redis 仅限 MCP Token 运行时状态，不作为通用缓存；
- **校验兜底**：Redis 为加速层非事实源——Gateway Redis Miss 时调用 Auth 内部 Token Introspection 查询 PostgreSQL，有效则回填 Redis（TTL = 剩余时间）继续请求，无效返回 401（见模块 01 §7.5）；
- **撤销/重新生成**：status=REVOKED（Redis + PG）立即全局失效；重新生成 = 撤销旧 + 新建；
- V1 工具：`search_knowledge` / `get_knowledge`；`review_change` 为 V2；
- 返回：结构化字段 + 精炼文本，Top 3~8，不返回完整 Knowledge 内部对象；
- **get_knowledge 硬约束**：`WHERE knowledge.id = ? AND knowledge ∈ profile_scope`，必须校验归属范围；
- 数据归属：Profile 在 Knowledge 域、mcp_tokens 在 Auth 域（只存哈希）、Redis 存运行时状态，MCP 只做协议适配与编排；
- Profile 范围默认**不含个人知识**：include_personal 默认 false，显式开启才纳入 Personal Knowledge（含有效引用，见模块 08 §7）；
- 签发分工：Web Access / Refresh 由 Sa-Token 签发；**MCP Token 由 Auth 域按上述生成规则统一签发**。

---

# 12. AI 模型体系

- **Spring AI 2.0.1 为唯一主框架**（ChatModel / EmbeddingModel / ChatClient / VectorStore / RAG / Tool Calling / MCP / Structured Output）；
- **LangChain4j 不作为核心依赖**：当前能力缺口均可用 Spring AI 覆盖；后续确有 Agent/Workflow 能力缺口时再评估；
- Chat Model：用户自行配置（Provider / Endpoint / Key / Model），第一版不做模型供应商平台；
- Embedding：冻结为 **Ollama 0.12.1 + Qwen3-Embedding-0.6B**（默认本地运行），与 Chat 解耦；Ollama 稳定镜像 tag 在容器化接入时确认后回写冻结；
- Structured Output 优先；模型输出不能直接成为 Published Knowledge。

---

# 13. 技术栈（v3.6 冻结稿）

> 版本冻结策略（v3.6）：下表版本为本项目冻结版本，不再使用"x.x 可升级""方向"等模糊表述；后续需要新增技术栈时，先在小范围测试并加入下表 + CHANGELOG 后视为冻结。

| 层级 | 技术 | 冻结版本 | 说明 |
| --- | --- | --- | --- |
| Runtime | OpenJDK | 21 LTS | 服务运行时 |
| Runtime | Node.js | 24 LTS | 前端构建/工具链运行时 |
| 构建 | Maven | 3.9.16 | Java 构建工具 |
| 后端框架 | Spring Boot | 4.0.8 | 唯一主框架（Spring Framework 由 Boot BOM 管理） |
| 微服务 | Spring Cloud | 2025.1.3（BOM） | Gateway 等组件版本随 BOM |
| 微服务 | Spring Cloud Gateway | 随 Spring Cloud 2025.1.3 BOM | 入口 / 路由 / 鉴权 |
| 微服务 | Spring Cloud OpenFeign | 随 Spring Cloud 2025.1.3 BOM | 跨服务 HTTP 客户端（v3.6 重新冻结，历史废弃记录见 CHANGELOG v3.0） |
| 微服务 | Spring Cloud Alibaba | 2025.1.0.0 | Nacos Discovery / Config 客户端（v3.6 重新冻结） |
| AI | Spring AI | 2.0.1 | 唯一 AI 主框架（Chat/Embedding/RAG/VectorStore/MCP） |
| 认证鉴权 | Sa-Token | 1.46.0 | Boot4 starter + reactor Gateway + JWT 无状态；不采用 Spring Security；GitHub OAuth 手动实现 |
| ORM | MyBatis-Flex | 1.11.6 | 数据访问层 |
| 代码智能 | JavaParser | 3.28.2 | AST/符号（SymbolSolver 随 3.28.2 管理） |
| 代码智能 | Checkstyle | 14.1.0 | V1 基础规则集（必做） |
| 代码智能 | PMD | 7.27.0 | 版本冻结；启用时机按模块 04 决策（后续，不阻塞 V1） |
| 数据库/向量 | PostgreSQL 16 + pgvector | Docker 镜像 pgvector/pgvector@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b | 关系数据 + 向量检索单库 |
| AI 模型 | Ollama | 0.12.1（冻结建议；稳定镜像 tag 在容器化接入时确认并回写本节） | 本地模型运行时 |
| AI 模型 | Qwen3-Embedding | 0.6B | 默认 Embedding 模型（Ollama 本地运行） |
| GitHub | GitHub OAuth App / GitHub App / GitHub REST API | 平台能力，无版本 | OAuth 仅身份；App 仓库只读；REST V1 |
| 对象存储 | MinIO（S3 兼容） | 容器化运行（版本见 docker-compose.yml） | 头像 / 图片 / 导入导出文件 |
| 异步任务 | PostgreSQL 任务表 + Scheduler 轮询 | — | status / next_run_at / 指数退避；无消息队列 |
| MCP | Spring AI 2.0.1 MCP（Streamable HTTP） | 随 Spring AI 2.0.1 | 远程 HTTPS |
| 前端 | Vue 3 / Vite / TypeScript | Vue 3.5.42 · Vite 8.1.5 · TS 7.0.2 | SPA 框架 |
| 前端 | Element Plus / Pinia / Vue Router | Element Plus 2.14.5 · Pinia 4.0.3 · Vue Router 5.3.1 | UI / 状态 / 路由 |
| 前端 | Axios | 1.20.0 | HTTP 客户端 |
| 前端 | vue-tsc / ESLint / Prettier | vue-tsc 3.3.11 · ESLint 10.4.0 · Prettier 3.6.2 | 类型检查 / Lint / 格式化 |
| 测试 | JUnit Jupiter | 5.14.4 | Java 单测 |
| 测试 | Mockito | 5.23.0 | Mock |
| 基础设施 | Nacos Server | 2.5.4 | 注册中心 + 配置中心（Docker 单机） |
| 基础设施 | Redis | 7.4.11 | 仅 MCP Token 运行时状态（TTL 自动淘汰） |
| 基础设施 | Docker Engine / Compose | 29.7.2 / v5.5.0 | 虚拟机运行中间件 |
| 部署 | Windows 宿主 + 虚拟机 Docker | — | 域名 codecycle.me，隧道 + HTTPS |

**明确不引入**：Kafka / RabbitMQ 等消息中间件（v3.3 复审定案，评估记录见 §14.4.1；触发条件出现前不引入）、通用缓存中间件（Redis 仅限 MCP Token 运行时状态专用，见 §11）、非 Spring AI 的 AI 框架（LangChain4j 等）、Spring Security、OAuth 仓库权限、MySQL、SpotBugs（未冻结）；Milvus/ES/Neo4j 仅作为 V1 之后的向量扩展评估项。OpenFeign 与 Spring Cloud Alibaba（Nacos 客户端）自 v3.6 起重新冻结引入，v3.0 的废弃记录仅适用于当时基线（完整历史见 CHANGELOG）。

---

# 14. 服务边界与部署

## 14.1 逻辑服务（业务边界）

```text
Gateway / Auth / Project / GitHub Integration / Code Analysis / Review / Knowledge / RAG / MCP
```

## 14.2 部署边界（业务边界 ≠ 进程边界）

| 进程 | 逻辑服务 | 合并理由 |
| --- | --- | --- |
| Gateway | Gateway | 独立入口，统一鉴权，无业务数据 |
| Auth | Auth | 安全边界：身份/凭证独立 |
| Project | Project + GitHub Integration | 项目上下文域，仓库数据强依赖 |
| Analysis/Review | Code Analysis + Review | 审查流水线，共享任务生命周期 |
| Knowledge/RAG | Knowledge + RAG | 知识资产与检索同域，共享 pgvector |
| MCP | MCP | 外部暴露面，独立故障域 |

## 14.3 数据归属（单实例 PostgreSQL + pgvector，逻辑分库）

```text
auth      ：users、oauth_accounts、refresh_tokens、mcp_tokens（只存哈希）、github_app_keys
runtime   ：Redis（仅 MCP Token 运行时状态：mcp:token:{sha256(token)}，TTL 自动淘汰）
project    ：projects、project_repositories、project_members、project_branches、github_repositories
analysis   ：analysis_tasks、commits、commit_changes、code_symbols、code_relations、analysis_snapshots
review     ：reviews、review_findings
knowledge  ：knowledge、knowledge_candidate、knowledge_versions、knowledge_relations、knowledge_references、mcp_profiles、knowledge_tasks（蒸馏/Embedding/重建）、embeddings（pgvector：candidate_vectors + knowledge_vectors）
project/analysis：assessment_scores（V1 预留）
```

服务间不跨库 join，只通过 HTTP 接口 / 异步任务访问。

## 14.4 异步任务机制（已定）

```text
PostgreSQL 任务表（每域一表：analysis_tasks / knowledge_tasks）
 ├── status：QUEUED / RUNNING / COMPLETED / FAILED / FAILED_RETRYABLE / CANCELLED
 │           （COMPLETED / FAILED / CANCELLED 为终态，禁止终态回 RUNNING）
 ├── next_run_at：下次执行时间（时间窗口 / 指数退避：1s → 2s → 4s … 上限 60s）
 └── 调度：Scheduler 轮询到期任务（默认 5s），虚拟线程执行
```

**任务表（v3.5 定案）**：每域一表——`analysis_tasks`（分析域）、`knowledge_tasks`（知识域：DISTILLATION / EMBEDDING / REINDEX），共享同一调度器接口与字段约定（status / next_run_at / 指数退避 / 认领 / 分支合并），域内事务不跨域。

**发布-订阅语义（无 MQ）**：发布 = Commit/事件落任务表；订阅 = Scheduler 消费；可选 PG LISTEN/NOTIFY 即时唤醒优化。

**任务合并与并发（Q8/Q11 定案，详见模块 04 §15）**：

```text
按分支合并：同一 (project, repository, branch, type) 最多一个 QUEUED 任务（部分唯一索引）
时间窗口：WINDOW=90s（可配置），窗口内新事件滚动合并 target_sha；
         MAX_WAIT=5min，next_run_at = min(now+WINDOW, created+MAX_WAIT)
分支级串行：同时最多一个 RUNNING 任务；RUNNING 期间新事件记录 pending_sha，完成后接力追赶
全局并发上限：analysis.concurrency-limit=2（虚拟线程 Worker 数 = N）
失败重试：指数退避，恢复后从上次成功点处理到最新（范围追赶）
```

适用：首次分析、Commit Review、Branch/Module 分析、知识提炼、Embedding、整体评估。

### 14.4.1 异步方案复审（v3.3 定案：不引入消息中间件）

**结论：V1 维持 PostgreSQL 任务表 + Scheduler，不引入 Kafka / RabbitMQ。** 发布-订阅语义已由任务表 + Scheduler 实现（发布 = 事件落表，订阅 = 调度消费；可选 PG LISTEN/NOTIFY 即时唤醒），本结论经以下评估后确认。

| 需求维度 | 系统需要 | PG 任务表方案 | RabbitMQ | Kafka |
| --- | --- | --- | --- | --- |
| 语义 | 任务级：状态、重试、进度可查 | 原生支持（status/next_run_at/指数退避） | 需另建任务状态表 | 需另建任务状态表 |
| 去重合并 | 窗口内多 Commit 合并为一个任务 | 部分唯一索引 + 滚动 target_sha 天然实现 | 需额外去重逻辑 | 需额外去重逻辑 |
| 顺序 | 同一分支严格串行 | 分支级锁 / 部分唯一索引 | 单消费者内有序，重投/多消费者需设计 | 分区键 = 分支，可有序但架构重 |
| 一致性 | 业务数据与任务同一事务 | 同库同事务，无双写问题 | 需 outbox（本质仍是任务表 + 投递器） | 同 RabbitMQ |
| 跨服务解耦 | 6 进程共享同一 PG | 事件方插表、消费方调度，已解耦 | 进程级解耦更强，但当前无收益 | 同 RabbitMQ |
| 延迟 | 审查/蒸馏分钟级、Embedding 秒级 | 5s 轮询 + LISTEN/NOTIFY 足够 | 毫秒级推送（不需要） | 毫秒级推送（不需要） |
| 吞吐 | 单用户日任务数十~数百，峰值远低于 1 msg/s | 无压力 | 无压力但引入中间件成本 | 严重过剩 |
| 运维 | 毕设单机 + VM Docker | 零新增依赖 | 新增中间件 + 故障面 | 需 KRaft/分区/副本，最重 |

**升级触发条件（出现任一再评估，届时选型：任务负载 → RabbitMQ；事件流/回放 → Kafka）**：

```text
1. 多实例 / 多进程部署导致任务抢单与公平调度成为瓶颈；
2. 需要事件扇出 / 多消费者广播（如 Webhook 实时通知、审计流、跨平台事件）；
3. 任务吞吐持续 > 100 msg/s；
4. 需要事件回溯、重放或跨域系统集成（CI 平台、外部 SaaS）。
```

## 14.5 编码实现约定（v3.6）

本节是各模块进入编码时的共同约定；模块不得另行定义冲突版本。

### 14.5.1 内部服务认证

- 所有 `/internal/**` 请求必须经过内部服务令牌校验；请求头统一为 `X-Internal-Service-Token`，令牌由部署环境注入，不存 PostgreSQL、日志或业务表；
- V1 使用每个部署环境独立的 256-bit 随机令牌，放入 `.env` / 密文配置，服务启动时加载；生产通过 Nacos 加密配置或环境密文注入；
- Gateway 不把外部 Access/MCP Token 传给内部服务。通过校验后仅透传 `X-User-Id`、`X-Auth-Type`、`X-Mcp-Profile-Id` 等已验证上下文；
- 令牌轮换采用短暂双令牌窗口：新旧令牌同时有效不超过 15 分钟，轮换完成后立即撤销旧令牌；校验失败统一返回 401/403，不泄露令牌状态。

### 14.5.2 Webhook 接收与幂等

```text
GitHub → Gateway POST /api/webhooks/github
       → Auth：校验 X-Hub-Signature-256 与 Webhook Secret
       → Analysis：校验 repository/project 映射，写入事件回执并创建分析任务
```

- Gateway 只负责白名单、请求体透传和基础限流；HMAC 验签必须基于原始请求体，由 Auth 完成；
- 使用 GitHub `X-GitHub-Delivery` 作为 `delivery_id`，在 `analysis_webhook_events` 建唯一约束；重复事件返回 202，不重复创建任务；
- 事件回执记录 event type、repository id、commit SHA、接收时间、处理状态和错误原因；不保存完整源码或不必要的请求体；
- V1 仅对 `push`/Commit 事件创建分析任务，PR、Issue、成员变化事件保留为 V2。

### 14.5.3 异步任务与跨域幂等

- 状态统一为 `QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED`；`FAILED_RETRYABLE` 只能回到 `QUEUED`，不是终态；
- 任务必须有稳定 `dedup_key`：分析任务按 `project + repository + branch + type + target range` 合并；知识任务按来源 Finding/Knowledge、任务类型和目标版本去重；
- 跨域交接采用“至少一次投递 + 消费方幂等”。Review、Knowledge、RAG 写入任务前使用来源标识和唯一约束去重，失败可安全重试；
- 任务状态更新、进度和错误写入任务表；Worker 认领使用数据库行锁/乐观条件更新，禁止同一分支同一类型并发运行。

### 14.5.4 统一 HTTP API 约定

- 每个请求使用或生成 `X-Request-Id`，响应统一为：

```json
{"ok": true, "data": {}, "requestId": "..."}
{"ok": false, "error": {"code": "...", "message": "...", "retryable": false}, "requestId": "..."}
```

- 分页统一返回 `items`、`page`、`pageSize`、`total`；异步接口统一返回 `taskId` 和当前状态；
- 重复提交使用幂等键；资源版本冲突返回 409；未认证返回 401，已认证但无权限返回 403，资源不存在或无权查看时按接口安全要求返回 404；
- 外部 API 由 Gateway 统一生成 requestId、限流和错误映射；内部服务不得把第三方错误结构直接暴露给前端。

### 14.5.5 数据清理、审计与敏感信息

- 源码只允许进入内存或受控临时空间；任务成功、失败、取消后都必须在 `finally` 清理，定时清理器每 1 小时扫描超过 24 小时的残留临时文件；
- MinIO 临时导入/导出对象默认 24 小时过期，业务头像、Logo 和已确认导出文件按业务记录管理；presigned URL 默认 5 分钟；
- 日志禁止记录 Access Token、MCP Token、Webhook Secret、API Key、私钥和 presigned URL；开发日志默认保留 30 天，生产日志默认保留 90 天；安全审计事件默认保留 180 天；
- 用户注销时立即撤销登录/MCP Token 并删除对象资产；历史 Commit、Review、Finding、Snapshot、Knowledge Version 按审计规则不可物理删除，项目知识通过归档失效。

### 14.5.6 AI 调用运行约束

- ChatModel 和 EmbeddingModel 均通过 Spring AI 配置，Provider、Endpoint、Model、超时和调用预算不得硬编码；
- 单次 Chat 调用默认超时 60 秒，单任务默认最多 3 次 LLM 调用；超时/限流按 `FAILED_RETRYABLE` 处理，超过重试上限转 `FAILED`；
- 每次 Review/Distillation 记录 provider、model、prompt_version、调用次数、输入输出 token 估算和最终状态；API Key 仅由环境变量或密文配置注入；
- Embedding 模型或维度变化必须创建 `REINDEX` 任务，旧向量先标记 `OUTDATED`，不得直接覆盖新旧模型数据。

## 14.6 产品部署 vs 开发环境（分离）

### Product Deployment（产品依赖）

```text
6 个进程（Gateway / Auth / Project+GitHub / Analysis+Review / Knowledge+RAG / MCP）
+ PostgreSQL + pgvector + Nacos（注册/配置中心）+ Redis（仅 MCP Token 运行时状态）+ MinIO（对象存储）
```

- 中间件可用 Docker Compose 编排（docker-compose.yml + .env，见架构 deployment.md）；
- MinIO：头像/图片/导入导出文件对象存储，私有 Bucket + presigned URL（V1 引入）。

### Development Environment（作者本地开发方式，非产品依赖）

```text
Windows 宿主运行 6 个进程；虚拟机（192.168.80.125）Docker Compose 运行中间件：
PostgreSQL + pgvector、Nacos、Redis、MinIO（docker-compose.yml + .env）；
Cloudflare Tunnel 绑定 codecycle.me 仅为本地开发/演示打通 HTTPS 的方式，
不是产品运行依赖（产品只需任意可部署的 HTTPS 环境）。
```

- 开发/生产双配置（application-dev / application-prod），敏感信息只走环境变量/密文；
- 虚拟机网络：VM 192.168.80.125 / 网关 192.168.80.2 / 宿主 192.168.80.1；应用经 VM IP 访问中间件；
- 文档中"部署环境"默认指 Product Deployment；Windows + Docker VM + Tunnel 仅在开发环境章节说明。

---

# 15. 核心业务流程

## 15.1 首次接入

```text
GitHub OAuth 登录（身份）→ 安装 GitHub App 到仓库
→ 选择 App 覆盖的 Repository → 创建项目
→ 同步分支 / Contributors → 首次分析（Code Graph + Project Profile）
```

## 15.2 Commit Review

```text
Commit 事件（Webhook / 分支轮询 / 手动选择，见模块 04 §15.1）→ Analysis（Diff → 符号 → 影响 → Context）
→ RAG 知识（可降级）→ Dynamic Review Plan → LLM Review
→ Finding（行号锚点）→ 用户确认 → 知识提炼（异步）
```

## 15.3 后续开发

```text
Agent → MCP（Authorization Header）→ Profile 范围 → RAG
→ 项目规范 / Skill / 历史错误 → 生成代码 → Commit → Review → 新知识
```

---

# 16. 第一阶段 MVP

- 用户：GitHub OAuth 登录（身份）、GitHub App 安装与仓库选择；
- GitHub：仓库/分支/Commit/Diff/Tree/Blob/Contributors 只读；
- 事件：GitHub App Webhook 接收（HMAC 验签 + 防重放）+ 分支轮询 / 模拟事件（本地调试兜底，不依赖公网域名）；
- 分析：首次分析 + Code Graph + Commit 增量 + 静态分析 + Context（优先级裁剪）；
- Review：Finding（锚点、状态管理、动态计划）；
- Knowledge：7 类知识、提炼、风险分级发布、Reference/Copy、版本、导出；
- RAG：Embedding + Scope 过滤 + 向量检索 + 压缩；
- MCP：search/get 工具、Profile + Token（Header 鉴权）；
- 前端：Dashboard / Project / Commit Review / Knowledge / MCP；
- 异步：PostgreSQL 任务表（analysis_tasks / knowledge_tasks）+ Scheduler。

---

# 17. 后续版本

## V1

- Branch / Module Analysis 完善、Commit 趋势；
- 知识导入完善、失效检测完善；
- Project Health（C 方案：V1 仅数据结构）。

## V2

- MCP review_change（Commit 事件驱动的自动 Review）；
- 关键词 + 向量 Hybrid Search、Reranker；
- CLI / IDE 插件；Contributors 自动同步。

## V3（不属本科核心）

- 公共知识库 / 知识市场、开放平台 API、更多 Git 平台、企业能力。

---

# 18. 明确不做的功能

- GitHub 写操作（Push / Commit / PR / Issue / 仓库修改）；
- 在线 IDE、自动修改代码、Agent 自动执行；
- 企业审批流、SaaS 多租户、企业 SSO、复杂 RBAC；
- 完整 GitHub 替代品、Repository 完整 Clone、大规模源码长期存储；
- 自研大模型、在线模型供应商平台；
- 消息队列与缓存中间件（V1）。

---

# 19. 项目核心价值

> **让项目和开发者过去积累的工程经验能够持续参与未来的 AI 辅助开发。**

一句话概括：

> **GitHub 提供代码与开发历史，AI 负责分析和提炼，RAG 负责保存工程经验，MCP 负责让 Agent 使用这些经验。**

---

# 20. 版本记录

- v3.6（2026-09-04）：技术栈版本冻结（Spring Boot 4.0.8 / Spring Cloud 2025.1.3 / SCA 2025.1.0.0 / Spring AI 2.0.1 等全量版本写入 §13；OpenFeign 与 Spring Cloud Alibaba 重新冻结引入），见 [CHANGELOG.md](./CHANGELOG.md)；
- v3.5（2026-09-04）：基线复查修订——事件源双模式（Webhook V1 + 本地调试兜底）、每域一表任务体系（knowledge_tasks）、Profile 个人知识默认关闭、人工审核全候选兜底、前端进度轮询定案、文档头与薄版文档同步（见 [CHANGELOG.md](./CHANGELOG.md)）；
- v3.4（2026-09-04）：扩展点装配硬约束（Spring @Conditional + application.yml 声明注释规范），见 [CHANGELOG.md](./CHANGELOG.md)；
- v3.3（2026-09-04）：异步方案复审（不引入 Kafka/RabbitMQ）+ 未定决策清零（生产 JWT/静态分析范围/事件源/人工审核入口）；
- v3.2（2026-09-02）：终版架构统一（MCP 校验兜底 + 发布策略收敛，8 项 P0）；
- v3.1（2026-09-02）：知识蒸馏定案并入文档（全 Java 引擎 + 状态机 + 并发控制）；
- v3.0（2026-09-01）：基线统一修订，见 [CHANGELOG.md](./CHANGELOG.md)；
- v2.x / v1.0：历史基线，废弃结论仅保留在 CHANGELOG。
