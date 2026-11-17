# 自研报表与 BI 平台

以帆软 FineReport + FineBI 功能体系为参照的自研实现，覆盖规格书 10 个模块，**深挖 M03 报表设计器的单元格扩展引擎**。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3 + Vite 5 + Element Plus + ECharts 5 + Pinia + vue-router |
| 后端 | Java 8 + Spring Boot 2.7.18 + MyBatis-Plus 3.5.5 + JWT + Lombok |
| 存储 | H2 内嵌库（演示零配置）；数据源层可接 MySQL/Oracle/SQLServer/PostgreSQL/达梦/人大金仓/GaussDB |
| 部署 | 前后端打包单 Jar，内嵌 Tomcat，`java -jar` 一条命令启动 |

## 模块实现清单

| 模块 | 功能 | 实现位置 |
| --- | --- | --- |
| M01 数据源接入 | JDBC 多驱动 + 连接测试 + 密码加密 + 文件/API | `Datasource.vue` / `JdbcExecutor` / `SqlSafeBuilder` |
| M02 数据集管理 | SQL 数据集 + `${param}` 安全占位符 + 可视化/文件 + 缓存 + 字段推断 | `Dataset.vue` / `DatasetQueryService` |
| **M03 报表设计器** | **类 Excel 画布 + 单元格扩展引擎（父格/纵横扩展）+ 公式引擎 + 三预览 + 版本回滚** | `Designer.vue` / `ExpandEngine` / `FormulaEngine` |
| M04 参数与查询 | 文本框/下拉/多选/日期/树 + 参数联动 + 注入查询 | `Params.vue` / `SqlSafeBuilder` |
| M05 可视化图表 | 12 种 ECharts 图表 + 维度度量绑定 + 大屏自由拖拽 | `Charts.vue` / `Dashboard.vue` |
| M06 数据填报 | 表单设计 + 必填/类型/范围/正则校验 + 事务批量入库 + 暂存 | `FormFill.vue` / `FormService` |
| M07 自助分析 | 拖拽维度度量 + 聚合 + 过滤排序 + 占比/累计/排名 + 配置转 SQL | `Analysis.vue` / `AnalysisService` |
| M08 指标管理 | 原子/派生指标 + 统一口径 + 血缘 | `Metrics.vue` |
| M09 平台管理 | 用户/角色 RBAC + 目录树 + 权限 + 调度 + 操作日志 | `Users.vue` / `Directory.vue` / `Schedule.vue` / `Logs.vue` |
| M10 部署运维 | 单 Jar + 健康检查接口 + JVM/节点监控 | `Ops.vue` / `AdminController` |

## 远程部署（已上线）

- **访问地址**：http://103.85.25.169:8099/
- **服务器**：103.85.25.169:23195（Ubuntu 22.04 / Java 17 / MySQL 8.0.46）
- **systemd 服务**：`rbi.service`（`systemctl status rbi` 查看状态）
- **部署目录**：`/opt/rbi/`，jar `/opt/rbi/report-bi-platform-1.0.0.jar`，日志 `/opt/rbi/logs/rbi.log`
- **数据库**（均在服务器本地 MySQL 3306）：
  - 后台元数据库 `rbi_platform`（用户/角色/报表/数据集/图表/指标/权限/调度/日志等 18 张表）
  - 测试业务库 `rbi_testdb`（`demo_sales` 销售明细 65 行，填报数据也落这里）
  - 应用账号 `rbi`，连接参数在 `/opt/rbi/rbi.env`（权限 600）

更新部署：把新 jar 上传到 `/opt/rbi/report-bi-platform-1.0.0.jar`，然后 `systemctl restart rbi`。

## 本地快速启动

### 方式一：直接运行打包好的 Jar

```bash
# 需要提供可访问的 MySQL（默认连 127.0.0.1:3306 的 rbi_platform / rbi_testdb）
cd backend/target
java -Dfile.encoding=UTF-8 -jar report-bi-platform-1.0.0.jar --server.port=8088
```

通过环境变量覆盖连接：`RBI_DB_URL` / `RBI_DB_USER` / `RBI_DB_PWD`（后台库），`RBI_TEST_URL` / `RBI_TEST_USER` / `RBI_TEST_PWD`（测试库）。

数据库初始化脚本在 `backend/src/main/resources/db/`：`schema.sql`+`data.sql`（后台库），`testdb_schema.sql`+`testdb_data.sql`（测试库）。

浏览器打开 http://localhost:8088 ，演示账号（密码均为 `123456`）：`admin` / `analyst` / `viewer`。

### 方式二：开发模式

```bash
# 后端
cd backend && mvn spring-boot:run
# 前端（另开终端）
cd frontend && npm run dev
# 打开 http://localhost:5173
```

## 深挖重点：单元格扩展引擎

这是帆软类报表与普通 BI 工具的根本区别（规格书 5.1 头号风险）。核心实现在 `backend/src/main/java/com/rbi/engine/`：

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
- 验证报告：`docs/远程部署验证报告.html`（含 23 项 API 验证结果与浏览器实测截图）。
