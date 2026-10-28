# 开发、提交与推送规范

本规范同时适用于本地 Git 提交和 GitHub 推送。本地提交以后可能整体进入远程历史，因此从第一次提交起就遵守同一套文件范围和凭据要求。

## 1. 提交到哪个分支

| 分支 | 用途 | 规则 |
| --- | --- | --- |
| 本地 `codex/github-clean` | 干净的开发与发布基线 | 小改动可直接提交；接收经过验证的功能改动；对应远程 `main` |
| 新建的 `codex/feat-*`、`codex/fix-*` 等分支 | 独立功能、修复或实验 | 必须从 `codex/github-clean` 创建；完成后合入发布基线 |
| 本地旧 `main` | 清理前的历史备份 | 不再开发、不推送，不把它合并进发布分支 |
| 清理前已有的其他分支 | 旧工作区与历史备份 | 不直接推送，不把其完整历史合并进发布分支 |

这里的“本地旧 `main`”和“远程 `main`”不是同一条历史。远程 `main` 应由 `codex/github-clean` 发布得到。首次发布后再次提交会正常增加提交数量，无需每次重新创建只有一次提交的分支。

提交前先确认当前位置：

```powershell
git status --short --branch
git branch --show-current
```

切换分支前先处理已有改动。旧分支仍保留清理前的跟踪规则，切换到旧分支会恢复其历史版本；日常开发保持在干净基线或由它新建的分支上。

## 2. 允许提交的内容

- 后端、前端源码和相关测试。
- Flyway 数据库迁移、MyBatis 映射及必要测试样例。
- `pom.xml`、`package.json`、`package-lock.json`、Maven Wrapper 和其他必要构建配置。
- 使用环境变量的公共配置、没有真实凭据的示例配置。
- 可复用的运行与验证脚本，以及 `README.md`、本规范等项目说明。

不提交 `.gitignore` 排除的内容，包括 `.agents/`、`.github/`、`.idea/`、`.tmp*/`、`.worktrees/`、`docs/`、`diagrams/`、`src/main/.ua/`、`target/`、`node_modules/`、`dist/`、日志、运行数据、上传文件、数据库备份、本地配置和密钥文件。`.mvn/` 的 Wrapper 配置保留。

不要使用 `git add -f` 绕过忽略规则。确有新的项目必需文件被误忽略时，应先调整具体的忽略规则，再正常提交。不要因为目录被忽略就删除本地运行数据或私密配置。

`.gitignore` 只控制文件跟踪，不能检查源码里的密码，也不能清除旧提交中的内容。已经跟踪的文件后来被忽略时，需要使用 `git rm --cached -- 文件路径`（目录加 `-r`）取消跟踪并提交；该操作保留磁盘上的文件，旧历史仍需另行处理。

## 3. 密码与敏感信息

- 密码、API Key、JWT 签名密钥、模型配置加密密钥通过环境变量或被忽略的本地配置提供。
- 公共配置不得包含真实凭据、带凭据的 URL 或可用的默认密钥；示例只使用空值或明确的占位说明。
- 测试可保留明确的虚构凭据，但不得复用真实环境的账号密码或 Key。
- 检查测试样例、截图、日志、数据库导出以及提交信息，避免夹带个人数据和凭据。
- 凭据误提交后，应移出跟踪并撤销或轮换对应凭据；新增一个删除提交不能消除历史中的泄露。已共享的历史需要协调后再清理。

## 4. 提交信息格式

使用以下格式，摘要可用中文，说明具体变化：

```text
<type>(<scope>): <摘要>

<可选正文：原因、主要行为变化、迁移要求>
```

`scope` 可省略；建议使用 `auth`、`chat`、`rag`、`ingestion`、`admin`、`frontend`、`db`、`build`、`docs` 等模块名。摘要控制在一行，建议不超过 72 个字符。

| type | 用途 | 示例 |
| --- | --- | --- |
| `feat` | 新功能 | `feat(chat): 支持取消流式回答` |
| `fix` | 修复问题 | `fix(auth): 修复过期令牌校验` |
| `refactor` | 不改变对外行为的代码重构 | `refactor(ingestion): 拆分文档解析职责` |
| `perf` | 性能优化 | `perf(rag): 减少重复检索请求` |
| `test` | 测试变更 | `test(admin): 补充导入失败场景` |
| `docs` | 项目文档 | `docs: 补充本地运行说明` |
| `build` | 依赖和构建配置 | `build(frontend): 更新开发依赖` |
| `chore` | 其他维护工作 | `chore(git): 排除本地临时文件` |
| `revert` | 撤销已有提交 | `revert: 撤销聊天缓存改动` |

一次提交只处理一件相对独立的事，避免把功能、无关格式调整和临时调试混在一起。不要使用“更新”“修改一些东西”“checkpoint current workspace”等无法说明变化的摘要。

存在不兼容变化时使用 `feat(api)!: ...` 等格式，并在正文用 `BREAKING CHANGE:` 说明影响与迁移方式。不要修改已发布的 Flyway 迁移文件来变更数据库结构，应增加新的迁移。

## 5. 本地提交流程

小改动可以在 `codex/github-clean` 上完成；较大的改动建议先创建独立分支，例如：

```powershell
git switch codex/github-clean
git switch -c codex/fix-login-validation
```

修改完成后先检查，再按文件或代码块暂存。下面以登录功能为例：

```powershell
git diff
git add -- frontend/src/views/LoginView.vue frontend/src/views/LoginView.test.ts
git diff --cached --stat
git diff --cached
git diff --cached --check
git status --short
```

也可使用 `git add -p` 选择已有文件中的改动；新文件需要显式 `git add -- 文件路径`。优先按范围暂存，使用 `git add .` 或 `git add -A` 前必须确认当前目录和全部改动。

根据变更范围完成验证：

| 变更范围 | 验证要求 |
| --- | --- |
| 仅文档或忽略规则 | 检查暂存差异、链接和示例；忽略规则变动时检查实际跟踪范围，无需运行完整测试 |
| 后端代码、公共配置或 Maven 配置 | 运行相关测试；涉及跨模块行为时执行 `.\mvnw.cmd test` |
| 前端代码或依赖 | 在 `frontend/` 中执行 `npm run test -- --run` 和 `npm run build` |
| 数据库迁移或持久化逻辑 | 设置 `TEST_DB_*`，在测试数据库上执行相关 `postgres-it` 集成测试 |
| 数据库密码、启动流程或运行脚本 | 检查配置和脚本，按影响执行迁移、启动或冒烟验证；使用测试环境 |

提交前确认暂存内容没有无关文件或敏感信息，并记录验证结果。未能执行某项必要验证时，明确说明原因，不标记为已通过。

```powershell
git commit -m "fix(frontend): 修复登录输入校验"
git status --short --branch
```

功能分支验证完成后，可在发布基线上快进合入：

```powershell
git switch codex/github-clean
git merge --ff-only codex/fix-login-validation
```

如果快进失败，先确认两个分支的改动及提交关系，整合并验证后再合入；不要用强制重置覆盖提交。需要复用旧备份分支的代码时，只迁移经过检查的具体改动，避免带回旧历史或被排除文件。

## 6. 远程推送流程

只提交本地 Git 时，完成 `git commit` 即可；`commit` 不会自动上传 GitHub。只有需要同步、协作或发布时才执行 `git push`。

GitHub 仓库为 [v185v/CollegeAssistant](https://github.com/v185v/CollegeAssistant)，远程名称为 `origin`，地址为 `https://github.com/v185v/CollegeAssistant.git`。当前本地已配置该远程，无需重复添加。先核对：

```powershell
git remote -v
```

其他工作区尚未配置 `origin` 时，执行：

```powershell
git remote add origin https://github.com/v185v/CollegeAssistant.git
```

首次发布到空的远程仓库：

```powershell
git push -u origin codex/github-clean:main
```

以后更新远程 `main`，在本地工作区干净、改动已提交时执行：

```powershell
git switch codex/github-clean
git fetch origin
git merge --ff-only origin/main
git log --oneline origin/main..codex/github-clean
git diff --stat origin/main codex/github-clean
git diff origin/main codex/github-clean
git push origin codex/github-clean:main
```

检查待推送差异与提交，确认只包含预期内容。拉取后的快进合并若失败，或推送被拒绝，先检查分叉并整合，不直接强推；整合改变了代码时重新执行相关验证。

本地分支名和远程分支名不同，默认 Git 配置下直接执行 `git push` 可能报错，因此使用上面的显式映射命令。不要在本地旧 `main` 上执行发布命令。

需要通过 Pull Request 协作时，可以显式推送新建的干净功能分支：

```powershell
git push -u origin codex/fix-login-validation:codex/fix-login-validation
```

在 GitHub 创建面向远程 `main` 的 PR。PR 中说明具体变化、验证结果和必要的迁移步骤。PR 合并后，将本地 `codex/github-clean` 与 `origin/main` 同步；如果采用 squash 或 rebase 合并，不要再将原功能分支的旧提交重复合入。

禁止使用 `git push --all`、`git push --mirror`、`git push --force`，也不要批量推送旧分支或旧标签。需要重写已共享历史时，单独确认处理范围和协作方案。
