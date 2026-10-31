-- ============================================================================
-- 衣恋时尚女装 · 扫码出入库系统 · 表结构 V1
-- 约定：
--   1) 所有金额 decimal(12,2)，不用 float/double（二进制浮点存钱是事故源头）
--   2) 所有时间 datetime(3)，毫秒精度（同一秒内的多次扫码要能排序）
--   3) 主键 bigint 自增：物理主键，无业务含义；业务单号另有 xxx_no 列
--   4) 软删除 del_flag：只对"资料类"表生效；流水表永不删除（ADR-09）
--   5) 唯一索引承担幂等：inv_stock_flow.uk_scan_token、biz_inbound_order_item.uk_order_barcode
-- ============================================================================

-- ---------------------------------------------------------------- 租户与账号
CREATE TABLE sys_shop
(
    id            bigint       NOT NULL AUTO_INCREMENT COMMENT '门店ID',
    shop_name     varchar(64)  NOT NULL COMMENT '店铺名称',
    address       varchar(255) DEFAULT NULL COMMENT '所在城市/地址',
    contact_phone varchar(20)  DEFAULT NULL COMMENT '联系电话',
    status        tinyint      NOT NULL DEFAULT 1 COMMENT '1 正常 0 停用',
    create_time   datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '门店（《答卷》Q1/Q4）';

CREATE TABLE sys_user
(
    id          bigint      NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    phone       varchar(20) NOT NULL COMMENT '登录手机号（Q67）',
    password    varchar(200) NOT NULL COMMENT 'PBKDF2-SHA256 哈希，格式 pbkdf2_sha256$iter$salt$hash',
    nickname    varchar(32) NOT NULL COMMENT '姓名',
    role        varchar(16) NOT NULL COMMENT 'OWNER 店主 / CLERK 店员（Q13）',
    shop_id     bigint      NOT NULL COMMENT '所属门店',
    status      tinyint     NOT NULL DEFAULT 1 COMMENT '1 在职 0 离职（离职不禁用历史流水外键）',
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    del_flag    tinyint     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone),
    KEY idx_shop_role (shop_id, role)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '员工账号';

-- RBAC：角色 -> 权限码。权限码写进表而不是硬编码在注解里，
-- 因为"店员能不能撤单"这类规则店主随时会改（Q69），改表比改代码发版快。
CREATE TABLE sys_role_permission
(
    role      varchar(16) NOT NULL COMMENT '角色',
    perm_code varchar(64) NOT NULL COMMENT '权限码，见 Permission 枚举',
    PRIMARY KEY (role, perm_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '角色权限矩阵（Q68/Q69）';

CREATE TABLE sys_warehouse
(
    id             bigint      NOT NULL AUTO_INCREMENT,
    shop_id        bigint      NOT NULL,
    warehouse_no   varchar(32) NOT NULL COMMENT '仓库编码',
    warehouse_name varchar(64) NOT NULL COMMENT '仓库名称（Q20 两个门店）',
    is_default     tinyint     NOT NULL DEFAULT 0,
    create_time    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    del_flag       tinyint     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_no (warehouse_no),
    KEY idx_shop (shop_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '仓库/门店';

-- 业务单号发号器：单行 UPDATE 自增，行锁串行化，天然无重复。
-- 为什么不用 UUID 当单号：店员要在电话里念单号（"你把 SO20261029-000012 那单撤了"）。
CREATE TABLE sys_seq
(
    biz      varchar(16) NOT NULL COMMENT '业务类型 FLOW/IN/SO/RT/TF/ST/LS',
    seq_date date        NOT NULL COMMENT '按日归零',
    cur_val  bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (biz, seq_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '按日单号序列';

-- ---------------------------------------------------------------- 商品主数据
CREATE TABLE cat_supplier
(
    id            bigint      NOT NULL AUTO_INCREMENT,
    supplier_name varchar(64) NOT NULL COMMENT '供货商/厂牌（Q31 必须）',
    contact       varchar(32)  DEFAULT NULL,
    phone         varchar(20)  DEFAULT NULL,
    create_time   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    del_flag      tinyint     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_name (supplier_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '供货商';

CREATE TABLE cat_spu
(
    id             bigint        NOT NULL AUTO_INCREMENT COMMENT '款式ID',
    spu_no         varchar(32)   NOT NULL COMMENT '货号（Q28 必须，扫码失败时手输的就是它）',
    spu_name       varchar(128)  NOT NULL COMMENT '款式名称',
    category       varchar(32)   NOT NULL COMMENT '品类（Q34 必须）',
    season         varchar(16)   NOT NULL COMMENT '季节（Q34 必须）',
    supplier_id    bigint        NOT NULL COMMENT '供货商（Q31 必须）',
    purchase_price decimal(12, 2) NOT NULL COMMENT '进货价（Q32 必须；CLERK 不可见，Q68）',
    retail_price   decimal(12, 2) NOT NULL COMMENT '零售价/吊牌价（Q33 必须）',
    special_price  decimal(12, 2) DEFAULT NULL COMMENT '特价（Q35）',
    purchase_date  date           DEFAULT NULL COMMENT '拿货日期（Q35）',
    status         tinyint       NOT NULL DEFAULT 1 COMMENT '1 在售 0 停售',
    version        int           NOT NULL DEFAULT 0 COMMENT '乐观锁：并发改价时防止后写覆盖先写',
    create_time    datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time    datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    del_flag       tinyint       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_spu_no (spu_no),
    KEY idx_supplier (supplier_id),
    KEY idx_category_season (category, season),
    KEY idx_update_time (update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '款式 SPU';

CREATE TABLE cat_sku
(
    id          bigint      NOT NULL AUTO_INCREMENT,
    spu_id      bigint      NOT NULL,
    sku_code    varchar(64) NOT NULL COMMENT 'SKU 编码 = 货号-颜色-尺码',
    size        varchar(16) NOT NULL COMMENT '尺码（Q29 必须）',
    color       varchar(16) NOT NULL COMMENT '颜色（Q30 必须）',
    status      tinyint     NOT NULL DEFAULT 1,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    del_flag    tinyint     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code),
    -- 同一款同一颜色同一尺码只能有一个 SKU，否则矩阵视图会出现两行同键
    UNIQUE KEY uk_spu_size_color (spu_id, size, color),
    KEY idx_spu (spu_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'SKU（款×色×码，Q52 矩阵的行列基础）';

CREATE TABLE cat_barcode
(
    id          bigint      NOT NULL AUTO_INCREMENT,
    barcode     varchar(64) NOT NULL COMMENT '吊牌二维码/条形码内容（Q26）',
    sku_id      bigint      NOT NULL,
    source      tinyint     NOT NULL DEFAULT 1 COMMENT '1 厂家提供 2 自印（Q24 没有二维码的要自印）',
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    del_flag    tinyint     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 唯一的是"码->SKU"的映射，不是"码->单件实物"（ADR-08）
    UNIQUE KEY uk_barcode (barcode),
    KEY idx_sku (sku_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '条码映射';

-- ---------------------------------------------------------------- 库存核心
CREATE TABLE inv_sku_stock
(
    id           bigint      NOT NULL AUTO_INCREMENT,
    sku_id       bigint      NOT NULL,
    warehouse_id bigint      NOT NULL,
    quantity     int         NOT NULL DEFAULT 0 COMMENT '账面数量：唯一事实源（ADR-03）',
    locked_qty   int         NOT NULL DEFAULT 0 COMMENT 'Redis 预扣在途量，仅用于展示与对账',
    safety_stock int         NOT NULL DEFAULT 1 COMMENT '安全库存（Q55 低于 1 件提醒）',
    version      int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本，盘点/资料变更用',
    create_time  datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time  datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    del_flag     tinyint     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_wh (sku_id, warehouse_id),
    KEY idx_wh_qty (warehouse_id, quantity)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '分仓库存（Q20 双门店）';

CREATE TABLE inv_stock_flow
(
    id             bigint        NOT NULL AUTO_INCREMENT,
    flow_no        varchar(40)   NOT NULL COMMENT '流水号 FL+yyyyMMdd+6位',
    flow_type      tinyint       NOT NULL COMMENT '1入库 2出库 3撤销冲正 4调拨出 5调拨入 6盘点调整 7退货入库',
    sku_id         bigint        NOT NULL,
    spu_id         bigint        NOT NULL,
    spu_no         varchar(32)   NOT NULL COMMENT '冗余货号：查流水不联表（Q61 按款式筛）',
    spu_name       varchar(128)  NOT NULL,
    size           varchar(16)   NOT NULL,
    color          varchar(16)   NOT NULL,
    warehouse_id   bigint        NOT NULL,
    quantity       int           NOT NULL COMMENT '数量，恒为正；方向由 flow_type + signed_qty 表达',
    signed_qty     int           NOT NULL COMMENT '带符号：入库+、出库-；SUM(signed_qty) 即库存增量（ADR-03）',
    before_qty     int           NOT NULL COMMENT '变更前账面',
    after_qty      int           NOT NULL COMMENT '变更后账面',
    sale_price     decimal(12, 2) DEFAULT NULL COMMENT '实收金额（出库/退货）',
    purchase_price decimal(12, 2) DEFAULT NULL COMMENT '进货价（入库；CLERK 查询时不 SELECT 此列）',
    pay_type       varchar(16)    DEFAULT NULL,
    operator_id    bigint        NOT NULL,
    operator_name  varchar(32)   NOT NULL,
    biz_no         varchar(40)    DEFAULT NULL COMMENT '关联单据号（入库单/销售单/调拨单/盘点单）',
    scan_token     varchar(64)    DEFAULT NULL COMMENT '扫码幂等令牌（ADR-04 第2层）',
    client_token   varchar(64)    DEFAULT NULL COMMENT '离线批次条目令牌（ADR-05）',
    status         tinyint       NOT NULL DEFAULT 1 COMMENT '1 正常 2 已撤销',
    revert_flow_no varchar(40)    DEFAULT NULL COMMENT '本冲正针对的原流水号',
    remark         varchar(255)   DEFAULT NULL,
    create_time    datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '业务发生时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_flow_no (flow_no),
    -- 幂等的物理保证：同一令牌只可能有一条流水。并发重复提交在这里被 MySQL 拦住。
    UNIQUE KEY uk_scan_token (scan_token),
    -- 离线补录的幂等键：同一条离线记录只可能落一条流水
    UNIQUE KEY uk_client_token (client_token),
    KEY idx_sku_time (sku_id, create_time),
    KEY idx_operator_time (operator_id, create_time),
    KEY idx_time_type (create_time, flow_type),
    KEY idx_biz_no (biz_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '出入库流水（只追加，ADR-09）';

CREATE TABLE inv_scan_token
(
    token       varchar(64)  NOT NULL COMMENT '客户端生成的幂等令牌',
    sku_id      bigint       DEFAULT NULL,
    result_json varchar(1000) DEFAULT NULL COMMENT '首次执行结果快照：重复请求原样返回',
    expire_at   datetime(3)  NOT NULL,
    create_time datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (token),
    KEY idx_expire (expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '扫码幂等令牌表';

-- ---------------------------------------------------------------- 入库
CREATE TABLE biz_inbound_order
(
    id            bigint        NOT NULL AUTO_INCREMENT,
    order_no      varchar(40)   NOT NULL COMMENT '入库单号（Q39 进货单号）',
    warehouse_id  bigint        NOT NULL,
    supplier_id   bigint        NOT NULL COMMENT 'Q39 供货商',
    operator_id   bigint        NOT NULL COMMENT 'Q39 操作人（取自 JWT）',
    operator_name varchar(32)   NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 录入中 / SUBMITTED 已入库 / CANCELLED',
    total_qty     int           NOT NULL DEFAULT 0 COMMENT '累计件数（Q42）',
    total_cost    decimal(12, 2) NOT NULL DEFAULT 0.00,
    remark        varchar(255)   DEFAULT NULL,
    submit_time   datetime(3)    DEFAULT NULL,
    create_time   datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_create_time (create_time),
    KEY idx_supplier (supplier_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '入库单（Q18/Q39/Q40）';

CREATE TABLE biz_inbound_order_item
(
    id             bigint        NOT NULL AUTO_INCREMENT,
    order_id       bigint        NOT NULL,
    barcode        varchar(64)   NOT NULL COMMENT '扫到的原始码（Q41 同单同码只计一次）',
    sku_id         bigint        NOT NULL,
    qty            int           NOT NULL DEFAULT 1 COMMENT 'Q19 整箱扫时 >1',
    purchase_price decimal(12, 2) NOT NULL,
    create_time    datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- Q41「同一个码重复扫码不做处理」的物理实现：唯一索引，第二次插入失败即"不处理"
    UNIQUE KEY uk_order_barcode (order_id, barcode),
    KEY idx_order (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '入库明细';

-- ---------------------------------------------------------------- 销售
CREATE TABLE biz_sale_order
(
    id              bigint        NOT NULL AUTO_INCREMENT,
    order_no        varchar(40)   NOT NULL COMMENT '销售单号',
    warehouse_id    bigint        NOT NULL,
    operator_id     bigint        NOT NULL COMMENT '开单店员（Q13 业绩归属）',
    operator_name   varchar(32)   NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 选购中 / PAID 已结账 / CANCELLED 已取消',
    total_qty       int           NOT NULL DEFAULT 0,
    tag_amount      decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '吊牌价合计',
    discount_amount decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '整单折扣（Q45）',
    received_amount decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '实收金额（Q49 手输）',
    cost_amount     decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '成本合计，用于毛利（Q11；CLERK 不可见）',
    pay_type        varchar(16)    DEFAULT NULL COMMENT 'CASH/WECHAT/ALIPAY/CARD（Q45）',
    customer_name   varchar(32)    DEFAULT NULL COMMENT '客户信息（Q45，选填）',
    customer_phone  varchar(20)    DEFAULT NULL,
    remark          varchar(255)   DEFAULT NULL,
    client_token    varchar(64)    DEFAULT NULL COMMENT '离线批次令牌，防整单重复入账',
    checkout_time   datetime(3)    DEFAULT NULL,
    create_time     datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_client_token (client_token),
    KEY idx_create_time (create_time),
    KEY idx_operator_time (operator_id, create_time),
    KEY idx_status_time (status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '销售单头（Q15/Q45）';

CREATE TABLE biz_sale_order_item
(
    id           bigint        NOT NULL AUTO_INCREMENT,
    order_id     bigint        NOT NULL,
    sku_id       bigint        NOT NULL,
    spu_id       bigint        NOT NULL,
    spu_no       varchar(32)   NOT NULL,
    spu_name     varchar(128)  NOT NULL,
    size         varchar(16)   NOT NULL,
    color        varchar(16)   NOT NULL,
    qty          int           NOT NULL DEFAULT 1 COMMENT 'Q50 同款同码再扫一次就 +1，不新增行',
    tag_price    decimal(12, 2) NOT NULL COMMENT '吊牌价',
    actual_price decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '折后实收（毛利按此计算）',
    cost_price   decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '进货价快照（历史成本不可变）',
    flow_no      varchar(40)    DEFAULT NULL COMMENT '最近一次扣减流水',
    create_time  datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_sku (order_id, sku_id),
    KEY idx_order (order_id),
    KEY idx_spu (spu_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '销售明细';

-- ---------------------------------------------------------------- 退货（Q17）
CREATE TABLE biz_return_order
(
    id             bigint        NOT NULL AUTO_INCREMENT,
    order_no       varchar(40)   NOT NULL,
    sale_order_no  varchar(40)    DEFAULT NULL COMMENT '原销售单（无单退货可空）',
    warehouse_id   bigint        NOT NULL,
    return_type    varchar(16)   NOT NULL DEFAULT 'RETURN' COMMENT 'RETURN 退货 / EXCHANGE 换货',
    qty            int           NOT NULL DEFAULT 1,
    refund_amount  decimal(12, 2) NOT NULL DEFAULT 0.00,
    restock        tinyint       NOT NULL DEFAULT 1 COMMENT '1 回补库存 / 0 直接报损不回补',
    reason         varchar(255)   DEFAULT NULL,
    operator_id    bigint        NOT NULL,
    operator_name  varchar(32)   NOT NULL,
    create_time    datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '退货单';

CREATE TABLE biz_return_order_item
(
    id            bigint        NOT NULL AUTO_INCREMENT,
    return_id     bigint        NOT NULL,
    sku_id        bigint        NOT NULL,
    qty           int           NOT NULL DEFAULT 1,
    refund_amount decimal(12, 2) NOT NULL DEFAULT 0.00,
    flow_no       varchar(40)    DEFAULT NULL,
    create_time   datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_return (return_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '退货明细';

-- ---------------------------------------------------------------- 调拨（Q21）
CREATE TABLE biz_transfer_order
(
    id                bigint      NOT NULL AUTO_INCREMENT,
    order_no          varchar(40) NOT NULL,
    from_warehouse_id bigint      NOT NULL,
    to_warehouse_id   bigint      NOT NULL,
    qty               int         NOT NULL DEFAULT 0,
    status            varchar(16) NOT NULL DEFAULT 'DONE' COMMENT 'DONE 已完成（本项目不做在途）',
    operator_id       bigint      NOT NULL,
    operator_name     varchar(32) NOT NULL,
    remark            varchar(255) DEFAULT NULL,
    create_time       datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '调拨单';

CREATE TABLE biz_transfer_order_item
(
    id          bigint      NOT NULL AUTO_INCREMENT,
    transfer_id bigint      NOT NULL,
    sku_id      bigint      NOT NULL,
    qty         int         NOT NULL,
    out_flow_no varchar(40) DEFAULT NULL,
    in_flow_no  varchar(40) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_transfer (transfer_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '调拨明细';

-- ---------------------------------------------------------------- 盘点（Q57/Q58）
CREATE TABLE inv_stocktake_task
(
    id            bigint      NOT NULL AUTO_INCREMENT,
    task_no       varchar(40) NOT NULL COMMENT '盘点单号',
    warehouse_id  bigint      NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'DOING' COMMENT 'DOING 盘点中 / DONE 已完成',
    total_items   int         NOT NULL DEFAULT 0 COMMENT '参与盘点的 SKU 数',
    scanned_items int         NOT NULL DEFAULT 0,
    diff_items    int         NOT NULL DEFAULT 0 COMMENT '差异 SKU 数',
    operator_id   bigint      NOT NULL,
    operator_name varchar(32) NOT NULL,
    finish_time   datetime(3)  DEFAULT NULL,
    create_time   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_no (task_no),
    KEY idx_wh_status (warehouse_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '盘点任务';

CREATE TABLE inv_stocktake_item
(
    id               bigint      NOT NULL AUTO_INCREMENT,
    task_id          bigint      NOT NULL,
    sku_id           bigint      NOT NULL,
    book_qty         int         NOT NULL COMMENT '建单时快照的账面数（Q58 自动比对）',
    actual_qty       int         NOT NULL DEFAULT 0 COMMENT '扫到的实际数',
    book_version     int         NOT NULL DEFAULT 0 COMMENT '快照时的版本，用于识别"盘点期间有销售"',
    create_time      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_sku (task_id, sku_id),
    KEY idx_task (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '盘点明细';

-- ---------------------------------------------------------------- 挂账（Q48）
CREATE TABLE inv_loss_case
(
    id            bigint      NOT NULL AUTO_INCREMENT,
    case_no       varchar(40) NOT NULL,
    sku_id        bigint      NOT NULL,
    warehouse_id  bigint      NOT NULL,
    qty           int         NOT NULL COMMENT '账上有、实物找不到的件数',
    reason        varchar(255) DEFAULT NULL,
    status        varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 挂账中 / CLOSED 已核销',
    operator_id   bigint      NOT NULL,
    operator_name varchar(32) NOT NULL,
    close_time    datetime(3)  DEFAULT NULL,
    create_time   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_no (case_no),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '实物找不到挂账';

-- ---------------------------------------------------------------- 基础设施表
CREATE TABLE msg_outbox
(
    id            bigint      NOT NULL AUTO_INCREMENT,
    biz_no        varchar(64) NOT NULL COMMENT '业务单号，消费端幂等用',
    msg_type      varchar(32) NOT NULL COMMENT 'STOCK_CHANGED / SALE_PAID / STOCK_NEGATIVE',
    exchange      varchar(64) NOT NULL,
    routing_key   varchar(64) NOT NULL,
    payload       text        NOT NULL,
    status        tinyint     NOT NULL DEFAULT 0 COMMENT '0 待投递 1 已投递 2 投递失败待重试 3 死信',
    retry_count   int         NOT NULL DEFAULT 0,
    next_retry_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    create_time   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_msg (biz_no, msg_type),
    KEY idx_status_retry (status, next_retry_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '事务性发件箱：DB 提交与 MQ 投递的最终一致解法';

CREATE TABLE rpt_daily_sales(
    id          bigint        NOT NULL AUTO_INCREMENT,
    stat_date   date          NOT NULL,
    spu_id      bigint        NOT NULL,
    sku_id      bigint        NOT NULL,
    qty         int           NOT NULL DEFAULT 0,
    amount      decimal(12, 2) NOT NULL DEFAULT 0.00,
    cost_amount decimal(12, 2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (id),
    UNIQUE KEY uk_date_sku (stat_date, sku_id),
    KEY idx_date (stat_date),
    KEY idx_spu (spu_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '销售日聚合（MQ 消费端维护，Q12 首页看板）';

CREATE TABLE scan_reconcile_batch
(
    id         bigint      NOT NULL AUTO_INCREMENT,
    batch_no   varchar(64) NOT NULL COMMENT '客户端批次号',
    device_id  varchar(64) NOT NULL,
    total      int         NOT NULL DEFAULT 0,
    accepted   int         NOT NULL DEFAULT 0,
    duplicated int         NOT NULL DEFAULT 0,
    conflicted int         NOT NULL DEFAULT 0,
    rejected   int         NOT NULL DEFAULT 0,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '离线对账批次（Q23）';

CREATE TABLE scan_reconcile_item
(
    id           bigint      NOT NULL AUTO_INCREMENT,
    batch_id     bigint      NOT NULL,
    client_token varchar(64) NOT NULL,
    device_seq   bigint      NOT NULL COMMENT '设备内单调序号，排序依据（不信任客户端时钟）',
    barcode      varchar(64) NOT NULL,
    scan_type    tinyint     NOT NULL COMMENT '1 入库 2 出库',
    quantity     int         NOT NULL DEFAULT 1,
    result       varchar(16) NOT NULL COMMENT 'ACCEPTED / DUPLICATED / CONFLICTED / REJECTED',
    flow_no      varchar(40)  DEFAULT NULL,
    reason       varchar(64)  DEFAULT NULL,
    create_time  datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_client_token (client_token),
    KEY idx_batch (batch_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '离线对账明细（逐条裁决，Q23）';

-- 消费端幂等表：RabbitMQ 是"至少一次"投递，重复消费必须有账可查、有键可挡。
-- 主键 (biz_no, msg_type) 让同一业务消息只可能被成功消费一次。
CREATE TABLE msg_consumed
(
    biz_no      varchar(64) NOT NULL,
    msg_type    varchar(32) NOT NULL,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (biz_no, msg_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '消费端幂等记录';
