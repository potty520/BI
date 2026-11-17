# 自研报表与 BI 平台

一套可私有化部署的报表与自助分析平台：数据接入 → 数据集建模 → 复杂报表设计 → 参数查询 → 可视化大屏 → 数据填报 → 自助分析 → 指标治理 → 权限调度 → 运维监控，全链路由一个 Jar 承载。核心是自研的**单元格扩展引擎**（父格拓扑、纵横扩展、分组上下文公式），配套权限、水印、调度与审计。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3 + Vite 5 + Element Plus + ECharts 5 + Pinia + vue-router |
| 后端 | Java 8 + Spring Boot 2.7.18 + MyBatis-Plus 3.5.5 + JWT + Lombok |
| 存储 | 生产：MySQL 8（元数据库 + 业务库分离）；本地开发：H2 内存库（`--spring.profiles.active=dev` 免装数据库）；数据源层可接 MySQL/Oracle/SQLServer/PostgreSQL/达梦/人大金仓/GaussDB |
| 部署 | 前后端打包单 Jar，内嵌 Tomcat，`java -jar` 一条命令启动 |

## 模块实现清单

| 模块 | 功能 | 实现位置 |
| --- | --- | --- |
| M01 数据源接入 | JDBC 多驱动 + 连接测试 + 密码加密 + 文件/**HTTP API 数据集（主机白名单防 SSRF）** | `Datasource.vue` / `JdbcExecutor` / `SqlSafeBuilder` |
| M02 数据集管理 | SQL 数据集 + `${param}` 安全占位符 + 可视化/文件 + 缓存 + 字段推断 | `Dataset.vue` / `DatasetQueryService` |
| **M03 报表设计器** | **类 Excel 画布 + 单元格扩展引擎（父格/纵横扩展）+ 公式引擎 + 三预览 + 版本回滚** | `Designer.vue` / `ExpandEngine` / `FormulaEngine` |
| M04 参数与查询 | 文本框/下拉/多选/日期/树 + 参数联动 + 注入查询 | `Params.vue` / `SqlSafeBuilder` |
| M05 可视化图表 | 12 种 ECharts 图表 + 维度度量绑定 + 大屏自由拖拽 | `Charts.vue` / `Dashboard.vue` |
| M06 数据填报 | 表单设计 + 必填/类型/范围/正则校验 + **PreparedStatement 参数化**事务批量入库 + 暂存 | `FormFill.vue` / `FormService` |
| M07 自助分析 | 拖拽维度度量 + 聚合**下推 SQL**（可回退内存）+ 过滤排序 + 占比/累计/排名 + 配置转可执行 SQL | `Analysis.vue` / `AnalysisService` |
| M08 指标管理 | 原子/派生指标 + 统一口径 + 血缘 | `Metrics.vue` |
| M09 平台管理 | 用户/角色 RBAC（**接口级强制鉴权 + 登录限流 + 审计日志 + 账号自助 + 找回密码**）+ **资源/行级/列级三重权限与脱敏** + 目录树 + **真实执行的定时调度（邮件推送、多节点租约、失败重试与告警）** + 操作日志 | `Users.vue` / `Directory.vue` / `Schedule.vue` / `Logs.vue` |
| M10 部署运维 | 单 Jar + 健康检查接口 + JVM/节点监控 + **报表 Excel/PDF（分页、重复表头、页脚页码）、数据集 CSV 导出** | `Ops.vue` / `AdminController` |

## 安全与权限（v1.1 加固）

原 v1.0 的后端**没有任何鉴权**：66 个接口全部裸奔，任何人可重置管理员密码或执行任意 SQL。v1.1 已按下表落地。

### 鉴权链路

所有 `/api/**`（除登录与健康检查）都由 `JwtAuthInterceptor` 校验令牌，解析出的 `CurrentUser` 放入请求级 `AuthContext`：

- 令牌只携带 `uid / username / roles`，权限判断一律以服务端解析结果为准；前端 localStorage 里的任何标记都不参与授权决策；
- 未登录返回 **HTTP 401**，越权返回 **403**，登录失败 5 次锁定 5 分钟返回 **429**；
- 密钥通过 `RBI_JWT_SECRET` 注入（≥32 字节），仓库内默认值仅供本机开发，启动日志会告警。

### 角色矩阵

| 能力 | ADMIN | ANALYST | VIEWER |
| --- | --- | --- | --- |
| 查看报表 / 图表 / 仪表板 / 填报 | ✅ | ✅ | ✅ |
| 提交填报数据 | ✅ | ✅ | ✅ |
| 新建/修改报表、数据集、参数、图表 | ✅ | ✅ | ❌ |
| 用户 / 角色 / 目录 / 权限 / 调度 / 日志 | ✅ | ❌ | ❌ |
| 数据源连接测试、任意 SQL 执行 | ✅ | ❌ | ❌ |

> `/api/admin/**` 统一要求 ADMIN；配置类写接口标注 `@RequireRole({"ADMIN","ANALYST"})`。

### 其他加固

- **SQL 参数化**：填报入库全部改 `PreparedStatement` 绑定参数；数据集 `${param}` 做类型化字面量转义（同时处理反斜杠，防 MySQL 转义绕过）；`/api/sql/execute` 仅 ADMIN 可用，且强制单条只读语句；
- **审计**：`OpLogAspect` 记录所有写操作（人/IP/接口/结果/耗时/错误摘要），落 `rbi_op_log`；
- **异常不泄密**：系统异常只回「内部错误 + traceId」，堆栈仅进服务端日志；
- **CORS 白名单**：`rbi.security.allowed-origins` 配置，默认只放行本地前端端口，且关闭 `allowCredentials`；
- **口令**：新建用户 / 重置密码一律服务端生成随机临时口令，返回一次供管理员转交，不再固定 `123456`；
- **初始化脚本幂等**：`data.sql` 改 `INSERT IGNORE`，`continue-on-error` 关闭，重复启动不再静默吞错。

### 定时调度（真实执行）

`ScheduleRunner` 每 30 秒扫描到期任务（`rbi_schedule`），按 cron 推进 `next_run_time`：

- `refresh`：清理报表数据集的查询缓存；
- `export`：渲染报表并导出 Excel 到 `rbi.export.dir`；
- `email`：未配置 SMTP 时**记为失败**并写审计，不会假成功；
- 管理端支持「立即执行一次」。单节点实现，多节点部署需加分布式锁。

## 快速启动

### 方式一：本地开发（H2 内存库，免装 MySQL）

```bash
cd frontend && npm install && npm run build   # 产物输出到 backend/src/main/resources/static
cd ../backend && mvn package -DskipTests
java -jar target/report-bi-platform-1.0.0.jar --spring.profiles.active=dev --server.port=18088
# 打开 http://127.0.0.1:18088  admin / 123456
```

### 方式二：生产（MySQL 8）

见下方「数据库配置」与「从源码构建」，用环境变量注入库连接与 `RBI_JWT_SECRET`。

### 接口冒烟测试

```bash
pwsh -File docs/smoke-test.ps1        # 或 powershell -ExecutionPolicy Bypass -File docs/smoke-test.ps1
```

覆盖 32 项断言：未登录 401、伪令牌 401、角色越权 403、SQL 写语句拒绝、报表渲染、Excel/CSV 导出格式、审计落库、调度失败可见、登录限流 429。

## 从源码构建

仓库不含构建产物（jar / node_modules / 前端 static 均已 gitignore），按以下顺序构建：

```bash
# 1. 构建前端（产物输出到 backend/src/main/resources/static）
cd frontend && npm install && npm run build

# 2. 打包后端 fat jar（含前端与依赖）
cd ../backend && mvn clean package -DskipTests
# 产物：backend/target/report-bi-platform-1.0.0.jar（约 54MB，含 POI 与前端产物）

# 3. 运行（需可访问的 MySQL，见下方环境变量）
java -Dfile.encoding=UTF-8 -jar target/report-bi-platform-1.0.0.jar --server.port=8088
```

> 后端要求 JDK 8+；打包需用 JDK（非 JRE）。前端需 Node 18+。

## 数据库配置

后台元数据库与测试业务库分离，通过环境变量注入：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `RBI_DB_URL` / `RBI_DB_USER` / `RBI_DB_PWD` | 后台元数据库（报表/用户/权限等） | `jdbc:mysql://127.0.0.1:3306/rbi_platform` |
| `RBI_TEST_URL` / `RBI_TEST_USER` / `RBI_TEST_PWD` | 测试业务库（`demo_sales` 演示数据） | `jdbc:mysql://127.0.0.1:3306/rbi_testdb` |

初始化脚本：`backend/src/main/resources/db/` 下的 `schema.sql`+`data.sql`（后台库）、`testdb_schema.sql`+`testdb_data.sql`（测试库）。

> 安全说明：数据源密码不在代码/SQL 中出现，`DbInitializer` 启动时按 `rbi.test-datasource` 配置自动同步到数据源记录。

## 生产部署（示例）

```bash
# 服务器需 JDK 8+ 与 MySQL 8
mkdir -p /opt/rbi/logs
# 上传 jar 后写 /opt/rbi/rbi.env（权限 600）：
#   RBI_DB_URL=...  RBI_DB_USER=...  RBI_DB_PWD=...
#   RBI_TEST_URL=... RBI_TEST_USER=... RBI_TEST_PWD=...
#   SPRING_SQL_INIT_MODE=never
# 再配置 systemd 服务 ExecStart=/usr/bin/java -jar /opt/rbi/report-bi-platform-1.0.0.jar --server.port=8099
```

更新部署建议：先上传为临时文件，再 `systemctl stop` → `mv` → `systemctl start`，**不要直接覆盖运行中的 jar**（会短暂导致静态资源 404）。

生产环境务必设置：

- `RBI_JWT_SECRET`（≥32 字节，否则启动即报错）；
- `RBI_ALLOWED_ORIGINS`（前端实际域名）；
- `SPRING_SQL_INIT_MODE=never`（首次初始化完成后关闭自动建表/灌数）。

## 前端开发模式（前后端分离）

```bash
# 后端
cd backend && mvn spring-boot:run
# 前端（另开终端）
cd frontend && npm run dev
# 打开 http://localhost:5173（Vite 已代理 /api 到 8088）
```

演示账号（密码均为 `123456`）：`admin` / `analyst` / `viewer`，分别为管理员 / 分析师 / 查看者，用于验证 RBAC。

## 深挖重点：单元格扩展引擎

这是"中国式复杂报表"与普通 BI 透视表的根本区别。核心实现在 `backend/src/main/java/com/rbi/engine/`：

- **CellModel**：单元格元数据（类型、字段、扩展方向 v/h/none、父格、样式）
- **ExpandEngine**：
  - 按"父格拓扑"排序，保证父格先展开
  - 父格的每个展开值形成一个数据分组上下文，子格在各自上下文中再展开（逐层下钻）
  - 纵向扩展字段去重值向下铺开，横向扩展向右
  - 展开完成后落入二维渲染网格
- **FormulaEngine**：递归下降解析器，支持 SUM/AVG/COUNT/MAX/MIN/IF/CONCAT/ROUND + 单元格引用 `A1+B1` + 四则运算，不使用脚本引擎（安全）
- **SqlSafeBuilder**：`${param}` 占位符按类型转义，`*_filter` 走白名单模板，防 SQL 注入

配套单元测试 `ExpandEngineTest` 覆盖：单层纵向扩展、父子格联动、横向扩展、公式聚合、父格上下文公式、单元格引用解析。

## 演示数据

`demo_sales` 表 65 行销售明细，覆盖 5 大区、多省份、4 类产品的 12 个月数据，存放在测试库 `rbi_testdb`，供设计器、图表、自助分析、填报共用。填报提交的数据也持久化到该表，重启服务不会丢失。

## 说明

- 后台库与测试库分离：元数据（模板/配置/权限）在 `rbi_platform`，业务演示数据在 `rbi_testdb`，通过 `application.yml` 的 `rbi.test-datasource` 配置连接。
- `DbInitializer` 在首次启动时检测 `demo_sales` 是否存在，不存在则自动建表导入演示数据；已存在则跳过，保护填报数据。
- 国产数据库驱动（达梦/金仓/GaussDB）需将对应厂商的 JDBC 驱动 jar 加入 `pom.xml`，在数据源管理页配置驱动类名与 JDBC URL 即可连接。
- 验证报告：`docs/远程部署验证报告.html`（含 23 项 API 验证结果与浏览器实测截图，为 v1.0 演示验证；v1.1 的鉴权与安全用例见 `docs/smoke-test.ps1`）。
- 可用性说明：`docs/可用性说明.md`——现在能做什么、不能做什么、上线前 checklist 与已知盲区。
- 冒烟脚本：`docs/smoke-test.ps1`（32）、`docs/smoke-test-v12.ps1`（38）、`docs/smoke-test-v13.ps1`（42）、`docs/smoke-test-v14.ps1`（41）、`docs/smoke-test-v15.ps1`（37）、`docs/smoke-test-worker.ps1`（9，双进程调度验证）；夹具：`dev-smtp-sink.js`、`dev-http-fixture.js`、`dev-oidc-fixture.js`。

## 变更记录

### v1.6（界面：去掉模块编号、重做配色与登录页）

- 侧边栏、页头、工作台去掉全部 `M01 ~ M10` 编号；侧边栏改为按「数据接入 / 报表与分析 / 平台」分组，子菜单折叠；
- 全局配色由宝蓝 `#2563eb` 改为靛蓝 `#4f46e5` + 紫 `#7c3aed`：设计令牌集中在 `frontend/src/styles/main.css`（含 Element Plus 主题变量），侧边栏深靛渐变、内容区冷灰蓝 `#f5f7fb`、卡片统一 16px 圆角与柔和阴影；
- 登录页重做为左右分栏（品牌介绍 + 表单卡片），新增「记住账号」、SSO 入口下移、演示账号一键填充；工作台 Hero 与步骤条同步换色；
- **修复**：v1.1 重写 `MainLayout.vue` 时漏掉了应用外壳样式块，导致侧边栏/头部/内容区布局失效（菜单直接贴顶、无 logo），本次一并恢复并重写；
- 修复用户列表操作列过窄导致按钮换行、被裁切的问题；
- 新增截图：`docs/screenshots/v16-login.png`、`v16-home.png`、`v16-dataset.png`、`v16-users.png`。

### v1.5（SSO/LDAP、单设备踢出、密码过期、部门树、审计导出与水印、独立调度）

**单点登录与目录认证**

- OIDC（授权码 + userinfo）：`/api/auth/oidc/config|authorize|callback`，state 一次性防重放，本地用户映射 + 可选自动开通（默认角色 `VIEWER`）；
- LDAP / AD 简单绑定：本地口令失败时回退目录认证，目录不可达返回 503 而非 500；配置 `rbi.ldap.*`；
- 前端登录页新增「企业单点登录」，新增 `/sso-callback` 页。

**会话与密码治理**

- `rbi_user_session` 会话表（jti/IP/UA/最后活跃/过期）；令牌带 `jti`，解析时校验会话存在且未注销；
- 我的会话列表、单设备注销、退出当前设备、退出全部设备（token_version）；
- 密码过期：`pwd_updated_at` + 全局 `rbi.security.pwd-expire-days`（可按用户覆盖），过期登录即强制改密；管理员可 `/api/admin/users/{id}/expire-password` 一键置过期。

**行级表达式增强**

- 新增 `${deptTree}`：按当前用户部门计算子树 ID 列表，实现"部门树继承"的数据隔离；
- 函数白名单：`lower/upper/coalesce/abs/round/year/month/day/length/trim`，其余函数（如 `sleep`）保存时即拒绝；
- 表达式字符集支持中文字面量（此前 `region = '华北'` 会被误判为非法字符）。

**审计导出与水印**

- `/api/admin/logs/export` 导出审计 CSV（最多 5000 条，支持按用户/模块过滤）；
- 所有导出带水印「导出人 xxx · 时间 · 仅限内部使用」：CSV 首行注释、Excel 页眉、PDF 页脚；定时任务导出水印为 `system(定时任务)`。

**独立调度进程**

- 应用实例默认 `rbi.schedule.enabled=false`（不跑调度），新增 `worker` profile（`spring.main.web-application-type=none`）专职跑调度；
- 调度执行时以系统身份运行，避免后台线程没有登录上下文被资源权限 403 拦下。

**新增验证脚本**

- `docs/smoke-test-v15.ps1`（37 项：会话/密码过期/部门树/水印/OIDC）；
- `docs/smoke-test-worker.ps1`（9 项：双进程证明应用不调度、worker 调度并落盘）；
- `docs/dev-oidc-fixture.js`（本地 OIDC 夹具）。

### v1.4（令牌吊销、MFA、行级表达式、分组换页/条件样式、API 分页）

**令牌吊销**

- `rbi_user.token_version`，令牌携带 `ver`；`JwtAuthInterceptor` 每次解析后与库中版本比对，不一致即 401；
- 退出登录、修改密码、管理员重置、`/api/admin/users/{id}/kick` 都会 `+1`，**旧令牌立即失效**，无需等自然过期。

**MFA（TOTP）**

- 纯 Java 实现 `Totp`（RFC 6238，HMAC-SHA1 / 6 位 / 30 秒，±1 窗口容错），无新增依赖；
- 流程：`/auth/mfa/setup` 生成密钥 → `/auth/mfa/enable` 提交动态码启用 → 登录返回 `mfaRequired`+`mfaToken` → `/auth/login/mfa` 换正式令牌；
- 关闭 MFA 同样要求动态码；`mfaToken` 5 分钟有效且不能当访问令牌用（解析时显式拒绝）。

**行级权限表达式**

- `rbi_data_scope` 增加 `scope_type` / `filter_expr`：支持 `amount < 10000 AND region = '华北'`、`IN (...)`、括号，以及 `${deptId}` / `${userId}` 上下文变量；
- 白名单语法校验：字符集 + 括号配对 + 关键字黑名单（`select/insert/update/delete/drop/union/exec…`）+ 片段黑名单（`;`、注释、`sleep(`），**保存时即校验**，非法配置进不了库。

**分组换页与条件样式**

- `LayoutModel.breakOn`：按字段分组时值变化即重新起页；PDF 分页与 Excel 分页符一致；
- 单元格 `style.rules` 支持条件样式（`> / >= / < / <= / == / != / contains:` → `color/bg/bold`），PDF 与 Excel 同时生效；Excel 样式按 workbook 缓存，避免跨 workbook 复用导致 500。

**API 数据集分页**

- `config_json.paging = {start, size, maxPages}`，URL/body 支持 `${page}`、`${size}`、`${offset}`，某页不足 `size` 即停止，总行数仍受 5000 上限保护。

**新增工具**

- `docs/smoke-test-v14.ps1`（41 项，含 Python 生成的 TOTP 动态码与 PDF/xlsx 结构断言）。

### v1.3（列级权限/脱敏、找回密码、分页页脚、调度重试告警、API 数据集）

**列级权限与脱敏**

- 新增 `rbi_column_policy` 与 `ColumnPolicyService`：按角色 + 数据集 + 列配置 `hidden` / `mask_all` / `mask_phone` / `mask_idcard` / `mask_email` / `mask_name`，同一列命中多条取最严格；
- 在 `DatasetQueryService.query()` 统一出口应用，因此报表、图表、自助分析、CSV/Excel/PDF 导出看到的是同一份受限数据；
- 分析接口对隐藏列做前置校验（用作维度/度量直接 403），防止通过聚合反推原值；
- 前端「目录与权限」页新增数据权限区（行级范围 + 列级策略维护）。

**找回密码**

- `rbi_user.email` + `rbi_reset_token`（一次性、30 分钟过期）；
- `POST /api/auth/forgot`（无需登录，账号不存在也返回同一提示，防枚举）、`POST /api/auth/reset`；
- 邮件通过 JavaMailSender 发送重置链接；`rbi.security.reset-debug=true` 时接口直接返回 token 便于联调；
- 前端登录页新增「忘记密码」，新增 `/reset-password` 页面。

**报表分页与页眉页脚**

- `LayoutModel` 新增 `headerRows` / `pageSize` / `footerText` / `pageSizeName`；
- PDF：每页重复表头（`PdfPTable#setHeaderRows`）、按 `pageSize` 主动分页、页脚支持 `{page}/{pages}/{date}`（先试排一次拿总页数）；
- Excel：`setRepeatingRows` 重复表头、`setRowBreak` 强制分页、页眉页脚 `&P/&N` 转换、横向 + 宽度自适应。

**调度失败重试与告警**

- `rbi_schedule` 新增 `retry_count` / `max_retries` / `last_error`；
- 失败按 `rbi.schedule.retry-backoff`（默认 1,5,15 分钟）退避重试；耗尽后发送告警邮件（`rbi.alert-to`）并回到正常 cron 周期；
- 手动执行同样走重试/告警逻辑，任务列表可直接看到 `retryCount` 与 `lastError`。

**API 数据集**

- `DatasetQueryService.runApi` 支持 `{url, method, headers, body, dataPath}` 拉取 JSON 并转行集；
- 安全：目标主机必须在 `rbi.api-dataset.allowed-hosts` 白名单内，默认全禁（防 SSRF）；连接/读取超时 5s/10s，行数上限 5000。

**其他**

- 全局 `server.servlet.encoding.force: true`：响应一律带 `charset=UTF-8`，避免客户端按 Latin-1 解析导致中文乱码；
- 新增 `docs/smoke-test-v13.ps1`（42 项）与 `docs/dev-http-fixture.js`（本地 HTTP 夹具）。

### v1.2（账号自助 / 数据权限 / 邮件 / 调度租约 / 分析下推 / PDF）

**账号自助**

- 新增 `POST /api/auth/password`（校验原密码 + 强度：≥8 位含字母数字、不得与用户名相同）、`PUT /api/auth/profile`；
- `rbi_user.must_change_pwd`：新建用户与管理员重置后置 1，登录返回 `mustChangePwd`，前端强制跳转个人设置页改密；
- 新增前端「个人设置」页 `Profile.vue`；新建用户接口改为返回 `{username, initialPassword}` 结构化对象。

**数据级权限**

- `PermissionService` 让存量 `rbi_permission` 表真正生效：报表/目录按角色授权，未配置视为公开，ADMIN 不受限；`/api/reports` 列表过滤 + 渲染/版本接口 `assertCanView`；
- 新增 `rbi_data_scope` 表与 `DataScopeService`：按角色 + 数据集 + 列值白名单生成 `AND col IN (...)`，经 `${row_filter}` 注入；
- **fail-closed**：配置了数据范围但数据集 SQL 缺 `${row_filter}` 时返回 403；无范围时也占位，避免空字面量导致 SQL 语法错误；
- 管理端新增 `/api/admin/data-scopes` CRUD。

**邮件推送 / 调度可靠性**

- 接入 `spring-boot-starter-mail`：`MailService` 把报表渲染成 xlsx 作为附件发送；未配置 SMTP 时明确失败并写审计；
- 调度新增 DB 租约（`rbi_schedule.locked_until` + `ScheduleMapper.claim/release`），多节点部署不会重复执行；
- 新增本地 SMTP 验证工具 `docs/dev-smtp-sink.js`（零依赖）。

**自助分析下推**

- `AnalysisService` 支持 `engine: auto|sql|memory`：默认优先把分组聚合下推到数据库，失败回退内存并在响应里标注 `engine`；
- `toSql` 输出**可执行**的 SQL（基于数据集真实 SQL，含行级权限），不再是 `FROM dataset WHERE 1=1` 的占位文本。

**PDF 导出**

- 新增 `PdfExportService`（OpenPDF，LGPL/MPL，规避 iText AGPL）：A4 横向、合并单元格、STSong-Light 中文；
- 新增 `GET /api/reports/{id}/export.pdf`，前端报表页新增「导出 PDF」。

**文档与验证**

- 新增 `docs/smoke-test-v12.ps1`（38 项断言）；`docs/可用性说明.md` 更新到 v1.2。

### v1.1（安全加固与可交付性）

**阻断性问题修复**

- 新增 `JwtAuthInterceptor`：`/api/**` 全部强制鉴权，未登录 401、越权 403；此前 66 个接口无任何身份校验，可未授权重置管理员密码、执行任意 SQL。
- JWT 密钥外置到 `RBI_JWT_SECRET`，令牌带 `uid/roles` 且校验 issuer；移除应用级 `@CrossOrigin("*")`，CORS 改白名单。
- `/api/sql/execute` 限定 ADMIN + 单条只读校验；数据源连接测试限定 ADMIN（防 SSRF）。
- 填报入库由字符串拼接改为 `PreparedStatement` 参数绑定；`SqlSafeBuilder` 补反斜杠转义。
- 异常响应不再回传内部错误详情，改为「traceId + 服务端日志」。

**能力补齐**

- 审计：新增 `OpLogAspect`，写操作全量落库（人/IP/接口/状态/耗时/错误），`rbi_op_log` 增加 `status/duration_ms/detail` 字段并对存量库自动迁移。
- 调度：新增 `ScheduleRunner`，每 30 秒扫描 cron 并真实执行 refresh/export，邮件任务未配置 SMTP 时明确失败。
- 导出：新增 `ExportService`，报表导出 Excel（保留合并单元格与样式）、数据集导出 CSV，前端报表页可一键下载。
- 登录防爆破：连续失败 5 次锁定 5 分钟（429）。
- 公开健康检查 `/api/health`（免登录），运维详情仍限 ADMIN。

**缺陷修复**

- 数据集 LIMIT 判定改用 DOTALL 正则，多行 SQL 不再被追加第二个 LIMIT 导致报错。
- 查询缓存键从 `params.hashCode()` 改为「数据集 + 数据源 + 规范化参数 JSON」，避免不同参数命中同一条错误数据；缓存加容量上限。
- 运维概览的报表/数据集/仪表板数量改为真实统计（原为硬编码 0）。
- `data.sql` 改 `INSERT IGNORE` 并关闭 `continue-on-error`，重复启动不再静默吞错（已用文件型 H2 连续启动两次验证）。
- 新建用户 / 重置密码改为服务端随机临时口令，不再固定 `123456`；编辑用户时忽略客户端传入的密码字段。
- 前端：启动时调 `/api/auth/me` 校验令牌、401 自动清理登录态跳登录页、菜单按角色渲染、新增导出与调度「立即执行」入口、日志页展示状态/耗时/详情。

**新增文件**

- `backend/src/main/java/com/rbi/security/`（`JwtAuthInterceptor` / `AuthContext` / `CurrentUser` / `RequireRole` / `LoginAttemptGuard`）
- `backend/src/main/java/com/rbi/audit/OpLogAspect.java`
- `backend/src/main/java/com/rbi/schedule/ScheduleRunner.java`
- `backend/src/main/java/com/rbi/export/ExportService.java`
- `backend/src/main/java/com/rbi/controller/HealthController.java`
- `backend/src/main/resources/application-dev.yml`（H2 内存库，免装 MySQL）
- `docs/smoke-test.ps1`（32 项接口断言）
- `docs/可用性说明.md`（能力边界、上线 checklist、验证记录与盲区）

**验证结果**

- `mvn package` 通过，单元测试 6/6 通过；
- 前端 `vite build` 通过；
- 冒烟测试 32/32 通过；
- 打包 jar 内置前端可访问（`/` 返回 SPA、静态资源 200、登录返回 ADMIN 角色）。

