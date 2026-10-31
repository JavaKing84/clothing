# Stage 5 · DoD 验收清单（Java 版）

> 验收对象：`D:\!javaproject\clothing`
> 验收方式：**逐条执行命令 → 粘贴真实输出 → 判定 PASS/FAIL**。任何一条没有真实输出就不写 PASS。
> 验收时间：2026-10-29（容器 `docker ps` 中 5 个 `yilian-java-*` 均 Healthy）

---

## 一、DoD 硬指标（用户给定，逐条对照）

### DoD-1 `docker compose up` 一键拉起

```bash
cd D:\!javaproject\clothing && docker compose up -d && docker ps --filter name=yilian-java
```
```
yilian-java-api    Up (healthy)   0.0.0.0:18081->8080/tcp
yilian-java-web    Up             0.0.0.0:8081->80/tcp
yilian-java-mq     Up (healthy)   0.0.0.0:15673->5672/tcp, 0.0.0.0:25672->15672/tcp
yilian-java-redis  Up (healthy)   0.0.0.0:16379->6379/tcp
yilian-java-mysql  Up (healthy)   0.0.0.0:13306->3306/tcp
```
**PASS** —— 5 个服务全部起来，4 个带 healthcheck 的均为 `healthy`；
`depends_on: condition: service_healthy` 保证后端在 MySQL/Redis/RabbitMQ 就绪后才启动，
不会出现"首次启动因连不上库而崩溃、要手动重启"的经典问题。

### DoD-2 扫码入库，库存 +1

真实跑的一条链路（`YL-W26-1003-NV-L`，SKU 23，店 1）：

```bash
# 入库前
GET  /api/v1/stock/sku/23?warehouseId=1
POST /api/v1/inbound/orders            {"warehouseId":1,"supplierId":2,"remark":"DoD-2 验收"}
POST /api/v1/inbound/orders/{no}/items {"barcode":"YL-W26-1003-NV-L","qty":1}
POST /api/v1/inbound/orders/{no}/submit
# 入库后
GET  /api/v1/stock/sku/23?warehouseId=1
```
```
入库前 warehouses: [{"warehouseId":1,"quantity":8},{"warehouseId":2,"quantity":1}]
单号 IN20261030-000002
扫码返回 totalQty = 1
入库后 warehouses: [{"warehouseId":1,"quantity":9},{"warehouseId":2,"quantity":1}]
```
**PASS** —— 8 → 9，且提交时同事务写 `inv_stock_flow`（`flowType=IN`）并把新余量回写 Redis 闸门。
自动化证据：`ConcurrencyIT.gateWrittenBackAfterReplenish`（卖空→入库 3→立刻可卖）；
`IntegrationTestSupport.setStock()` 走的正是"入库单提交"这条真实路径，不走 SQL 直灌。

### DoD-3 输入货号 → 3 秒内出矩阵视图

```bash
# 连续 5 次 GET /api/v1/stock/matrix?warehouseId=1（全新库 + 刚做了一次入库）
```
```
matrix colors=8 rows=20 grandTotal=129      ← 128 + DoD-2 入库的 1 件
5 次耗时(ms): [10.5, 11.0, 9.8, 8.9, 9.2]   min = 8.9
```
**PASS** —— 最慢 11 ms（阈值 3000 ms，余量 270 倍）。8 色 × 20 行、129 件在库。
透视在应用层做（`StockMatrixBuilder`），SQL 只取长表，避免 `GROUP BY + 动态列` 的不可控执行计划；
行序由 `sizeOrder()` 业务序决定，不依赖 `ORDER BY FIELD(...)`。

### DoD-4 两个人同时扫码，库存不被覆盖

```bash
# ConcurrencyIT.noLostUpdate：24 线程同起跑线（CountDownLatch）并发扫同一 SKU
```
```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ConcurrencyIT
```
断言内容：24 次全部成功 → 最终 `quantity == before − 24`（精确，不是"大约"）。
**PASS** —— 三层防御：`SELECT … FOR UPDATE` 行锁 + `quantity = quantity - ?` 原子算术 +
`uk_scan_token` 唯一索引。实测把原子算术改回 `SET quantity = ?`（Java 里算的值）后，
该用例立刻变成丢失更新 —— 说明测试真的在测这件事，不是摆设。

### DoD-5 核心接口 Swagger 有请求示例

```bash
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18081/openapi.json   # → 200
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18081/doc            # → 302（跳 Swagger UI，200）
```

真实输出（`application.yml` 把文档路径改成了 `/openapi.json`，UI 改成了 `/doc`，
原来的 `/v3/api-docs` 和 `/swagger-ui/index.html` 因此是 404 —— 这是配置后的正确行为，不是缺陷）：

```
openapi.json -> 200
paths 48
title 衣恋时尚女装 · 扫码出入库系统 API
securitySchemes ['bearerAuth']
sample ['/api/v1/sales/orders/{orderNo}/items', '/api/v1/inbound/orders/{orderNo}/items', '/api/v1/sales/orders/{orderNo}/items/{itemId}']
```

**PASS** —— 48 条 path 全部收录（11 个 Controller / 45 个端点 + 若干同路径多方法）；
`bearerAuth` 安全方案已注册，UI 右上角 "Authorize" 填 token 即可试接口；
关键请求体带 `@Schema(example=…)`，打开即预填。

### DoD-6 并发不超卖测试用例

```bash
# ConcurrencyIT.noOversell：库存置 5，30 个线程同时扫 1 件
```
真实日志（节选，25 条同形）：
```
WARN [8960b90032604744] GlobalExceptionHandler - 业务异常 POST /api/v1/sales/orders/SO20261029-000017/items code=1001 msg=库存不足
WARN [341f22173719418e] GlobalExceptionHandler - 业务异常 POST ... code=1001 msg=库存不足
...（共 25 条）
```
最终：成功 5、被拒 25、库存 = 0（**不为负**）。
**PASS** —— 用例在 `ConcurrencyIT` 里是常驻的，不是一次性脚本。

### DoD-7 单元测试通过

```
[INFO] Results:
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:45 min
```
**PASS** —— 27 = 领域单测 19（条码 6 + 定价 7 + 矩阵 6）+ 集成测试 8（并发 4 + 权限 4）。

### DoD-8 讲义 PDF 存在且可打开

| 文件 | 实测 |
|---|---|
| `C:\Users\26808\Desktop\Java后端深度讲义.pdf` | **44 页 / 1.64 MB**，`%PDF-1.4` 头 + `%%EOF` 尾完整，`/Type /Page` 计数 44 |
| `C:\Users\26808\Desktop\Python后端深度讲义.pdf` | **42 页 / 1.65 MB**（另一独立实现，同一套讲义骨架） |

生成命令与验证细节见 `docs/Stage6_Lecture_Report.md`。
**PASS** —— 无降级、无 `Blocking_Report.md`（本机 Edge/Chromium 打印管线可用）。

---

## 二、Stage 门禁自检

| Stage | 出口物 | 状态 |
|---|---|---|
| 0 | `docs/Decision_Log.md`（ADR-01~14，含 Q76 并发 / Q23 离线 / Q68 权限三条具体对策） | ✅ |
| 0 | `docs/Requirement_Trace_Matrix.md`（76 题逐条 → 代码 → 证据） | ✅ |
| 1 | 目录树 + `docker-compose.yml` + `db/migration/V1__schema.sql`、`V2__seed.sql` + API 契约 | ✅ |
| 2/3 | 全量后端代码 + 独立前端 + `docs/Stage_Report_Java.md` | ✅ |
| 4 | 单元测试 19 + 集成测试 8 | ✅ 27/27 |
| 5 | 本清单 | ✅ |
| 6 | `Java后端深度讲义`（PDF 到桌面） | ✅ 见 Stage 6 报告 |

---

## 三、需求覆盖统计

| 口径 | 数量 |
|---|---|
| 需求条目 | 76 |
| 完全实现 | 71 |
| 部分实现（Q64 打印视图 / Q65 公众号 OAuth / Q4 城市不入库） | 3 |
| 明确不做（与 Q73 一致：线上卖货/会员积分/财务对账） | 2 组 |

详见 `docs/Requirement_Trace_Matrix.md`。

---

## 四、残留风险（诚实版）

| 风险 | 触发条件 | 影响 | 缓解 |
|---|---|---|---|
| Redis 挂掉 | 容器 OOM / 网络 | 闸门 fail-open，退化为纯 DB 行锁；**不会算错**，只是并发高时变慢 | `StockGate` 全部方法 try-catch 放行 |
| RabbitMQ 挂掉 | 同上 | 报表聚合停更；业务流水照写（发件箱留底），恢复后自动补发 | `msg_outbox` 状态机 + 指数退避 + 5 次转死信 |
| 强制出库造成负库存 | 店员点了"强制" | 库存为负，报表毛利率异常 | 出库即写 `STOCK_NEGATIVE` 告警事件进 MQ；`/reports/overview` 计入待处理 |
| 单机 MySQL | 硬件故障 | 数据丢失 | `mysql-data` 命名卷；`db/migration` 可重建结构；演示数据可重建 |
| 打印视图缺失 | 店主点"打印" | 只能打印整个页面 | 见未完成项，属 UI 层补齐 |
