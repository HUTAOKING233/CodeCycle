# CodeCycle

CodeCycle 是基于 RAG 与 MCP 的开发者代码知识迭代平台。

## 当前运行边界

```text
Windows 宿主
  ├── 6 个 Java 21 Spring Boot JAR
  │   ├── Gateway
  │   ├── Auth
  │   ├── Project + GitHub Integration
  │   ├── Analysis + Review
  │   ├── Knowledge + RAG
  │   └── MCP
  └── Vue 3 前端（Node.js 24 / Vite）

Linux 虚拟机 Docker
  ├── PostgreSQL 16 + pgvector
  ├── Nacos 2.5.4
  ├── Redis 7.4.11
  └── MinIO
```

Java 服务和 Vue 前端均在 Windows 开发；Docker Compose 只负责虚拟机中的数据库与中间件。

## 本地开发

1. 在 Linux 虚拟机中复制 `.env.example` 为 `.env`，修改密码后启动中间件：

   ```bash
   docker compose --env-file .env up -d
   ```

2. 在 Windows 宿主确认 `192.168.80.125` 可访问 PostgreSQL、Redis、Nacos、MinIO。

3. 默认使用 `dev` Profile：

   ```powershell
   .\scripts\start-dev.ps1
   ```

4. 前端单独启动：

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

生产环境显式设置 `SPRING_PROFILES_ACTIVE=prod`，生产配置不提供密码、Token 或 API Key，统一从环境变量/Nacos 密文读取。

## 文档

- [项目定义](AI代码工程知识与质量分析平台_项目定义.md)
- [设计文档入口](ai-code-knowledge-platform-design/README.md)
- [变更记录](CHANGELOG.md)
