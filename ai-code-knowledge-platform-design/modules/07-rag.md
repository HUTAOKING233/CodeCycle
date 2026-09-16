# 07 RAG Service（检索与向量）

> 模块文档 · 基线 v3.6（2026-09-04，跟随 CHANGELOG）
> 依据《CodeCycle 项目定义 v3.6》编写
> 部署：与 Knowledge Service 合并为部署单元 ⑤ Knowledge/RAG（逻辑上独立）

---

## 1. 模块职责

RAG Service 是知识库的**检索层**：

- Embedding：知识入库向量化（与 Chat 模型解耦，用户自配）；
- 检索：Scope 过滤 →（关键词）+ 向量检索 → 候选集 → Rerank → Context Compression；
- 为 MCP、Web 知识问答、Review 知识上下文提供统一检索能力；
- 通过 Spring AI VectorStore 抽象保留未来切换 Milvus / Qdrant 的能力。

核心原则：

> 关系数据库负责"知识是什么"，向量负责"知识像不像"；检索层做第二层压缩（第一层压缩在知识提炼时完成），防止 Token 爆炸。

---

## 2. 为什么存在

- 知识检索是"工程经验反哺 Agent"的通道，MCP 与 Review 都需要；
- 检索必须带**权限与范围过滤**（Project / Personal / 混合），不能裸向量搜索；
- 两层压缩是控制 Token 与质量的关口；
- 与 Knowledge 分离逻辑职责：Knowledge 管资产，RAG 管检索；RAG 挂掉时知识管理仍正常。

---

## 3. 业务边界

| 属于本模块 | 不属于本模块 |
| --- | --- |
| Embedding 生成与管理 | 知识 CRUD / 生命周期（Knowledge） |
| 向量检索 + 关键词检索 | 知识提炼（Knowledge） |
| Rerank / Context Compression | MCP 协议适配（MCP） |
| 检索范围过滤（Profile / 权限） | 用户 / Token 签发（Auth） |
| 知识问答（RAG + LLM） | 代码分析（Code Analysis） |

---

## 4. 技术选型

### 4.1 结论

**PostgreSQL + pgvector**（与业务库同实例），V1 不引入 Milvus / Elasticsearch / Neo4j。

- 毕设知识规模（项目几十~几百条、个人长期几千条）pgvector 完全适用；
- HNSW 索引默认方案；
- 通过 Spring AI `VectorStore` 抽象保留扩展点。

### 4.2 存储方案（已定）

**采用方案 A：Spring AI PgVectorStore（官方）**

```text
- metadata（JSONB）：knowledge_id / scope / project_id / type / status / risk
- 权限过滤：metadata filter（等值 / IN，官方能力足够）
- 索引：HNSW（默认）
- 切换 Milvus / Qdrant：替换 VectorStore 实现，业务不变
```

- 选型依据：官方支持 HNSW + metadata filtering，适配毕设知识规模；本项目过滤条件均为等值/IN，官方能力足够；抽象切换成本最低；
- 方案 B（自建 knowledge_embeddings 表）保留为备选：仅在实现中发现 metadata 过滤性能不达标时启用（概率低，启用时记录决策）。

---

## 5. Embedding 体系

### 5.1 模型配置（用户自配，与 Chat 解耦）

```text
EMBEDDING_PROVIDER=ollama                # 冻结默认：Ollama 0.12.1（或 OpenAI-compatible API 备选）
EMBEDDING_MODEL=qwen3-embedding:0.6b     # 冻结默认模型（v3.6）
EMBEDDING_DIM=1024                       # 随模型而定
EMBEDDING_BASE_URL=http://<VM_IP>:11434  # 本地模型跑在 VM Docker
EMBEDDING_API_KEY=...                    # 云端 API 时必填
```

- 默认冻结**本地轻量开源模型**（Ollama 0.12.1 + Qwen3-Embedding-0.6B），代码不出本机；Ollama 稳定镜像 tag 在容器化接入时确认并回写冻结；
- Embedding 与 Chat Model 完全独立，互不绑定；
- 切换模型/维度需**全量重建 embedding**（提供重建任务）。

### 5.2 状态与重试

```text
PENDING → COMPLETED / FAILED
        →（来源/模型变更）→ OUTDATED → REBUILDING → COMPLETED
```

- 知识发布后入队 Embedding；
- 失败保留可重试状态（不阻塞知识查看/导出）；
- **知识已发布但 Embedding 未完成：Web 可见，MCP 暂不可检索**（硬约束）；
- 重建/更新任务批量执行（配合模型限流）。
- **候选向量化一步到位**：Candidate 在 Extraction 完成时立即向量化到独立候选集合 `candidate_vectors`（供 Consolidation 相似召回，模块 06 §6.2），不延迟到发布；Published 发布后入 `knowledge_vectors`。

---

## 6. 检索 Pipeline

### 6.1 第一版

```text
Query
 → Query Classification（关键词 / typeHint 规则匹配）
 → Scope Filter（Profile/权限，status=PUBLISHED）
 → Vector Search（TopK=20 候选）
 → Rerank（规则评分）
 → Context Compression（Top 3~8）
 → MCP / Web 问答 / Review
```

### 6.2 增强版（后续按效果引入）

```text
Query → Metadata Filter + Keyword（PostgreSQL 全文检索）+ Vector
      → Candidate Set → Rerank → Compression
```

---

## 7. Query Classification

V1 只做**确定性规则分类**，不调用 LLM（P0-5）：

```text
"报错 / 错误 / 失败 / 踩坑 / 以前遇到过" → 偏向 BUG_AVOIDANCE / PERFORMANCE_STRATEGY
"规范 / 规则 / 要求 / 怎么写"           → 偏向 ARCHITECTURE_RULE / SECURITY_RULE / METRIC_RULE
"步骤 / 怎么实现 / 流程 / 新增渠道"      → 偏向 SKILL / GENERATION_TEMPLATE
其他                                    → 混合检索
```

- 输出：`typeHint`（可空）+ `query`（原样）；纯规则匹配，零 LLM 调用；
- LLM 意图识别列为后续增强（V2），不计入 V1 检索环节。

---

## 8. Scope Filter（权限与范围）

```text
来源：
  MCP：profileId → mcp_profiles（project_ids + include_personal）
  Web：当前用户 + projectId + includePersonal
  Review：当前项目 + 项目规则 + 个人引用（可选）

过滤条件：
  status = PUBLISHED
  scope ∈（PROJECT 且 project_id ∈ 授权集）∪（PERSONAL 且 owner_user_id = 当前用户/被引用）
  type ∈ typeHint（若有）
```

- **跨用户隔离**：检索结果只含授权知识（项目成员 / 本人 / 本人引用的项目知识）；
- **个人知识默认不混合**：MCP Profile `include_personal` 与 Web `includePersonal` 默认均为 false，显式开启才纳入个人知识（模块 08 §7）；
- MCP Profile 决定范围，Token 只做身份（Profile 范围见模块 08 §7；Token 校验见模块 01 §7.5）；
- 引用知识（knowledge_references）在个人检索范围内一并返回。

---

## 9. Rerank

### 9.1 第一版（规则评分，不引入 Reranker 模型）

```text
keyword 关闭（V1 默认）：
  finalScore = 0.7 × vectorScore + 0.3 × priority        # 归一化，权重和 = 1
keyword 开启：
  finalScore = 0.5 × vectorScore
            + 0.3 × keywordScore
            + 0.2 × priority（knowledge 优先级/最近使用/occurrences 归一化）
```

### 9.2 增强（后续）

- 引入 Reranker 模型（如 bge-reranker）对 Top 20 精排；
- 或使用 RRF（Reciprocal Rank Fusion）合并多路结果。

---

## 10. Context Compression（第二层压缩）

```text
候选 Top20 → Rerank → Top 3~8 → 压缩：
  - 单条 Knowledge 截断（如 300~600 字）
  - 总 Context Token 上限（如 3000）
  - 重复/相似知识去重（保留高优先级）
  → 输出精炼结果
```

输出结构（供 MCP / Agent / 前端）：

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

> 第一层压缩在 Knowledge 提炼时完成（Review → 抽象知识）；第二层在检索时完成，两层共同防 Token 爆炸。

---

## 11. 接口设计

| 方法/路径 | 说明 | 鉴权 |
| --- | --- | --- |
| `POST /api/rag/search` | 语义检索（范围 + 过滤 + 分页） | 登录 |
| `POST /api/rag/ask` | 知识问答（RAG + ChatModel，建议实现） | 登录 |
| `GET /internal/rag/search?profileId=&query=` | MCP 检索入口 | 内部服务令牌（经 Gateway MCP Token 放行） |
| `POST /internal/rag/embed` | 触发知识 Embedding（knowledgeId[]） | 内部服务令牌 |
| `GET /internal/rag/knowledge/{id}/embedding-status` | 查询向量化状态 | 内部服务令牌 |
| `POST /internal/rag/reindex` | 全量重建 embedding（模型/维度变更时） | 内部服务令牌 |

---

## 12. 与其他服务关系

```text
Knowledge ──→ RAG（同部署单元：发布后入队 Embedding）
MCP ──→ RAG（经 Gateway；Profile → 范围过滤 → 检索）
Review ──→ RAG（检索项目规则/历史错误，可降级）
Web ──→ RAG（知识问答 / 搜索）
```

解耦验证：

```text
RAG 挂   → 知识管理（CRUD/审核/导出）正常；Review 降级（无知识上下文）；MCP 不可用
Knowledge 挂 → RAG 无法检索（依赖其元数据/权限）
Review 挂 → RAG 完全正常（MCP 知识查询不受影响）
```

---

## 13. 异常处理

| 场景 | 处理 |
| --- | --- |
| Embedding 模型不可用 | 任务 FAILED_RETRYABLE；知识保持可查看，仅不可检索 |
| 检索超时 | 返回空结果 + 提示"检索不可用"，不阻塞调用方 |
| 维度不匹配（模型切换） | 触发全量 reindex；旧向量标记无效 |
| 无授权知识 | 返回空集（不泄露存在性信息给未授权用户） |
| 查询过短/空 | 返回提示，不执行向量检索 |

---

## 14. 异步流程

```text
知识 PUBLISHED → 入队 Embedding 任务（knowledge_tasks，类型 EMBEDDING，见项目定义 §14.4）
 → 调 Embedding 模型 → 写 pgvector（metadata 关联 knowledge_id）
 → embedding_status=COMPLETED / FAILED（可重试）/ OUTDATED / REBUILDING
模型/维度变更 → reindex 任务（分批执行，节流）

Candidate Extraction 完成 → 立即向量化写入 candidate_vectors（独立集合，见模块 06 §6.2）
```

---

## 15. AI 调用点

1. **Embedding 模型**（必用，轻量开源模型）；
2. **知识问答 ask**（建议实现，RAG + ChatModel，走 Spring AI）；
3. Reranker 模型（后续增强）。

> Query Classification 为纯规则实现（关键词 / typeHint 匹配），不是 AI 调用点。

约束：

- Embedding 与 Chat 独立配置；
- ask 问答消耗计入用户模型配额（用户自配模型）；
- 检索结果必须经 Scope Filter，禁止把未授权知识喂给模型。

---

## 16. 权限要求

- 检索范围由 Profile / 用户上下文决定，**服务端强制过滤**（前端不可传任意 projectId 越权）；
- `/internal/rag/**` 仅服务网络可达；
- MCP 检索只认 MCP Profile 范围，与登录用户范围互不混用。

---

## 17. 技术实现建议

- Spring AI：`EmbeddingModel` + `VectorStore`（PgVectorStore，HNSW，metadata filter）；
- pgvector：`CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)`；metadata 字段建 GIN/复合索引；
- 全文检索：PostgreSQL `tsvector`（title/content），增强版启用；
- Token 计算：入库时记录 content 的 token 估算，压缩时按预算截断；
- 批量 Embedding：分批 + 节流，避免打爆模型与 GitHub 无关（模型自身限流）。

---

## 架构约束（P0）

1. **检索与详情必须校验归属范围**：`get_knowledge(id)` 等价于 `WHERE knowledge.id = ? AND knowledge ∈ profile_scope`，禁止仅按 id 直查（越权风险）；
2. **Scope Filter 服务端强制**：范围由 Profile / 用户上下文决定，前端/Agent 不可传任意 projectId 越权；
3. **Embedding 未完成不可检索**：知识 PUBLISHED 但 embedding_status 非 COMPLETED 时，Web 可见、MCP 暂不可检索；
4. **异步任务统一走 PostgreSQL 任务表 + Scheduler**，不引入消息队列；
5. **不保存源码**：向量库只存知识 embedding 与元数据，禁止源码向量化（持久化边界见项目定义 §3.4）。
6. **扩展点装配（Spring @Conditional 硬约束，v3.4）**：本模块扩展点（VectorStore/关键词检索/Rerank/Query Classification）一律通过 `@ConditionalOnProperty` 按需装配，未启用不加载；application.yml 声明开关并附注释（默认值/可选值/启用影响），禁止 if-else 硬编码扩展；规范见项目定义 §3.6。

## 设计细则（P1 必检项）

- Embedding 生命周期：PENDING → COMPLETED / FAILED / OUTDATED / REBUILDING；模型/维度变更触发 reindex，旧向量先标 OUTDATED；
- 两层压缩：知识提炼时第一层抽象压缩；检索时第二层（Top 3~8、单条长度、总 Token、去重）；
- 第一版 Rerank 用规则评分：keyword 关闭（V1 默认）按 0.7×向量 + 0.3×优先级归一化；开启时 0.5/0.3/0.2；不强制 Reranker 模型；
- 检索结果必须服务端强制 Scope 过滤，未授权知识不得进入模型上下文。
- **Candidate 与 Published 分向量集合**：知识蒸馏的相似候选召回（模块 06 §6.2 阶段三）在独立候选集合执行；RAG/MCP 只检索 Published 集合，未验证知识不可见。

### 扩展点与配置开关（v3.4）

| 扩展点 | V1 实现（默认） | 未来扩展 | 配置键（application.yml） | 默认值 |
| --- | --- | --- | --- | --- |
| VectorStore | Spring AI PgVectorStore（pgvector，HNSW） | Milvus / Qdrant（替换 VectorStore 实现） | `codecycle.rag.vectorstore` | pgvector |
| 关键词检索 | 关闭（V1 仅向量） | PostgreSQL 全文检索（tsvector） | `codecycle.rag.keyword-enabled` | false |
| Rerank 模式 | 规则评分（keyword 关闭默认 0.7×向量 + 0.3×优先级） | Reranker 模型 | `codecycle.rag.reranker` | rules |
| Query Classification | 规则匹配（关键词 / typeHint，零 LLM） | LLM 意图识别（V2） | `codecycle.rag.query-classification.llm-enabled` | false |

- 开关必须带注释：默认值、可选值、启用影响（如 vectorstore=milvus 时装配 Milvus VectorStore Bean，PgVectorStore Bean 不加载）；
- 未启用实现不装配，主链路（pgvector + 规则评分）零额外依赖；维度/模型变更触发 reindex（见 §5.2）。

## 18. 实现优先级

### 【毕设必须】

- Embedding（知识发布入队 + 状态 + 重试）
- Scope Filter（Project/Personal/混合）
- Vector Search（TopK 候选）+ metadata 过滤（status/type）
- Context Compression（TopK、单条长度、总 Token、去重）
- 规则 Rerank（keyword 关闭：0.7/0.3；keyword 开启：0.5/0.3/0.2）
- 检索结果结构化输出（MCP 兼容）

### 【建议实现】

- `ask` 知识问答
- PostgreSQL 全文检索（关键词）
- 引用知识纳入个人检索范围

### 【可选扩展】

- Query Classification 增强（LLM 意图识别，V2）
- reindex 任务
- 检索使用统计

### 【暂不实现】

- Reranker 模型
- Milvus / Qdrant 实际部署（仅抽象预留）
- Graph RAG

---

## 19. 后续扩展

1. **Hybrid Search 完整版**：Keyword + Vector + RRF + Reranker；
2. **Milvus / Qdrant 切换**：规模增长后替换 VectorStore 实现；
3. **Graph RAG**：沿 knowledge_relations 扩展检索（V2 评估）；
4. **稀疏向量**：代码术语精确匹配增强。

---

## 20. 决策记录

| 决策 | 结论 | 状态 |
| --- | --- | --- |
| 向量库 | PostgreSQL + pgvector（HNSW），不引入 Milvus/ES/Neo4j | 已定 |
| 存储方案 | Spring AI PgVectorStore + metadata 过滤（HNSW）；自建表为备选 | 已定 |
| Embedding 模型 | 用户自配，默认本地轻量开源（Qwen3-Embedding-0.6B 方向） | 已定 |
| 第一版 Pipeline | Scope Filter → Vector Search → 规则 Rerank → Compression | 已定 |
| Rerank | keyword 关闭时 0.7/0.3；keyword 开启时 0.5/0.3/0.2；不强制 Reranker 模型 | 已定 |
| 压缩 | 两层：提炼时抽象 + 检索时 TopK/长度/Token/去重 | 已定 |
| 权限 | 服务端强制 Scope Filter，Profile/用户上下文决定范围 | 已定 |
| 任务归属 | Embedding / Reindex 任务落 knowledge_tasks（EMBEDDING / REINDEX），与分析任务共用调度语义 | 已定 |
