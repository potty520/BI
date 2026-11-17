-- ============ 测试业务库表结构 ============
CREATE TABLE IF NOT EXISTS demo_sales (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_date DATE,
  region VARCHAR(32),
  province VARCHAR(32),
  city VARCHAR(32),
  category VARCHAR(32),
  product VARCHAR(64),
  salesperson VARCHAR(32),
  amount DECIMAL(14,2),
  quantity INT,
  profit DECIMAL(14,2)
) ;
