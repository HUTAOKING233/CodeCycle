# CodeCycle 数据库设计（基线 v3.6 · DB 全局约定 v3.5.1）

> 存储方式已定：PostgreSQL（业务事实源）+ pgvector（检索索引）；Redis 仅 MCP Token 运行时状态（不落业务事实）。
> 全局约定 v3.5.1 定案（5 Schema / 跨域无 FK / 三类删除策略 / 时间与乐观锁 / 历史不可删除 / 枚举终态），变更记录见 CHANGELOG。
> 本文档为全库表设计总纲；字段级权威来源为各模块文档（01~08）与本文件。

## 1. 全局约定（已定案）

```text
1. 逻辑分库（PostgreSQL schema，锁定 5 个）：
   auth / project / analysis / review / knowledge
   单数据库部署，业务域级逻辑隔离；Knowledge + RAG 同部署单元，向量表归 knowledge schema。
2. 命名与主键：
   - 表/字段 snake_case；业务表主键一律 id BIGINT（Snowflake，应用层统一 IdGenerator 生成）；
   - Snowflake 只负责生成业务主键，不表达任何业务语义（禁止 1=Auth 这类含义）；
   - Java 层统一 Long；UUID 不作为业务主键，如需对外标识用 external_id / public_id / request_id。
3. 时间字段统一规范：
   - created_at TIMESTAMPTZ NOT NULL、updated_at TIMESTAMPTZ NOT NULL；
   - 全部 UTC 存储，展示层再转换时区；
   - 不可变历史表只保留 created_at，不加 updated_at（commits / commit_changes /
     analysis_snapshots / knowledge_versions 等）。
   （本文档表结构示意图中未显式标注类型的 created_at / updated_at 均默认 TIMESTAMPTZ NOT NULL。）
4. 外键策略：
   - 同 schema 建物理 FK；跨 schema（跨域）只存目标 id + 索引，不建物理 FK；
   - 数据一致性由应用层与任务编排保证（V1 锁定该方案）。
5. 删除策略（三类，替代"全部逻辑删除"）：
   ① 逻辑删除：可撤销的业务实体——projects、project_members、knowledge 等（status + archived_at/removed_at）；
   ② 状态失效：生命周期实体——mcp_tokens（REVOKED/EXPIRED）、refresh_tokens（ROTATED/REVOKED）等；
   ③ 不删除：历史事实——commits、reviews、review_findings、knowledge_versions、
     analysis_snapshots、code_symbols/code_relations（valid_until 演进，不物理删）。
6. 枚举：VARCHAR + CHECK，但 CHECK 只用于真正的枚举字段，
   不给 title/description/content 等自由文本加约束；Java Enum 与 DB CHECK 双层保护；
   枚举 CHECK 需覆盖终态与合法转移（如任务终态禁止回 RUNNING）。
7. JSONB 使用范围：知识 payload、review_plan、evidence、stats、source、project_ids、
   任务扩展信息、检索 metadata；不用于高频过滤字段。
8. 乐观锁：允许并发编辑的业务表增加 version BIGINT NOT NULL DEFAULT 0
   （projects / knowledge / mcp_profiles 等）；
   不可变历史表不加 version（commits / commit_changes / knowledge_versions 等）。
   —— 例外：knowledge 已有业务版本列 version（当前知识版本，配合 knowledge_versions），
   乐观锁列命名 row_version BIGINT NOT NULL DEFAULT 0，避免与业务版本语义冲突。
9. 任务表：每域一表（analysis_tasks / knowledge_tasks），字段约定一致；
   status：QUEUED / RUNNING / COMPLETED / FAILED / FAILED_RETRYABLE / CANCELLED；
   COMPLETED / FAILED / CANCELLED 为终态，禁止终态 → RUNNING；
   FAILED_RETRYABLE → QUEUED（退避重试，次数上限）；
   QUEUED 部分唯一索引 = 按 (project, repository, branch, type) 去重合并。
10. 向量：candidate_vectors 与 knowledge_vectors 必须为独立物理表/独立 Collection，
    不能只靠 status 字段区分；由 Spring AI PgVectorStore 托管，
    不手工修改其内部表结构，metadata 扩展通过 Spring AI 机制配置。
11. 历史事实不可删除：Commit / Review / Finding / Version / Snapshot 为审计记录，
    不提供物理删除或逻辑删除操作。
```

## 2. Schema 与表清单

| Schema | 表 | 说明 |
| --- | --- | --- |
| auth | users | 平台用户（邮箱登录预留） |
| auth | oauth_accounts | GitHub OAuth 身份绑定（access/refresh 加密存储） |
| auth | refresh_tokens | 平台会话 Refresh（哈希 + 轮换/重用检测；状态失效） |
| auth | github_app_keys | GitHub App 凭证（私钥加密，全局一份） |
| auth | github_app_installations | 用户安装 GitHub App 记录（状态失效） |
| auth | mcp_tokens | MCP Token（只存 SHA-256；状态失效） |
| project | projects / github_repositories / project_repositories / project_members / project_branches | 项目与仓库/成员/分支（projects/members 逻辑删除） |
| project | assessment_scores | 整体评估评分（V1 预留结构） |
| analysis | analysis_tasks / analysis_webhook_events / commits / commit_changes / code_symbols / code_relations / analysis_snapshots | 任务、Webhook 去重回执 + 代码图（历史事实不可删除） |
| review | reviews / review_findings | Review 与 Finding（历史事实不可删除） |
| knowledge | knowledge / knowledge_candidate / knowledge_tasks / knowledge_versions / knowledge_relations / knowledge_references / mcp_profiles | 知识资产与生命周期 |
| knowledge | candidate_vectors / knowledge_vectors | pgvector 双集合（物理隔离） |

## 3. auth schema

### users

```text
id            BIGINT PK            -- Snowflake（无业务语义）
email         VARCHAR(255) NULL UNIQUE  -- 邮箱登录预留（V1 可为空）
display_name  VARCHAR(100)
status        VARCHAR(20)          -- ACTIVE / DISABLED（状态失效）
role          VARCHAR(20)          -- USER / ADMIN（ADMIN 预留）
last_login_at TIMESTAMP NULL
created_at TIMESTAMPTZ NOT NULL / updated_at TIMESTAMPTZ NOT NULL
```

### oauth_accounts

```text
id                    BIGINT PK
user_id               BIGINT FK → auth.users.id
provider              VARCHAR(20)       -- GITHUB（V1 唯一）
provider_user_id      VARCHAR(64)       -- GitHub 数字 ID
login                 VARCHAR(100)
name                  VARCHAR(100) NULL
avatar_url            VARCHAR(512) NULL
email                 VARCHAR(255) NULL
access_token_encrypted  TEXT           -- GitHub OAuth 身份令牌（AES-256-GCM 加密）
refresh_token_encrypted TEXT           -- GitHub refresh（6 个月）
token_scopes          JSONB            -- ["read:user","user:email"]
token_status          VARCHAR(20)      -- ACTIVE / EXPIRED / REVOKED
last_synced_at        TIMESTAMP NULL
created_at / updated_at
UNIQUE (provider, provider_user_id)
```

### refresh_tokens（平台会话；状态失效策略）

```text
id            BIGINT PK
user_id       BIGINT FK → auth.users.id
token_hash    CHAR(64) UNIQUE        -- SHA-256(refresh_token)
status        VARCHAR(20)            -- ACTIVE / ROTATED / REVOKED（后两者终态）
expires_at    TIMESTAMP              -- 7 天（可配置）
rotated_from  BIGINT NULL            -- 轮换来源（重用检测）
revoked_at    TIMESTAMP NULL
created_at    TIMESTAMP
INDEX (user_id, status)
```

### github_app_keys（全局一份）

```text
id                    BIGINT PK
app_id                VARCHAR(100)
client_id             VARCHAR(100)
private_key_encrypted TEXT           -- PEM（AES-256-GCM 加密）
created_at / updated_at
```

### github_app_installations（状态失效策略）

```text
id               BIGINT PK
user_id          BIGINT FK → auth.users.id
installation_id  BIGINT UNIQUE
account_login    VARCHAR(100)
account_type     VARCHAR(20)          -- USER / ORG
repo_selection   VARCHAR(20)          -- ALL / SELECTED
status           VARCHAR(20)          -- ACTIVE / UNINSTALLED
created_at / updated_at / uninstalled_at NULL
INDEX (user_id, status)
```

### mcp_tokens（生命周期实体：状态失效，不物理删除）

```text
id           BIGINT PK               -- Snowflake（业务主键，无语义）
user_id      BIGINT FK → auth.users.id
profile_id   BIGINT                  -- knowledge.mcp_profiles.id（跨域，不建 FK，建索引）
token_hash   CHAR(64) UNIQUE         -- SHA-256(token)；原文 ccmcp_ 仅展示一次
status       VARCHAR(20)             -- ACTIVE / REVOKED / EXPIRED（后两者终态）
expires_at   TIMESTAMP               -- 30 天窗口化滑动（剩余 ≤ 7 天才续期）
last_used_at TIMESTAMP NULL
alias        VARCHAR(100) NULL
created_at / revoked_at NULL
INDEX (user_id, status), INDEX (profile_id)
```

## 4. project schema

### projects（逻辑删除 + 乐观锁）

```text
id                  BIGINT PK
name                VARCHAR(100)
description         VARCHAR(500) NULL
owner_user_id       BIGINT FK → auth.users.id   -- 跨域引用（不建 FK，见约定 4）
status              VARCHAR(20)      -- ACTIVE / ARCHIVED（ARCHIVED 为逻辑删除态）
initial_analysis    VARCHAR(20)      -- NOT_STARTED / QUEUED / RUNNING / COMPLETED / FAILED
last_assessment_at  TIMESTAMP NULL
version             BIGINT NOT NULL DEFAULT 0   -- 乐观锁
created_at / updated_at / archived_at NULL
INDEX (owner_user_id, status)
```

### github_repositories（GitHub 元数据镜像）

```text
id              BIGINT PK
owner           VARCHAR(100)
name            VARCHAR(100)
full_name       VARCHAR(200) UNIQUE
private         BOOLEAN
default_branch  VARCHAR(100)
description     VARCHAR(500) NULL
html_url        VARCHAR(512)
pushed_at       TIMESTAMP NULL
status          VARCHAR(20)         -- OK / UNREACHABLE
last_synced_at  TIMESTAMP NULL
created_at / updated_at
```

### project_repositories（V1 业务约束：1 项目 = 1 仓库，结构保留 1:N）

```text
id            BIGINT PK
project_id    BIGINT FK → projects.id
repository_id BIGINT FK → github_repositories.id
role          VARCHAR(20)   -- MAIN（V1 仅 MAIN）
created_at    TIMESTAMP
UNIQUE (project_id, repository_id)
```

### project_members（逻辑删除：REMOVED）

```text
id             BIGINT PK
project_id     BIGINT FK → projects.id
user_id        BIGINT NULL FK → auth.users.id   -- 未绑定用户为空（占位）
github_user_id VARCHAR(64) NOT NULL
login          VARCHAR(100)
role           VARCHAR(20)           -- OWNER / MEMBER / VIEWER
source         VARCHAR(20)           -- CREATOR / GITHUB_CONTRIBUTOR / MANUAL
status         VARCHAR(20)           -- ACTIVE / PENDING_BIND / REMOVED
joined_at      TIMESTAMP
created_at / updated_at / removed_at NULL
UNIQUE (project_id, github_user_id)
```

### project_branches

```text
id             BIGINT PK
project_id     BIGINT FK → projects.id
repository_id  BIGINT FK → github_repositories.id
name           VARCHAR(100)
head_sha       VARCHAR(64)
is_default     BOOLEAN
last_synced_at TIMESTAMP
created_at / updated_at
UNIQUE (repository_id, name)
```

### assessment_scores（V1 预留，C 方案；只追加不修改）

```text
id           BIGINT PK
project_id   BIGINT FK → projects.id
dimension    VARCHAR(30)   -- ARCHITECTURE / CODE_QUALITY / MAINTAINABILITY / SECURITY
score        NUMERIC(5,2)  -- 0-100
evaluated_at TIMESTAMP
created_at   TIMESTAMP
UNIQUE (project_id, dimension, evaluated_at)
```

## 5. analysis schema

### analysis_tasks（任务状态机含终态）

```text
id              BIGINT PK
project_id      BIGINT                 -- project.projects.id（跨域，建索引）
repository_id   BIGINT NULL
branch_id       BIGINT NULL            -- project.project_branches.id
type            VARCHAR(30)   -- PROJECT_INITIALIZATION / COMMIT_ANALYSIS / BRANCH_ANALYSIS / MODULE_ANALYSIS / PROJECT_ASSESSMENT
ref             VARCHAR(128)  -- 分支名 / commit sha / 模块路径
target_sha      VARCHAR(64) NULL       -- 窗口内滚动到最新
pending_sha     VARCHAR(64) NULL       -- RUNNING 期间到达，完成后接力
status          VARCHAR(20)   -- QUEUED / RUNNING / COMPLETED / FAILED / FAILED_RETRYABLE / CANCELLED
                              -- COMPLETED / FAILED / CANCELLED 终态；FAILED_RETRYABLE → QUEUED 退避重试
next_run_at     TIMESTAMP NULL
retries         INT DEFAULT 0
result_ref      VARCHAR(255) NULL      -- snapshot id / review id
error           TEXT NULL
created_at / started_at NULL / finished_at NULL / updated_at
UNIQUE (project_id, repository_id, branch_id, type) WHERE status = 'QUEUED'
INDEX (status, next_run_at)
```

### analysis_webhook_events（Webhook 去重回执：历史事实不可删除）

```text
id              BIGINT PK
delivery_id     VARCHAR(128) NOT NULL UNIQUE   -- GitHub X-GitHub-Delivery
event_type      VARCHAR(50)  NOT NULL          -- V1: push
repository_id   BIGINT NULL
commit_sha      VARCHAR(64) NULL
status          VARCHAR(20)  NOT NULL          -- RECEIVED / ACCEPTED / DUPLICATE / REJECTED / FAILED
error           TEXT NULL
received_at     TIMESTAMPTZ NOT NULL
processed_at    TIMESTAMPTZ NULL
created_at      TIMESTAMPTZ NOT NULL           -- 不可变
```

仅保存事件元数据和处理结果，不保存完整 Webhook 请求体；重复 `delivery_id` 返回 202 并标记 `DUPLICATE`，不得重复创建分析任务。

### commits（历史事实：不可删除，仅 created_at）

```text
id            BIGINT PK
project_id    BIGINT
repository_id BIGINT
sha           VARCHAR(64)
message       TEXT
author_name   VARCHAR(200)
author_email  VARCHAR(255)
authored_at   TIMESTAMP
parents       JSONB             -- TEXT[]（多父提交处理）
merge_flag    BOOLEAN DEFAULT false
stats         JSONB             -- 增删行/文件统计
synced_at     TIMESTAMP
created_at TIMESTAMPTZ NOT NULL   -- 不可变：无 updated_at、无 version
UNIQUE (repository_id, sha)
INDEX (project_id, authored_at)
```

### commit_changes（历史事实：不可删除，仅 created_at）

```text
id          BIGINT PK
commit_id   BIGINT FK → commits.id
file_path   VARCHAR(512)
status      VARCHAR(20)     -- ADD / MODIFY / RENAME / DELETE
additions   INT DEFAULT 0
deletions   INT DEFAULT 0
before_sha  VARCHAR(64) NULL
after_sha   VARCHAR(64) NULL
created_at TIMESTAMPTZ NOT NULL
UNIQUE (commit_id, file_path)
```

### code_symbols / code_relations（版本化演进，不物理删除）

```text
code_symbols:
 id, project_id, repository_id, snapshot_id FK → analysis_snapshots.id,
 file_path, symbol_type, name, qualified_name, signature,
 line_start, line_end, visibility, modifiers(JSONB),
 version_sha, valid_from, valid_until NULL, language DEFAULT 'JAVA'
 created_at TIMESTAMPTZ NOT NULL
 INDEX(project_id, file_path), INDEX(qualified_name)
 PARTIAL INDEX (valid_until IS NULL)          -- 当前版本查询

code_relations:
 id, project_id, snapshot_id FK,
 source_symbol_id FK → code_symbols.id, target_symbol_id FK → code_symbols.id,
 relation_type(CALLS/EXTENDS/IMPLEMENTS/FIELD_TYPE/IMPORT/CONTAINS/REFERENCES),
 confidence(EXTRACTED/INFERRED/AMBIGUOUS),
 valid_from, valid_until NULL, created_at TIMESTAMPTZ NOT NULL
 INDEX(source_symbol_id, relation_type), INDEX(target_symbol_id)
 PARTIAL INDEX (valid_until IS NULL)
```

（说明：Graph State 三元组 (project_id, repository_id, branch_id) 由 analysis_snapshots 记录，符号/关系按 snapshot 定位当前图版本。）

### analysis_snapshots（历史事实：不可删除，仅 created_at）

```text
id                    BIGINT PK
project_id            BIGINT
repository_id         BIGINT
branch_id             BIGINT
sha                   VARCHAR(64)
kind                  VARCHAR(20)      -- FULL / INCREMENTAL
current_graph_commit  VARCHAR(64)
prev_graph_commit     VARCHAR(64) NULL
symbol_count          INT DEFAULT 0
relation_count        INT DEFAULT 0
created_at TIMESTAMPTZ NOT NULL
UNIQUE (project_id, repository_id, branch_id, sha)
```

## 6. review schema

### reviews（历史事实：不可删除，仅 created_at + completed_at）

```text
id                  BIGINT PK
project_id          BIGINT
repository_id       BIGINT
commit_id           BIGINT FK → analysis.commits.id
status              VARCHAR(20)   -- QUEUED / ANALYZING / REVIEWING / COMPLETED / FAILED / CANCELLED
review_plan         JSONB         -- hardRules / suggestedChecks / focusAreas / depth / constraints
summary             TEXT NULL
stats               JSONB         -- 按类型 × 严重度计数
mark                VARCHAR(20)   -- COMPLETE / PARTIAL_REVIEW
extensions          JSONB         -- 多轮扩展记录
llm_calls           INT DEFAULT 0
context_tokens_used INT DEFAULT 0
created_at / updated_at / completed_at NULL
UNIQUE (project_id, commit_id)
```

### review_findings（历史事实：不可删除）

```text
id            BIGINT PK
review_id     BIGINT FK → reviews.id
project_id    BIGINT
commit_id     BIGINT FK → analysis.commits.id
type          VARCHAR(30)    -- BUG / PERFORMANCE / SECURITY / ARCHITECTURE / CODE_SMELL / DESIGN / BEST_PRACTICE
severity      VARCHAR(10)    -- CRITICAL / HIGH / MEDIUM / LOW / INFO
risk          VARCHAR(10)    -- HIGH / MEDIUM / LOW
title         VARCHAR(200)
description   TEXT
repository    VARCHAR(200)
commit_sha    VARCHAR(64)
file_path     VARCHAR(512)
side          VARCHAR(10)    -- OLD / NEW
line_start    INT NULL
line_end      INT NULL
evidence      JSONB          -- [{source, text}]
suggestion    TEXT NULL
source        VARCHAR(20)    -- LLM / STATIC_ANALYSIS / RULE
status        VARCHAR(20)    -- OPEN / CONFIRMED / REJECTED / IGNORED
confidence    NUMERIC(4,3) NULL
created_at / updated_at / confirmed_at NULL
INDEX (project_id, commit_id, type, severity, status)
INDEX (status, source)
```

## 7. knowledge schema

### knowledge（逻辑删除 ARCHIVED + 乐观锁 row_version；version 为业务版本）

```text
id                BIGINT PK
candidate_id      BIGINT NULL FK → knowledge_candidate.id
project_id        BIGINT NULL          -- scope=PROJECT 时非空
owner_user_id     BIGINT NULL          -- scope=PERSONAL 时非空
scope             VARCHAR(20)          -- PROJECT / PERSONAL
type              VARCHAR(30)          -- 7 种类型之一
title             VARCHAR(200)
summary           VARCHAR(500)
content           TEXT                 -- 面向 Agent 的精炼文本（入库前已压缩）
payload           JSONB                -- 按类型专用字段
status            VARCHAR(30)          -- PENDING_REVIEW / PUBLISHED / REJECTED / OUTDATED / SUPERSEDED / ARCHIVED
risk_level        VARCHAR(10)          -- LOW / HIGH / CRITICAL
confidence        NUMERIC(4,3) NULL
version           INT DEFAULT 1        -- 业务版本（当前知识版本，配合 knowledge_versions）
row_version       BIGINT NOT NULL DEFAULT 0   -- 乐观锁（避免与业务版本混淆）
occurrences       INT DEFAULT 1
first_seen_commit VARCHAR(64) NULL
last_seen_commit  VARCHAR(64) NULL
related_projects  JSONB                -- 相关项目 id 列表
source            JSONB                -- {project, commit, file, symbol, codeFragment}
embedding_status  VARCHAR(20)          -- PENDING / COMPLETED / FAILED / OUTDATED / REBUILDING
source_version    INT DEFAULT 1
created_by        BIGINT
created_at / updated_at / published_at NULL
INDEX (project_id, scope, type, status), INDEX (owner_user_id, scope)
```

### knowledge_candidate

```text
id                     BIGINT PK
finding_id             BIGINT           -- review.review_findings.id（跨域，建索引）
knowledge_id           BIGINT NULL FK → knowledge.id  -- 归属 Topic，Publication 后回填
project_id             BIGINT
type                   VARCHAR(30)
title                  VARCHAR(200)
content                TEXT
payload                JSONB            -- 分族专用字段
stage                  VARCHAR(20)      -- CANDIDATE / SUPPORTED / MERGED / REJECTED（无 PUBLISHED）
consolidation_decision VARCHAR(20) NULL -- NEW / MERGE / REVISION / DUPLICATE
risk_level             VARCHAR(10)      -- LOW / HIGH / CRITICAL
confidence             NUMERIC(4,3) NULL
evidence_count         INT DEFAULT 1
commit_count           INT DEFAULT 1
module_count           INT DEFAULT 1
confirmation_rate      NUMERIC(4,3) NULL
recall                 JSONB            -- 阶段三召回命中摘要
created_at / updated_at
INDEX (finding_id), INDEX (knowledge_id), INDEX (stage, type)
```

### knowledge_tasks（知识域任务；状态机同 analysis_tasks）

```text
id            BIGINT PK
project_id    BIGINT
repository_id BIGINT NULL
branch_id     BIGINT NULL
type          VARCHAR(30)   -- DISTILLATION / EMBEDDING / REINDEX
ref           VARCHAR(128)  -- finding_id / knowledge_id / 批号
target_ref    VARCHAR(128) NULL
status        VARCHAR(20)   -- QUEUED / RUNNING / COMPLETED / FAILED / FAILED_RETRYABLE / CANCELLED
                            -- COMPLETED / FAILED / CANCELLED 终态
next_run_at   TIMESTAMP NULL
retries       INT DEFAULT 0
result_ref    VARCHAR(255) NULL
error         TEXT NULL
created_at / started_at NULL / finished_at NULL / updated_at
UNIQUE (project_id, repository_id, branch_id, type) WHERE status = 'QUEUED'
INDEX (status, next_run_at)
```

### knowledge_versions（历史事实：不可删除，仅 created_at）

```text
id             BIGINT PK
knowledge_id   BIGINT FK → knowledge.id
version        INT
content_hash   CHAR(64)
content        TEXT
payload        JSONB
reason         VARCHAR(500) NULL
superseded_by  BIGINT NULL
created_at TIMESTAMPTZ NOT NULL
UNIQUE (knowledge_id, version)
```

### knowledge_relations

```text
id                   BIGINT PK
source_knowledge_id  BIGINT FK → knowledge.id
target_knowledge_id  BIGINT FK → knowledge.id
relation_type        VARCHAR(20)  -- RELATED_TO / DERIVED_FROM / SUPERSEDES / APPLIES_TO
created_at           TIMESTAMP
UNIQUE (source_knowledge_id, target_knowledge_id, relation_type)
```

### knowledge_references（个人引用，不复制权限）

```text
id           BIGINT PK
user_id      BIGINT           -- auth.users.id
knowledge_id BIGINT FK → knowledge.id
created_at   TIMESTAMP
UNIQUE (user_id, knowledge_id)
```

> 硬约束：项目权限失效/归档后，引用知识在检索时同步排除（Reference 不复制权限）。

### mcp_profiles（乐观锁 version）

```text
id               BIGINT PK
user_id          BIGINT
name             VARCHAR(100)
project_ids      JSONB          -- 选择的 Project Knowledge id 列表
include_personal BOOLEAN DEFAULT false  -- v3.5：默认不混合个人知识
description      VARCHAR(500) NULL
status           VARCHAR(20) DEFAULT 'ACTIVE'  -- ACTIVE / DISABLED
version          BIGINT NOT NULL DEFAULT 0     -- 乐观锁（并发编辑 Profile）
created_at / updated_at
INDEX (user_id)
```

### candidate_vectors / knowledge_vectors（pgvector 双集合，物理隔离）

```text
（Spring AI PgVectorStore 托管表，实际列以依赖版本为准，结构示意如下；
 禁止手工修改其内部表结构，metadata 扩展通过 Spring AI 机制配置）

candidate_vectors:                        knowledge_vectors:
  id         UUID PK                       id         UUID PK
  metadata   JSONB NOT NULL                metadata   JSONB NOT NULL
  embedding  vector(1024) NOT NULL         embedding  vector(1024) NOT NULL
  created_at TIMESTAMPTZ                   created_at TIMESTAMPTZ

metadata 关键键（candidate_vectors）：     metadata 关键键（knowledge_vectors）：
  candidate_id / project_id /               knowledge_id / project_id / scope /
  type / family / stage / risk_level        type / family / status=PUBLISHED / risk_level

CREATE INDEX ON candidate_vectors USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON knowledge_vectors USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON candidate_vectors USING gin (metadata);
CREATE INDEX ON knowledge_vectors USING gin (metadata);
```

> 隔离硬约束：两个集合必须为独立物理表/独立 Collection，不能只靠 status 字段区分；
> Candidate 只入 candidate_vectors（Consolidation 召回），Published 只入 knowledge_vectors（RAG/MCP）；
> 维度随 Embedding 模型定（默认 1024），模型/维度变更触发全量 REINDEX（旧向量先标 OUTDATED）。

## 8. 关键索引与约束清单

```text
唯一约束：
  oauth_accounts(provider, provider_user_id)          auth 域
  refresh_tokens.token_hash / mcp_tokens.token_hash   auth 域
  github_repositories.full_name / installations.installation_id
  project_members(project_id, github_user_id)
  project_branches(repository_id, name)
  commits(repository_id, sha)
  commit_changes(commit_id, file_path)
  analysis_snapshots(project_id, repository_id, branch_id, sha)
  reviews(project_id, commit_id)
  knowledge_versions(knowledge_id, version)
  knowledge_relations(source, target, type)
  knowledge_references(user_id, knowledge_id)
  任务表：UNIQUE(project_id, repository_id, branch_id, type) WHERE status='QUEUED'

高频查询索引：
  analysis_tasks / knowledge_tasks(status, next_run_at)
  code_symbols(project_id, file_path) / (qualified_name)
  code_relations(source_symbol_id, relation_type) / (target_symbol_id)
  review_findings(project_id, commit_id, type, severity, status) / (status, source)
  knowledge(project_id, scope, type, status) / (owner_user_id, scope)
  knowledge_candidate(stage, type) / (finding_id) / (knowledge_id)
  mcp_tokens(user_id, status) / (profile_id)

部分索引：
  code_symbols / code_relations：WHERE valid_until IS NULL（当前图快照查询）
```

## 9. 一致性 / 生命周期要点

```text
1. 任务状态机：QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED（终态）；
   FAILED_RETRYABLE → QUEUED（指数退避）；认领用 FOR UPDATE SKIP LOCKED；
   同分支同时只有一个 RUNNING；终态禁止回 RUNNING。
2. Knowledge 版本禁止直接 UPDATE 覆盖：来源变化 → 旧版 OUTDATED / SUPERSEDED，
   新版本写入 knowledge_versions；乐观锁用 row_version。
3. 三类删除策略：
   ① 逻辑删除（可撤销业务实体：projects/project_members/knowledge）；
   ② 状态失效（生命周期实体：mcp_tokens/refresh_tokens/installations）；
   ③ 历史事实不删除（commits/reviews/review_findings/knowledge_versions/analysis_snapshots）。
4. 历史表不可变：仅 created_at，无 updated_at、无 version、无 DELETE 接口。
5. Finding 行号锚点（repository + commit_sha + file_path + side + line_start/end）为硬约束；
   Rejected Finding 不进提炼。
6. 个人引用权限实时校验：项目权限失效 → 检索排除（不复制权限）。
7. MCP Token 只存哈希；Redis 仅运行时状态，PG 为事实源；撤销双向置 REVOKED。
8. 向量集合物理隔离（独立表/Collection）；知识 PUBLISHED 但 embedding 未 COMPLETED：
   Web 可见、MCP 不可检索。
9. assessment_scores 为 V1 预留结构（C 方案），只追加不修改。
10. 源码不落库：只存元数据/统计/知识来源片段，禁止完整 Blob/Tree/前后源码。
```

## 10. DDL 前置约定与实现默认值

```text
已确认（v3.5.1）：
  - 5 Schema：auth / project / analysis / review / knowledge
  - 同域物理 FK，跨域无物理 FK
  - Snowflake 生成 BIGINT（统一 IdGenerator），不表达业务语义；UUID 仅用于向量表内部/外部标识
  - VARCHAR + CHECK 仅用于真枚举；Java Enum + DB CHECK 双层保护；CHECK 覆盖终态
  - 三类删除策略；历史事实不可删除
  - UTC + TIMESTAMPTZ；created_at / updated_at 统一；不可变表只有 created_at
  - 并发编辑表 version（knowledge 用 row_version 作乐观锁）
  - PgVectorStore 托管 + 双向量集合物理隔离（不手改内部表）

实现默认值（不属于架构待定）：
  1. IdGenerator 使用应用内统一 Snowflake 抽象（Java 21 实现，节点号由配置注入），业务表统一 BIGINT；
  2. Embedding 维度默认 1024，由当前模型配置决定；模型或维度变化必须创建 REINDEX 任务；
  3. Spring AI 2.0.1 的 PgVectorStore 负责向量表生命周期，应用不直接修改其内部表；应用只维护 HNSW/GIN 索引和约定的 metadata；
  4. 枚举 CHECK 在 DDL 迁移中按各模块已定字段枚举生成，新增枚举必须同时修改模块文档、迁移脚本和测试。
```
