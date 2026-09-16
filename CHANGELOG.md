# CodeCycle 设计基线变更记录

> 本文档保存**已废弃结论**的历史痕迹；正文（项目定义与各模块文档）不得出现下列废弃字眼。
> 例外说明（v3.4+）：正文中 Redis 以"仅 MCP Token 运行时状态专用"、Kafka/RabbitMQ 以"否决/评估对象"语境出现属合法说明，不算残留。
> 例外补充（v3.6）：OpenFeign 与 Spring Cloud Alibaba（Nacos 客户端）自 v3.6 重新冻结引入，正文合法出现；v3.0 的废弃记录仅适用于当时基线。

## v3.6（2026-09-04）—— 技术栈版本冻结

1. **版本冻结清单写入项目定义 §13**（原"v3.0 最终稿"表废止）：Runtime OpenJDK 21 LTS / Node.js 24 LTS；Maven 3.9.16；Spring Boot 4.0.8（取代原 4.1.x）；Spring Cloud 2025.1.3（BOM）；Spring Cloud Gateway / OpenFeign 随 BOM；Spring Cloud Alibaba 2025.1.0.0（Nacos 客户端）；Spring AI 2.0.1（取代原 2.x）；Sa-Token 1.46.0；MyBatis-Flex 1.11.6；JavaParser 3.28.2（SymbolSolver 随附）；Checkstyle 14.1.0；PMD 7.27.0（启用时机仍按模块 04 决策）；PostgreSQL 16 + pgvector（镜像 sha256:ccc6e83d...fb4d6b）；Ollama 0.12.1 + Qwen3-Embedding-0.6B；前端与测试工具版本全量冻结；基础设施 Nacos Server 2.5.4 / Redis 7.4.11 / Docker Engine 29.7.2 / Compose v5.5.0；
2. **OpenFeign / Spring Cloud Alibaba 由"不引入"改为"冻结引入"**：跨服务 HTTP 客户端统一 OpenFeign（BOM 管理版本，配合内部令牌）；模块 02/03 与项目定义 §3.5 的旧约束同步修订；
3. **明确不引入清单修订**：移除"服务间调用框架依赖"；新增 SpotBugs（未冻结）、LangChain4j 等非 Spring AI 框架明示；Spring Security、MySQL、消息中间件、通用缓存维持否决；
4. docker-compose 镜像对齐冻结版本（pgvector digest / nacos v2.5.4 / redis 7.4.11 / minio 与 mc 固定 RELEASE）；
5. 版本冻结策略：后续新增技术栈需先测试，再写入 §13 + 本文档视为冻结，正文不再使用"x.x 可升级/方向"等模糊表述。

## v3.5.2（2026-09-04）—— 中间件编排 docker-compose + MinIO 引入

1. **新增 docker-compose.yml / .env / .env.example**（项目根目录）：PostgreSQL+pgvector、Nacos（单机）、Redis、MinIO 四个中间件；容器名、服务名、网络、数据卷全部统一 `codecycle_*` 显式命名，不使用匿名卷与默认位置；
2. **目标环境**：虚拟机 Docker（VM 192.168.80.125 / 网关 192.168.80.2 / Windows 宿主 192.168.80.1），端口映射到 VM 全接口，Windows 宿主应用经 VM IP 访问；
3. **MinIO 分析结论：引入**——用户头像/项目图片/导入导出文件为二进制持久化，不适合入 PG；纳入 compose（codecycle-minio + codecycle-minio-init 自动建桶）；
4. **Bucket 规划**：codecycle-assets（头像/Logo/图片）、codecycle-files（导入导出），均私有；对象键规范 user/{userId}/project/{projectId}/export/...；头像 ≤2MB、图片 ≤5MB、png/jpeg/webp；应用层 presigned URL（5 分钟）访问，MinIO 9000/9001 不直接暴露公网（控制台 9001 仅开发）；
5. 产品依赖更新为：PostgreSQL+pgvector、Nacos、Redis（MCP Token 运行时状态）、MinIO；部署文档 deployment.md 重写（清理重复段落）并补 MinIO 设计章节；项目定义 §13/§14.5 同步。

## v3.5.1（2026-09-04）—— 数据库全局约定定案（9 项）

1. **5 Schema 锁定**：auth / project / analysis / review / knowledge，单数据库业务域级逻辑隔离；
2. **跨 schema 不建物理 FK**（同域可建），一致性由应用层保证；
3. **Snowflake 只生成业务主键、不表达业务语义**：业务主键统一 BIGINT（Java Long），UUID 不作为业务主键（可作 external_id/public_id/request_id）；
4. **VARCHAR + CHECK 仅用于真枚举字段**（不给自由文本加 CHECK），Java Enum + DB CHECK 双层保护；CHECK 覆盖终态与合法状态转移；
5. **删除策略由"全部逻辑删除"改为三类**：① 逻辑删除（projects/project_members/knowledge 等可撤销实体）；② 状态失效（mcp_tokens/refresh_tokens 等生命周期实体）；③ 历史事实不删除（commits/reviews/review_findings/knowledge_versions/analysis_snapshots，审计记录无删除操作）；
6. **时间字段统一**：created_at/updated_at TIMESTAMPTZ NOT NULL，UTC 存储；不可变历史表仅 created_at；
7. **乐观锁**：并发编辑业务表加 version BIGINT DEFAULT 0（projects/knowledge/mcp_profiles 等）；不可变历史表不加；knowledge 已有业务版本列 version，乐观锁列命名 row_version 避免语义冲突；
8. **历史事实不可删除**：Commit/Review/Version/Snapshot 为审计记录，不提供物理或逻辑删除；
9. **任务状态机补充终态**：QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED（后三者终态），FAILED_RETRYABLE → QUEUED 退避重试；禁止终态回 RUNNING（同步 04/06/项目定义）；
10. **PgVectorStore 托管约束**：candidate_vectors / knowledge_vectors 必须独立物理表/Collection（不靠 status 区分），不手工改内部表结构，metadata 扩展走 Spring AI 机制。

## v3.5（2026-09-04）—— 基线复查修订：事件源/任务表/安全默认值收敛

1. **D1｜任务表定案：每域一表**——analysis_tasks（分析域）与 knowledge_tasks（知识域：DISTILLATION / EMBEDDING / REINDEX）共享同一调度器接口与字段约定（status / next_run_at / 指数退避 / 认领 / 分支合并），域内事务不跨域；knowledge_tasks 表结构写入模块 06 §8；
2. **D2｜Commit 事件源提入 V1 双模式**——Webhook（生产/演示默认，HMAC 验签 + 防重放，路由 `POST /api/webhooks/github`）+ 分支轮询 / 模拟 Push 事件（开发兜底，不依赖公网域名，仅 dev 开放）；原 v3.3 "Webhook V2" 表述作废（历史保留本条，正文以 v3.5 为准）；
3. **D3｜个人知识默认不混合**——MCP Profile include_personal 与 Web includePersonal 默认均 false，显式开启才纳入 Personal Knowledge（含有效引用）；
4. **D4｜人工审核兜底扩展到任何候选**——任意候选（含单 Finding / 未达 SUPPORTED 门槛）可由用户提前转人工审核并发布，风险由人承担；自动发布路径仍要求 SUPPORTED；
5. **D5｜前端进度通道定案**——V1 轮询（默认 5s），SSE 列为 V2 升级点（模块 04/05 已改）；
6. **D6｜Nacos 定位确认**——保留注册中心 + 配置中心；单机开发只启动单节点，不涉及集群/部署管理；
7. **P1 修订**——文档头统一"基线 v3.5"（项目定义 + 01~08）；00-overview、database-design、data-flow、service-boundary、system-architecture、README 同步 v3.5；Rerank 规则评分补 keyword 关闭时的归一化公式（0.7×向量 + 0.3×优先级）；订正失效交叉引用（07→01 §12.3、03→01 §19、05→项目定义 §9）；05 旧发布策略残留清理；本项目复审视发现的其他矛盾项已修。

## v3.4（2026-09-04）—— 扩展点装配硬约束：Spring @Conditional + application.yml 声明注释

1. **硬约束定案**：所有为后续版本预留的扩展点，一律以 Spring `@Conditional` 系列注解（`@ConditionalOnProperty` / `@ConditionalOnClass` / `@ConditionalOnBean`）实现按需装配；禁止改造既有类 / 运行时 if-else 硬编码分支实现扩展；
2. **配置规范**：含扩展点的模块必须在 application.yml（含 dev/prod）声明开关并注释（默认值/可选值/启用影响/关联文档）；配置键规范 `codecycle.<module>.<feature>.enabled`（开关）与 `.provider`（实现选择）；未启用 Bean 不装配，主链路零依赖；
3. **扩展点清单落库**：模块 03（Git 数据通道 rest/graphql、代码托管方 GitHub/GitLab/Gitea）、模块 04（Parser、静态分析工具、Commit 事件源、项目评估产出）、模块 07（VectorStore、关键词检索、Rerank、Query Classification）各建"扩展点与配置开关"表并挂 P0 约束；
4. 规范主文在项目定义 §3.6，其余模块未来新增扩展功能时同样遵循并补清单。

## v3.3（2026-09-04）—— 异步方案复审 + 未定决策清零

1. **异步任务是否引入 MQ 复审定案：不引入 Kafka / RabbitMQ**，维持 PostgreSQL 任务表 + Scheduler（发布-订阅语义 = 事件落表 + 调度消费；LISTEN/NOTIFY 可选唤醒）。评估记录写入项目定义 §14.4.1：任务语义/去重合并/分支顺序/事务一致性/进度可查由任务表原生满足，Kafka 严重过剩、RabbitMQ 需外置 outbox 与去重；升级触发条件（多实例抢单瓶颈、事件扇出、>100 msg/s、事件回放）出现前不引入；届时任务负载选 RabbitMQ、事件流选 Kafka；
2. **生产 JWT 算法定案（原"建议，待确认"）**：V1 统一 HS256（256-bit 随机密钥，环境变量/密文注入，支持轮换）；RS256 密钥对升级列为 V2（Sa-Token 默认 JWT 为 HS256，RSA 需自定义签名器，V1 不引入适配成本）；
3. **静态分析 V1 范围定案**：JavaParser 结构规则 + Checkstyle 基础规则集为毕设必做；PMD / SpotBugs 列为后续，不再标注"建议/可选"造成范围模糊；
4. **Commit 事件源定案**：V1 分支 Commit 轮询默认 60s（Nacos 可配置）触发发布；GitHub App Webhook 实时触发保持 V2；
5. **人工审核入口定案**：规则型 / SKILL / 高风险候选（含单 Finding）可由用户从 Web 提前转入 PENDING_REVIEW，不依赖 SUPPORTED 证据门槛；自动发布仍需 SUPPORTED（≥3 Finding、≥2 Module、conf≥0.8，可配置）；
6. **未定决策清零**：全量复核 8 个模块 + 项目定义决策记录表，唯一"待确认"行（生产 JWT 算法）已定案；其余"再评估"均为 V2+ 预留触发点，非 V1 阻塞项。

## v3.2（2026-09-02）—— 终版架构统一：MCP 校验兜底 + 知识发布策略收敛（8 项 P0）

1. **P0-1**：08 删除 MCP 相关 JWT 残留（type=mcp / API-key 表述），统一为单一高熵 **MCP Token**（`ccmcp_` + SHA-256 存储），全文不再使用 Credential/Access Token/API-key 等别名；
2. **P0-2**：新增校验兜底——Gateway Redis Miss 时调用 **Auth 内部 Token Introspection**（`POST /internal/auth/mcp/introspect`）查询 PostgreSQL，有效则回填 Redis（TTL=剩余时间）继续请求；Redis 定位明确为加速层而非事实源；
3. **P0-3**：Candidate 状态收敛为 **CANDIDATE / SUPPORTED / MERGED / REJECTED**（不含 PUBLISHED）；Published Topic 状态（PUBLISHED/SUPERSEDED/OUTDATED）独立于 knowledge.status；knowledge_candidate.knowledge_id 在 Consolidation/Publication 后回填归属 Topic；
4. **P0-4**：发布策略全文档统一——规则型（SECURITY/ARCHITECTURE/METRIC）一律 PENDING_REVIEW 人工；经验型低风险（conf≥0.8）自动；GENERATION_TEMPLATE 低风险自动；SKILL 默认人工（影响 Agent 行为）；任何 CRITICAL 强制人工；SUPPORTED 证据门槛默认 ≥3 Finding、≥2 Module、conf≥0.8（可配置）；
5. **P0-5**：07 的 "Query Understanding" 更名为 **Query Classification**，明确 V1 只做关键词 / typeHint 确定性规则匹配，不调用 LLM（LLM 意图识别移至 V2）；
6. **P0-6**：知识 payload 改为**按具体类型专用字段**（SECURITY=rule/constraint/scope；ARCHITECTURE 另含 decision；METRIC 另含 metric/threshold/condition；BUG= cause/pattern/avoidance/applicable；PERFORMANCE= cause/pattern/strategy/applicable；TEMPLATE= template/constraints/validation/applicable；SKILL= goal/prerequisites/steps/checklist/validation），不再使用跨类型公共字段集合；
7. **P0-7**：MCP 续期窗口化——仅当剩余 TTL ≤ 7 天才 SET expiresAt=now+30d，窗口内只验证不续期，消除高并发下每次请求无谓 EXPIRE/SET；
8. **P0-8**：MCP Token 术语统一（Token/Credential/Access Token/JWT/API-key 别名清理），首次定义 = 高熵可撤销 API Key；JWT 仅保留用于 Web 登录态与 GitHub App 签名，不再与 MCP 混用；
9. 核心关系定案：Candidate/Published 向量集合物理逻辑隔离（candidate_vectors 仅 Consolidation，knowledge_vectors 仅 RAG/MCP）；Profile 动态生效（改项目权限无需重发 Token）；MCP Service 只路由不管理 Token。

## v3.1（2026-09-02）—— 知识蒸馏定案并入文档（Java 计算引擎 + 状态机 + 并发控制）

**《知识蒸馏.txt》作为设计输入归档**，其中 Python / Tree-sitter / FastAPI 方案被 Java 方案取代，以模块 04/06 与项目定义 §9 为准：

1. **计算引擎全 Java**：JavaParser（AST/符号/关系）+ Spring AI（LLM Structured Output）+ pgvector；不引入 Python/FastAPI/Tree-sitter 服务；未来转型 C/S 或本地化项目再评估直接引入成熟开源引擎（如 code-review-graph）；
2. **五层 Evidence 硬预算**：P0 Finding / P1 Diff / P2 Local / P3 Impact / P4 Project；文件≤10、片段≤20、节点≤50、深度≤2、Token≤12000，超限按 P0>P1>P2>P3>P4 截断；确定性 Token 压缩（非 LLM 摘要）；
3. **两次 LLM 调用**：Extraction（Finding → Candidate，JSON 结构化，去项目特有命名）+ Consolidation（仅四种决策：NEW / MERGE / REVISION / DUPLICATE）；Candidate 生成后**立即向量化（一步到位）**入独立候选集合；
4. **状态机按附件定案**：OBSERVED → CANDIDATE → SUPPORTED → PUBLISHED → SUPERSEDED（+ PENDING_REVIEW / OUTDATED 分支）；
5. **知识分族字段定案（Q10）**：规则型（SECURITY/ARCHITECTURE/METRIC）→ rule/constraint/threshold/scope；经验型（BUG/PERFORMANCE）→ cause/pattern/avoidance/applicable；生成型（TEMPLATE/SKILL）→ steps/template/validation；
6. **发布门槛（Q6）**：规则型一律暂存 + 用户审核（标注风险，不自动进入正式知识库）；经验/生成低风险自动发布（confidence≥0.8、≥3 独立 Finding、≥2 模块、无 SECURITY_CRITICAL）；
7. **边置信度（Q9）**：Code Graph 关系增加 EXTRACTED / INFERRED / AMBIGUOUS 三档，用于 Context 排序与裁剪；
8. **图存储压力评估**：V1 不上图数据库，PostgreSQL 关系表 + 复合索引 + 有界遍历承载（详见模块 04 §6.1.1）；
9. **发布-订阅 + 并发（Q8/Q11）**：任务表 + Scheduler 实现发布-订阅（无 MQ）；Commit 事件按分支合并 + 时间窗口（90s，MAX_WAIT 5min）+ 分支级串行 + 全局并发上限（默认 2）+ 失败退避后范围追赶；
10. 知识蒸馏.txt 历史方案（Python 双语言、FastAPI 接口、Tree-sitter）不再作为实现依据。

## v3.0.5（2026-09-02）—— 知识蒸馏管线定型

**CodeCycle 不对整个代码库做全局知识蒸馏**，以已确认 Review Finding 为最小知识来源：

1. **四阶段流水线**：Evidence Preparation（限界上下文，不扫描全库）→ Candidate Extraction（Finding 粒度）→ Consolidation（pgvector 相似召回 + LLM 归并/抽象/版本演化）→ Publication（风险控制 + 审核）；
2. **Review 与蒸馏解耦**：蒸馏为独立异步任务，不占用 Review 调用，避免单次 LLM 调用承担过多任务；
3. **限界上下文**：只读取 Finding 相关证据（Diff ≤ 10KB、代码片段 ≤ 20KB、调用链、Commit 元数据、项目规则片段）；
4. **Candidate 与 Published 分库/分向量集合**：未验证知识不进入 RAG/MCP 检索路径；
5. **知识类型分族**：规则型（SECURITY/ARCHITECTURE/METRIC）· 经验型（BUG_AVOIDANCE/PERFORMANCE）· 生成型（TEMPLATE/SKILL），对应不同提炼策略与检索优先级；
6. **动态置信度**：confidence = f(evidence_count, commit_count, module_count, Finding 确认率)；
7. **来源追溯（Provenance）**：每条知识保留源 Finding/Commit/证据计数，finding_id / candidate_id / knowledge_id 全程关联；
8. **候选粒度定案**：Candidate=Finding 粒度（最小生成单元），Published=知识主题粒度（一条知识=一个可复用规则/策略/模板，匹配 MCP 按主题检索的消费方式）；
9. 借鉴点纳入：两级索引（短索引 + 按需详情，对应 RAG 精炼 + get_knowledge）、规则引擎 + LLM 双层（静态分析 + 提炼）、候选审查模式（PENDING_REVIEW）、多源并行提取列为 V2 评估。

## v3.0.4（2026-09-02）—— MCP Token 简化：单一高熵 Token + 滑动过期

考虑到签发/续期复杂度，**废弃 v3.0.3 的双层凭证方案**，改为：

1. **生成规则**：Snowflake 管 ID（业务主键），SecureRandom 管密钥（256-bit → Base64URL → 前缀 `ccmcp_`）；不可预测，攻击者无法枚举 User ID/时间戳伪造；
2. **存储**：PostgreSQL（事实源）与 Redis（运行时状态 `mcp:token:{sha256(token)}`）**仅存 SHA-256 哈希**，绝不存 Token 原文；Redis TTL = expiresAt 剩余（自动淘汰）；
3. **滑动过期**：有效期固定 30 天，每次 Gateway 验证通过自动重置 `now+30d`；连续 30 天未使用自动失效；
4. **请求验证**：Gateway 计算 SHA-256 → Redis GET → status/expiresAt 校验 → 滑动续期 SET → 取 profileId 路由；
5. **管理操作**：创建（明文一次）/ 撤销（REVOKED 立即全局失效）/ 重新生成 / 列表（别名、最后使用、过期）；
6. **移除冗余**：双层 Credential/Access Token、OAuth 授权码/PKCE、Token Version、分布式 Refresh Lock、TTL 抖动、Gateway 本地缓存（refresh 场景）；
7. **Redis 引入说明**：Redis 仅限 MCP Token 运行时状态专用，不作为通用缓存；部署依赖新增 Redis（VM Docker）；
8. 数据表回到 `mcp_tokens`（Snowflake id、token_hash、status、expires_at、last_used_at、alias）。

## v3.0.3（2026-09-02）—— MCP Token 两层凭证续期设计

**MCP 认证改为两层凭证模型（所有令牌由 Sa-Token 生成/签发）**：

1. **MCP Credential**（长期，默认 1 年，Sa-Token 生成，SHA-256 哈希存储）：资格证明，与 Profile 绑定、设备级隔离、可单独撤销/轮换；明文仅展示一次；
2. **MCP Access Token**（短期，Sa-Token JWT 无状态，60min ± 5min 随机抖动）：实际通行证，Claims 含 credentialId/profileId/userId/tokenVersion/exp，不落库；
3. **续期**：客户端主动（401 触发或 5min 窗口提前）携 Credential 调 `POST /api/auth/mcp/refresh`，服务端不静默替换；刷新并发用 PG advisory lock 归并 + 随机 TTL 抖动，败者复用新 Token；
4. **撤销**：Token Version（version+1）即时失效，覆盖 Credential 撤销/到期、Profile 禁用、用户禁用，不维护黑名单；
5. **存储适配**：原方案运行时状态用 Redis（token 状态/刷新锁/本地缓存缓冲），本项目 v3.0 基线不引入缓存中间件，全部用 PostgreSQL（advisory lock + 行内回写 + 本地缓存 TTL≤60s）等价实现；Gateway 只验签，版本/状态校验在 MCP Service；
6. **校验路径**：Gateway Sa-Token 无状态验签（签名+过期，零存储访问）→ MCP Service 校验 version/status（PG + 本地缓存）→ Profile → RAG；
7. 数据表 `mcp_tokens` 改为 `mcp_credentials`（含 version、current_access_token 等字段）；客户端接入两档（短期粘贴 Access Token / 长效 Credential + refresh 脚本），MCP OAuth 化（Authorization spec）列为 V2 评估。

## v3.0.2（2026-09-02）—— 认证鉴权框架替换

**业务层认证鉴权统一替换为 Sa-Token，不采用 Spring Security**：

1. 技术选型：Sa-Token v1.46.0（`sa-token-spring-boot4-starter` 业务服务 + `sa-token-reactor-spring-boot4-starter` Gateway + `sa-token-jwt`）；
2. 无状态 JWT 模式：`StpLogicJwtForStateless`，Gateway 与业务服务共享 `jwt-secret-key` 验签，不引入服务端会话存储；
3. Access / MCP Token 仅由 Auth 用 Sa-Token 签发；Refresh Token 保留（DB 哈希 + 轮换）；
4. 业务服务鉴权：`StpUtil.checkLogin()` / `@SaCheckPermission`；项目角色权限由 `StpInterface` 动态加载（OWNER/MEMBER/VIEWER 权限码）；
5. GitHub OAuth 客户端改为**手动实现**（authorize/callback/state/PKCE），不使用 Spring Security OAuth2 Client；
6. 旧表述归档：`Spring Security`、`Spring Security OAuth2 Client`、`Security 7` 从正文移除（代码审查类型的 SECURITY / SECURITY_RULE 不受影响）。

## v3.0.1（2026-09-01）—— 工程细节校准

**P0（阻塞开发，已修）**

1. 仓库列表改用 `GET /installation/repositories`（App 安装覆盖范围），删除 `/user/repos` 及"私有仓库需 repo scope"表述；
2. 限流文案修正：Installation Token 限流按安装粒度独立计算（基础 5000/h，随仓库/用户数上浮），平台实时读取 `X-RateLimit-*` 响应头并纳入调度，禁止硬编码固定配额；
3. Installation Token 创建强制 `repository_ids=[当前项目 Repo ID]`，Token 仅能读取当前仓库，禁止宽 Token 覆盖整个 Installation。

**P1（工程健壮性，已纳入基线）**

1. Contributors 仅用于成员发现/候选邀请，删除 GitHub 权限→项目角色映射，角色由 Project Service 独立管理；
2. V1 业务约束写死 1 项目 = 1 仓库（数据模型保留 1:N 预留）；
3. Graph State 由 (project_id, repository_id, branch_id) 三元组唯一确定，分支间严格隔离；
4. Commit 父字段改为 parents[]；Merge Commit 标记 MERGE_COMMIT，不增量 Review；
5. 变更语义明确：ADD / MODIFY / RENAME / DELETE（DELETE 逻辑删除，valid_until 标记）；
6. 自定义 Context 改为 Hard Include（强制包含，不参与优先级排序）；
7. Review Plan 拆分 hardRules（必须遵守）与 suggestedChecks（AI 建议）；
8. Finding Status（OPEN/CONFIRMED/REJECTED/IGNORED）与 Source（LLM/STATIC_ANALYSIS/RULE）解耦；
9. 知识演进链路：finding → knowledge_candidate → knowledge（三表关联溯源）；
10. V1 语言边界：仅 Java 语言级图谱，其余语言作项目上下文元数据；
11. 单 Commit Review 用 commits/{sha} files[]；Compare API 仅用于分支/Range；
12. "源码不落盘"改为工程化表述（禁止主动持久化至业务库/对象存储/普通日志；受控临时空间任务后清理）；
13. 文档拆分 Product Deployment 与 Development Environment（Windows + Docker VM + Tunnel 仅为作者本地开发方式）。

## v3.0（2026-09-01）—— 基线统一修订

**两链条定型**

- 生产链：GitHub → Analysis → Review → Knowledge
- 消费链：Knowledge → RAG → MCP → Agent

**部署边界定型（业务边界 ≠ 进程边界）**

- Gateway（独立）
- Auth（独立）
- Project + GitHub Integration（合并）
- Analysis + Review（合并）
- Knowledge + RAG（合并）
- MCP（独立）

**P0 硬约束 8 条、P1 设计细则 7 条**已写入各模块"架构约束 / 设计细则"章节。

**本次修订的关键变更（正文已生效）**

1. 认证与仓库访问分离：OAuth App 仅登录身份（`read:user user:email`，**不再申请 repo scope**）；仓库只读改由 **GitHub App** 提供（`Contents: Read` + `Metadata: Read`，按仓库粒度安装授权）。
2. 异步任务定型：**PostgreSQL 任务表 + Scheduler 轮询**（status、next_run_at、指数退避重试），**不引入消息队列**。
3. 技术栈收敛：Spring Boot 4 + Spring AI 2.x 为唯一主框架；LangChain4j 不作为核心依赖；服务间调用采用 Spring Framework 7 HTTP 接口；不引入 OpenFeign、Redis、RabbitMQ 及 Spring Cloud Alibaba 其余组件。
4. Code Graph 增加一致性锚点 `current_graph_commit`，禁止增量跳变。
5. 个人引用（Reference）不复制权限：项目权限失效后个人引用同步失效，永久保留需 Copy/Fork。
6. MCP Token 只走 HTTP Authorization Header，Tool 入参不含 Token；`get_knowledge` 必须同时校验知识归属范围。
7. 知识、Embedding、Finding、Context 均增加版本/优先级/锚点等设计细则（详见各模块）。

**废弃术语归档（正文不得再出现）**

`RabbitMQ`、`Redis`、`OpenFeign`、`Spring Cloud Alibaba`、`LangChain4j（核心依赖）`、`OAuth repo scope`、"物理部署单元"等旧表述，仅在本文档保留历史。

---

## v2.3（2026-09-01）

- 生产域名定为 `codecycle.me`（隧道 + HTTPS）；
- 模块 01 Gateway & Auth、模块 02 Project 完成详细设计。

## v2.2（2026-09-01）

- 服务拆分定型：逻辑 9 服务 → 物理 6 部署单元；
- 部署环境定型：Windows 宿主运行服务 + 虚拟机 Docker 运行中间件；
- 开发/生产双环境配置；隧道 + 域名 + HTTPS 方案。

## v2.1（2026-09-01）

- Java 21 LTS + Spring Boot 4.1.x + Spring AI 2.0.x 定型；
- 分布式组件（Gateway / Nacos / OpenFeign / LoadBalancer）定型 —— 注：OpenFeign 于 v3.0 废弃；
- 新增 SKILL 知识类型；Project Health 定为补充功能（C 方案）。

## v2.0（2026-09-01）

- 按对话收敛结果重写项目定义；
- 移除早期技术选型（MySQL、Milvus 等），明确两类知识空间、MCP 走 Gateway 鉴权、静态分析层。

## v1.0（历史草案）

- 早期讨论版本，含 MySQL 8、Milvus、Redis、RabbitMQ、Spring Cloud Alibaba、团队知识域、知识上传 GitHub、OAuth repo scope 等**已废弃结论**，仅作历史追溯。
