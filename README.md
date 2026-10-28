# CollegeAssistant · 校园智答

面向校园信息查询的 AI 助手，提供用户认证、个人资料、知识库检索问答、流式对话、UGC 反馈，以及管理端的文档采集、模型配置、任务与数据库备份管理。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `src/main/java/xyz/v185v/collegeassistant/` | Spring Boot 后端，按认证、对话、文档、采集、检索、管理等业务模块组织 |
| `src/main/resources/application.yaml` | 公共配置，密码与 API Key 从环境变量读取 |
| `src/main/resources/db/migration/` | Flyway 数据库迁移 |
| `src/main/resources/mapper/` | MyBatis XML 映射 |
| `src/test/` | 后端测试、数据库集成测试和 RAG 评测样例 |
| `frontend/` | Vue 3、TypeScript、Vite 前端及 Vitest 测试 |
| `scripts/` | 冒烟测试及 OpenAPI 生成、校验工具 |
| `.mvn/`、`mvnw`、`mvnw.cmd` | Maven Wrapper |
| `docker-compose.yml` | PostgreSQL 18 + pgvector 开发环境 |

后端使用 Java 25、Spring Boot 4、MyBatis-Plus、LangChain4j；前后端分别运行。

## 本地运行

准备 JDK 25、Node.js（满足 Vite 7 的版本要求，例如 22.12+）、Docker Compose。下面以 PowerShell 7 为例，在项目根目录运行。

### 数据库与后端

```powershell
$env:DB_PASSWORD = Read-Host '数据库密码' -MaskInput
$env:DB_URL = 'jdbc:postgresql://localhost:55432/college'
$env:DB_USERNAME = 'college'
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))

docker compose up -d postgres
.\mvnw.cmd -q flyway:migrate
.\mvnw.cmd spring-boot:run
```

后端地址为 `http://localhost:8080`，接口前缀为 `/api`。已有数据库卷需要使用原数据库密码；更改环境变量不会修改已有数据库用户的密码。需要长期使用时，把生成的 JWT 密钥保存在本地私密配置中并复用。

### 前端

另开终端：

```powershell
Set-Location frontend
npm ci
npm run dev
```

打开 `http://localhost:5173/login`。开发服务器将 `/api` 代理到后端。

### 管理员与模型

首次创建管理员时，在启动后端的终端中设置 `ADMIN_BOOTSTRAP_USERNAME` 和 `ADMIN_BOOTSTRAP_PASSWORD`，然后启动后端。没有启用的管理员时，引导逻辑会创建管理员，或提升同名账号并重置其密码；已有启用管理员时不执行引导。创建完成后移除这两个环境变量。管理员使用同一登录页，登录后进入 `/admin`。

模型相关配置包括 `AI_CHAT_PROVIDER`、`AI_CHAT_MODEL`、`AI_CHAT_BASE_URL`、`AI_CHAT_API_KEY` 和对应的 `AI_EMBEDDING_*` 变量；远程模型地址需要加入 `AI_MODEL_HOST_ALLOWLIST`。完整变量见 `application.yaml`。

使用管理端持久化模型密钥时，需要设置 `ADMIN_CONFIG_ENCRYPTION_KEY`（32 个随机字节的 Base64 编码），并妥善保存以便后续解密：

```powershell
$env:ADMIN_CONFIG_ENCRYPTION_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

### 私密配置

真实密码、API Key、JWT 密钥和加密密钥通过环境变量或被忽略的 `src/main/resources/application-local.yaml` 保存；使用本地配置时，设置 `SPRING_PROFILES_ACTIVE=local`。不要把真实凭据写回公共配置或源码。

根目录 `.env` 仅由 Docker Compose 自动读取，Spring Boot 和 Maven 不会自动加载它。`.env.example` 是可公开的空值模板。

## 验证

```powershell
.\mvnw.cmd test
Push-Location frontend
npm run test -- --run
npm run build
Pop-Location
```

PostgreSQL 集成测试使用 `postgres-it` Maven profile，需要设置 `TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD`。`scripts/smoke-test.ps1` 需要在当前终端设置 `DB_PASSWORD`。OpenAPI 文件位于被忽略的 `docs/` 中，可先执行 `python scripts/generate_openapi.py` 重新生成，再执行 `python scripts/validate_openapi.py` 校验。

## GitHub 发布范围

仓库保留源码、测试、数据库迁移、公共配置、依赖锁文件、运行脚本及本说明。内部文档、Agent/IDE 配置、CI 目录、临时工作区、依赖、构建产物、运行数据、备份和私密配置通过 `.gitignore` 排除，本地文件保留。

干净发布分支为 `codex/github-clean`。发布时仅推送此分支，例如将它推送为远程 `main`：

```powershell
git remote add origin https://github.com/<your-account>/<your-repository>.git
git push -u origin codex/github-clean:main
```

原 `main` 和其他旧分支保留本地历史。不要使用 `git push --all` 或 `git push --mirror` 上传这些分支。
