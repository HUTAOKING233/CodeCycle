# Deployment Architecture（基线 v3.6 · 中间件编排 v3.6）

## 1. Product Deployment（产品依赖）

```text
6 个进程：
 ├── Gateway（对外入口，统一鉴权）
 ├── Auth
 ├── Project（Project + GitHub Integration）
 ├── Analysis/Review（Code Analysis + Review）
 ├── Knowledge/RAG（Knowledge + RAG）
 └── MCP（独立故障域）

依赖中间件：
 ├── PostgreSQL + pgvector（业务事实源 + 检索索引）
 ├── Nacos（注册中心 + 配置中心）
 ├── Redis（仅 MCP Token 运行时状态专用，TTL 自动淘汰；不作为通用缓存）
 └── MinIO（对象存储：用户头像 / 项目图片 / 导入导出文件，S3 兼容）

异步任务：PostgreSQL 任务表 + Scheduler（无需消息中间件）
```

- 产品部署 = 6 个进程 + 上述中间件，运行于任意可部署环境（中间件可用 Docker Compose 或云托管）；
- 对外入口：Gateway；Web `https://codecycle.me`、MCP `https://codecycle.me/mcp`（隧道/域名属部署环境事实，非架构依赖）；
- 服务间调用走 HTTP 接口 + 内部令牌。

## 2. Development Environment（作者本地开发方式，非产品依赖）

```text
Windows 宿主运行 6 个进程（服务注册到 Nacos 时上报 Windows 宿主 IP）；
虚拟机 Docker（Docker Compose）运行中间件：PostgreSQL + pgvector、Nacos、Redis、MinIO；
编排文件：项目根目录 docker-compose.yml（环境变量 .env，模板 .env.example）。
```

虚拟机网络（作者本地）：

```text
虚拟机（Docker 宿主）IP：192.168.80.125
虚拟网络网关：192.168.80.2
Windows 宿主：192.168.80.1
```

- 启动：`docker compose --env-file .env up -d`（在虚拟机内执行；中间件端口已映射到 192.168.80.125 全接口）；
- Windows 宿主应用访问中间件地址：
  - PostgreSQL：`192.168.80.125:5432`
  - Redis：`192.168.80.125:6379`
  - Nacos：`http://192.168.80.125:8848`
  - MinIO API：`http://192.168.80.125:9000`；控制台（仅开发）：`http://192.168.80.125:9001`
- 容器/服务/网络/卷命名统一 `codecycle_*`（显式命名卷，不使用匿名卷与默认位置）；
- Nacos 单机开发定位（v3.5 确认）：仅作注册中心 + 配置中心，单节点启动，不涉及集群/高可用部署管理；
- Webhook 本地测试：无公网域名时用分支轮询或"模拟 Push 事件"调试入口完成全链路测试（不依赖生产域名）；生产/演示将 GitHub App Webhook 指向 `https://codecycle.me/api/webhooks/github`（HMAC 验签，见模块 04 §15.1）；
- 本地访问：`http://localhost:8080`（Web）、`http://localhost:8080/mcp`（MCP 调试）；
- **该环境仅属于开发环境章节，不作为产品运行依赖**。

## 3. 网络与 HTTPS

```text
生产（application-prod）：
 ├── 域名（已定）：codecycle.me（隧道绑定 → HTTPS）
 ├── Web：https://codecycle.me（Gateway）
 ├── MCP：https://codecycle.me/mcp（经 Gateway 鉴权）
 └── GitHub OAuth 回调：https://codecycle.me/api/auth/github/callback

开发（application-dev）：
 ├── Web/MCP：http://localhost:8080（Gateway）
 └── 中间件：http://192.168.80.125（PostgreSQL/Redis/MinIO 走端口）
```

## 4. 配置管理

- 环境区分：`application-dev.yml` / `application-prod.yml`（或 Nacos 配置中心按环境分组）；
- Docker Compose 环境变量：项目根 `.env`（模板 `.env.example`），敏感信息（数据库密码、Redis/MinIO 密码、GitHub OAuth Client Secret、GitHub App 私钥、模型 API Key、隧道 Token）只走环境变量/密文，不提交明文；
- Nacos 作为配置中心统一管理各服务配置；
- Gateway 统一鉴权覆盖 Web 与 MCP 请求。

## 5. MCP

```text
Claude Code / Agent → HTTPS（经隧道域名）→ Gateway 鉴权 → MCP Service → Knowledge/RAG
```

## 6. 未来 C/S

```text
Desktop
 ├── Application Server
 ├── PostgreSQL + pgvector
 ├── Redis（MCP Token 运行时状态）
 ├── MinIO（本地对象存储）
 ├── Ollama / Local Embedding
 └── MCP
```

第一版不引入独立 Milvus；未来可按规模切换。

## 7. 对象存储（MinIO）设计（v3.5.2 定案）

**结论：引入 MinIO，纳入 Docker Compose（codecycle-minio + codecycle-minio-init）。**

理由：

- 用户头像 / 项目 Logo / 知识导入导出等属于二进制持久化，不适合进 PostgreSQL 关系库；
- MinIO S3 兼容、单容器轻量，适合毕设 + 未来本地化 C/S 场景；应用层用 SDK 生成 presigned URL，前端不经对象存储直连。

Bucket 规划（启动时由 minio-init 自动创建，均私有）：

```text
codecycle-assets   用户头像 / 项目 Logo / 业务图片（私有 + presigned GET）
codecycle-files    导入导出文件 / 临时产物（服务端生成后 presigned 下载）
```

对象键命名规范：

```text
user/{userId}/avatar/{uuid}.{ext}
project/{projectId}/logo/{uuid}.{ext}
export/user/{userId}/{yyyyMM}/{uuid}.{ext}
```

上传限制（应用层强制，非 MinIO 配置）：

```text
头像 ≤ 2MB；业务图片 ≤ 5MB；仅 png / jpeg / webp；
导出文件由服务端生成（不接受用户直接上传大文件）；
presigned URL 短时效（默认 5 分钟），日志不记录签名 URL。
```

访问模型：

```text
应用进程（Windows 宿主）→ http://192.168.80.125:9000（SDK，配置在 application-dev）
容器内互访/未来同机部署 → http://minio:9000（compose 服务名）
对外（Web/MCP）→ 应用生成 presigned URL 走 Gateway HTTPS，MinIO 9000/9001 不直接暴露公网
控制台 9001 仅开发环境使用（生产禁用/独立认证）
```