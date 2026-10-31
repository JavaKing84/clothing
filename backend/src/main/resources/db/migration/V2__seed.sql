-- ============================================================================
-- 衣恋时尚女装 · 演示种子数据 V2
-- 说明：
--   1) 只放"主数据"（门店/员工/仓库/供货商/款式/SKU/条码/期初库存）。
--      销售流水不在这里手写——手写流水必然与库存算不平，改由 DemoSeeder 走真实业务代码生成，
--      这样"演示数据"本身就是一次回归测试（见 infrastructure/demo/DemoSeeder.java）。
--   2) 所有账号初始密码均为 123456（PBKDF2-SHA256，21 万次迭代，每人独立盐）。
-- ============================================================================

INSERT INTO sys_shop (id, shop_name, address, contact_phone)
VALUES (1, '衣恋时尚女装', '云南省临沧市临翔区', '15987250881');

INSERT INTO sys_user (id, phone, password, nickname, role, shop_id)
VALUES (1, '13800000000', 'pbkdf2_sha256$210000$WzXQBNNvrumbOlSLqPP68g==$F92d/jrZ6PTZ2vQWpXsXumyUcGjNtvowFdMDxIHzNG0=', '张莉', 'OWNER', 1),
       (2, '13800000001', 'pbkdf2_sha256$210000$cRBMzQ/cbYLWMfe36l6dYQ==$6efO7y8SQZxXZfnz4Wjx47rRQHCKs8WxKkmPD3N9PR4=', '李静', 'OWNER', 1),
       (3, '13900000001', 'pbkdf2_sha256$210000$Q8qrXIYljEbR3LrpoMQHWQ==$DlUPVH59qgEe+NO7pKGawsmwtc8X9bN6rSlMshWqYqY=', '杨小满', 'CLERK', 1),
       (4, '13900000002', 'pbkdf2_sha256$210000$AirkIJES3cpAHoJAQ3hPGA==$6nZ5pNjUfWk6JwB9wKGxn6m8UAXSA47tUp+aMWTokqA=', '罗婷', 'CLERK', 1);

-- 角色权限矩阵（ADR-06）：一条 INSERT 覆盖 OWNER 全部权限码
INSERT INTO sys_role_permission (role, perm_code)
VALUES ('OWNER', 'stock:read'), ('OWNER', 'stock:write'), ('OWNER', 'stock:cost:read'),
       ('OWNER', 'catalog:write'), ('OWNER', 'inbound:create'), ('OWNER', 'sale:create'),
       ('OWNER', 'sale:void'), ('OWNER', 'transfer:write'), ('OWNER', 'stocktake:write'),
       ('OWNER', 'ledger:reverse'), ('OWNER', 'ledger:reverse:self'), ('OWNER', 'report:read'),
       ('OWNER', 'report:profit:read'), ('OWNER', 'export:run'), ('OWNER', 'user:manage'),
       -- 店员：能干活、能看自己的业绩，但看不到进价/利润，不能改商品资料、不能撤销已结账单
       ('CLERK', 'stock:read'), ('CLERK', 'stock:write'), ('CLERK', 'inbound:create'),
       ('CLERK', 'sale:create'), ('CLERK', 'transfer:write'), ('CLERK', 'stocktake:write'),
       ('CLERK', 'ledger:reverse:self'), ('CLERK', 'report:read'), ('CLERK', 'export:run');

INSERT INTO sys_warehouse (id, shop_id, warehouse_no, warehouse_name, is_default)
VALUES (1, 1, 'WH-MAIN', '临沧总店', 1),
       (2, 1, 'WH-SECOND', '临沧二店', 0);

INSERT INTO cat_supplier (id, supplier_name, contact, phone)
VALUES (1, '杭州四季青·A档', '陈姐', '13712340001'),
       (2, '广州十三行·B档', '林哥', '13712340002'),
       (3, '昆明螺蛳湾·C档', '赵姐', '13712340003');

INSERT INTO cat_spu (id, spu_no, spu_name, category, season, supplier_id, purchase_price, retail_price, special_price,
                     purchase_date, status)
VALUES (1, 'W26-1001', '法式碎花连衣裙', '连衣裙', '春季', 1, 118.00, 399.00, NULL, '2026-09-12', 1),
       (2, 'W26-1002', '羊毛混纺开衫', '针织衫', '秋季', 1, 96.00, 329.00, NULL, '2026-09-18', 1),
       (3, 'W26-1003', '高腰阔腿牛仔裤', '裤装', '四季', 2, 78.00, 259.00, NULL, '2026-09-20', 1),
       (4, 'W26-1004', '小香风短外套', '外套', '冬季', 2, 168.00, 559.00, 499.00, '2026-10-02', 1),
       (5, 'W26-1005', '纯棉基础T恤', '上衣', '夏季', 3, 32.00, 99.00, NULL, '2026-08-30', 1),
       (6, 'W26-1006', '醋酸垂感半身裙', '半裙', '夏季', 3, 88.00, 299.00, NULL, '2026-09-05', 1);

-- SKU 用"尺码 × 颜色"笛卡尔积生成：手工写 33 行必然出错，用 SQL 生成既短又不会漏
INSERT INTO cat_sku (spu_id, sku_code, size, color)
SELECT s.id,
       CONCAT(s.spu_no, '-', c.code, '-', z.size),
       z.size,
       c.color
FROM cat_spu s
         JOIN (SELECT 'S' AS size
               UNION ALL
               SELECT 'M'
               UNION ALL
               SELECT 'L'
               UNION ALL
               SELECT 'XL') z
         JOIN (SELECT '碎花蓝' AS color, 'FLB' AS code
               UNION ALL
               SELECT '杏色', 'AP'
               UNION ALL
               SELECT '燕麦色', 'OT'
               UNION ALL
               SELECT '黑色', 'BK'
               UNION ALL
               SELECT '深蓝', 'NV'
               UNION ALL
               SELECT '米白', 'IV'
               UNION ALL
               SELECT '白色', 'WH'
               UNION ALL
               SELECT '雾霾蓝', 'HZ') c
WHERE (s.spu_no = 'W26-1001' AND c.code IN ('FLB', 'AP') AND z.size IN ('S', 'M', 'L', 'XL'))
   OR (s.spu_no = 'W26-1002' AND c.code IN ('OT', 'BK') AND z.size IN ('S', 'M', 'L'))
   OR (s.spu_no = 'W26-1003' AND c.code = 'NV' AND z.size IN ('S', 'M', 'L', 'XL'))
   OR (s.spu_no = 'W26-1004' AND c.code = 'IV' AND z.size IN ('S', 'M', 'L'))
   OR (s.spu_no = 'W26-1005' AND c.code IN ('WH', 'BK') AND z.size IN ('S', 'M', 'L', 'XL'))
   OR (s.spu_no = 'W26-1006' AND c.code IN ('HZ', 'BK') AND z.size IN ('S', 'M'));

-- 每个 SKU 两个码：厂家条码（69 开头 13 位）+ 自印码（YL- 开头），覆盖 Q24/Q25/Q27 三种现实
INSERT INTO cat_barcode (barcode, sku_id, source)
SELECT CONCAT('69', LPAD(id, 11, '0')), id, 1 FROM cat_sku
UNION ALL
SELECT CONCAT('YL-', sku_code), id, 2 FROM cat_sku;

-- 期初库存：总店为主，二店少量；用取模制造 0/1 件，天然产生"待补货"（Q55/Q56）
INSERT INTO inv_sku_stock (sku_id, warehouse_id, quantity, safety_stock)
SELECT id, 1, (id * 7) % 9, 1 FROM cat_sku
UNION ALL
SELECT id, 2, (id * 5) % 6, 1 FROM cat_sku;
