-- ============ 基础用户/角色 ============
INSERT IGNORE INTO rbi_role (id, role_code, role_name, remark) VALUES
 (1,'ADMIN','系统管理员','全部权限'),
 (2,'ANALYST','分析师','报表设计与分析'),
 (3,'VIEWER','查看者','仅查看');

-- 密码均为 123456 的 BCrypt 值（$2a$10$...），便于演示
INSERT IGNORE INTO rbi_user (id, username, password, real_name, dept_id, status) VALUES
 (1,'admin','$2a$10$54xxQ0rZ/Bb2SH/vERvKoOs9YvLmMo3iQyUks3ZM13Z397e1d1cY.','系统管理员',1,1),
 (2,'analyst','$2a$10$54xxQ0rZ/Bb2SH/vERvKoOs9YvLmMo3iQyUks3ZM13Z397e1d1cY.','张分析',2,1),
 (3,'viewer','$2a$10$54xxQ0rZ/Bb2SH/vERvKoOs9YvLmMo3iQyUks3ZM13Z397e1d1cY.','李查看',3,1);

INSERT IGNORE INTO rbi_user_role (user_id, role_id) VALUES (1,1),(2,2),(3,3);

INSERT IGNORE INTO rbi_dept (id, name, parent_id, sort_no) VALUES
 (1,'总部',0,1),(2,'技术部',1,1),(3,'市场部',1,2),(4,'西北大区',1,3);

-- ============ 数据源 ============
INSERT IGNORE INTO rbi_datasource (id, name, type, driver_class, jdbc_url, username, password_enc, remark) VALUES
 (1,'演示测试库','mysql','com.mysql.cj.jdbc.Driver','jdbc:mysql://127.0.0.1:3306/rbi_testdb?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai','rbi','', '测试业务库，含 demo_sales 销售明细（密码由启动时按 rbi.test-datasource 配置自动同步）');

-- ============ 数据集 ============
INSERT IGNORE INTO rbi_dataset (id, name, datasource_id, type, sql_text, params_json, cache_seconds) VALUES
 (1,'销售明细数据集',1,'sql',
  'SELECT order_date, region, province, city, category, product, salesperson, amount, quantity, profit FROM demo_sales WHERE 1=1 ${region_filter} ${date_filter} ${row_filter}',
  '[{"name":"region","label":"大区","type":"select","defaultValue":""},{"name":"startDate","label":"开始日期","type":"date","defaultValue":""}]',
  0);

-- ============ 目录 ============
INSERT IGNORE INTO rbi_directory (id, parent_id, name, type, ref_id, sort_no) VALUES
 (1,0,'示例报表','folder',NULL,1),
 (2,1,'区域销售汇总表','report',1,1),
 (3,0,'分析看板','folder',NULL,2),
 (4,3,'销售经营驾驶舱','dashboard',1,1);

INSERT IGNORE INTO rbi_permission (id, role_id, resource_type, resource_id, perm) VALUES
 (1,3,'directory',1,'view'),(2,3,'report',1,'view'),(3,2,'report',1,'edit');

-- ============ 参数控件 ============
INSERT IGNORE INTO rbi_param (id, name, code, type, options_json, default_value, linked_param) VALUES
 (1,'大区','region','select','["华北","华东","华南","西北","西南"]','',NULL),
 (2,'省份','province','select','[]','','region'),
 (3,'开始日期','startDate','date','NULL','',NULL);

-- ============ 图表 ============
INSERT IGNORE INTO rbi_chart (id, name, type, dataset_id, config_json, dashboard_id) VALUES
 (1,'各大区销售额','bar',1,'{"dim":"region","metric":"amount","agg":"SUM"}',1),
 (2,'产品类别占比','pie',1,'{"dim":"category","metric":"amount","agg":"SUM"}',1),
 (3,'月度销售趋势','line',1,'{"dim":"order_date","metric":"amount","agg":"SUM"}',1),
 (4,'区域利润对比','bar',1,'{"dim":"region","metric":"profit","agg":"SUM"}',1);
INSERT IGNORE INTO rbi_dashboard (id, name, layout_json, background) VALUES
 (1,'销售经营驾驶舱','[]','#0f172a');

-- ============ 填报 ============
INSERT IGNORE INTO rbi_form (id, name, table_name, fields_json, dataset_id) VALUES
 (1,'销售订单填报','demo_sales',
  '[{"field":"order_date","label":"订单日期","type":"date","required":true},{"field":"region","label":"大区","type":"select","options":["华北","华东","华南","西北","西南"],"required":true},{"field":"product","label":"产品","type":"text","required":true},{"field":"amount","label":"金额","type":"number","required":true,"min":0},{"field":"quantity","label":"数量","type":"number","required":true}]',
  1);

-- ============ 示例报表模板：区域销售汇总表 ============
-- 布局说明：A列大区(纵向扩展,无父格)，B列省份(纵向扩展,父格A2)，C列产品类别(横向扩展)，D列销售额合计(不扩展,SUM公式)
INSERT IGNORE INTO rbi_report (id, name, directory_id, dataset_id, layout_json, params_json, current_version, create_by) VALUES
 (1,'区域销售汇总表',1,1,
 '{"cols":5,"rows":12,"cells":[
   {"ref":"A1","value":"区域销售汇总表","type":"text","style":{"bold":true,"fontSize":16,"align":"center","colspan":4}},
   {"ref":"A2","value":"大区","type":"text","style":{"bold":true,"bg":"#e8eaf6"}},
   {"ref":"B2","value":"省份","type":"text","style":{"bold":true,"bg":"#e8eaf6"}},
   {"ref":"C2","value":"产品类别","type":"text","style":{"bold":true,"bg":"#e8eaf6"}},
   {"ref":"D2","value":"销售额","type":"text","style":{"bold":true,"bg":"#e8eaf6"}},
   {"ref":"A3","type":"dataset","field":"region","expand":"v","parent":""},
   {"ref":"B3","type":"dataset","field":"province","expand":"v","parent":"A3"},
   {"ref":"C3","type":"dataset","field":"category","expand":"h","parent":""},
   {"ref":"D3","type":"formula","value":"SUM(amount)","expand":"none","style":{"format":"#,##0.00"}}
 ]}',
 '[{"code":"region","label":"大区","type":"select","options":["华北","华东","华南","西北","西南"]}]',
 1,'admin');
INSERT IGNORE INTO rbi_report_version (id, report_id, version, layout_json, remark, create_by) VALUES
 (1,1,1,'{"cols":5,"rows":12,"cells":[]}','初始版本','admin');

-- ============ 指标 ============
INSERT IGNORE INTO rbi_metric (id, name, code, type, expression, dataset_id, unit, caliber, lineage_json) VALUES
 (1,'销售总额','TOTAL_SALES','atomic','SUM(amount)',1,'元','订单金额求和，不含退款','{"tables":["demo_sales"],"fields":["amount"]}'),
 (2,'销售毛利','GROSS_PROFIT','atomic','SUM(profit)',1,'元','销售额减成本','{"tables":["demo_sales"],"fields":["profit"]}'),
 (3,'毛利率','PROFIT_RATE','derived','GROSS_PROFIT / TOTAL_SALES',1,'%','毛利/销售额','{"metrics":["GROSS_PROFIT","TOTAL_SALES"]}'),
 (4,'客单价','AVG_ORDER','derived','TOTAL_SALES / COUNT(order_date)',1,'元/单','销售总额/订单数','{"metrics":["TOTAL_SALES"]}');

-- ============ 调度 ============
INSERT IGNORE INTO rbi_schedule (id, name, report_id, cron, type, receiver, enabled, next_run_time) VALUES
 (1,'每日销售报表推送',1,'0 0 9 * * ?','email','admin@example.com',1,'2026-11-18 09:00:00');

-- ============ 日志 ============
INSERT IGNORE INTO rbi_op_log (id, username, module, operation, ip, create_time) VALUES
 (1,'admin','平台管理','系统登录','127.0.0.1','2026-11-17 09:12:30'),
 (2,'analyst','报表设计器','编辑报表[区域销售汇总表]','127.0.0.1','2026-11-17 10:25:18');
