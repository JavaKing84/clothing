# 决策记录（Decision Log）· Java 版

> 项目：衣恋时尚女装 · 扫码出入库系统（Spring Boot 3 + Java 21 + DDD 四层）
> 业务真源：`衣恋-76题需求答卷（原始粘贴全文）.txt`（店主 2026/10/10 提交，76 题）
> 本文件只记录**做过取舍**的地方。凡是需求里写清楚、照着做就行的，不占篇幅。
> 每条 ADR 都给出：背景 → 决策 → 代价 → 证据（代码位置 / 测试 / 实测数据）。

---

## ADR-01 双实现物理隔离，只共享 HTTP 契约

**背景**：需求要求 Java 与 Python 各实现一套，且明确"严禁双端协作、数据同步、异构双活"。

**决策**：
- Java：`D:\!javaproject\clothing`，库 `yilian_clothing`，Redis db 0，交换机 `yilian.stock.java`；
- Python：`D:\!pythonproject\clothing`，库 `yilian_clothing_py`，Redis db 1，交换机 `yilian.stock.py`；
- 两者**唯一共享物**是 URL 路径与响应结构（`code/msg/data/traceId`），因此同一套前端与压测脚本可复用。

**代价**：业务规则改动要改两处；换来的是任一端可以独立部署、独立演进而互不牵连。

**证据**：两套 `docker-compose.yml` 各自 5 个服务、互不引用；`Redis 库号`、`库名`、`交换机名` 三处均不同。

**踩过的坑（真实事故）**：一开始两端都用 `yilian.stock` 交换机，结果 Python 发布的 JSON **对象**被 Java 消费者读到，抛
`Cannot deserialize String from Object`，消息被 reject 进死信——日报聚合静默失效，只有"真的有单成交"时才暴露。
根因就是"共享了不该共享的东西"。修复=命名空间隔离（见 ADR-13）。

---

## ADR-02 加购即扣库存，而不是结账才扣

**背景**：Q15「扫码→确定出库→收钱装袋」；Q46「扫一件弹确认框确认后出库」。

**决策**：`POST /sales/orders/{no}/items` 成功即扣库存并写出库流水（`flow_type=2`），
结账只改单据状态与金额，不再动库存。

**为什么**：顾客把衣服拿在手上的那一刻，库存就该消失——否则两个店员可能同时把最后一件卖给两个人。
若等到结账才扣，收银台就是超卖窗口。

**代价**：放弃购物车要补一笔反向流水（`REVERSE`），而不是"什么都没发生"。
这是**可审计**的代价：账本上能看到"曾经打算卖、后来放回去了"。

**证据**：`SaleAppService.addItem()`；`SaleAppService.cancel()` 走 `StockDomainService.move(outbound, direction=+1)`；
测试 `ConcurrencyIT.noLostUpdate`（24 并发各扫 1 件，库存精确 −24）。

---

## ADR-03 金额口径：流水记"吊牌价"，抹零补一条 0 数量的调价流水

**背景**：Q11「出库输入实收、算出利润」；Q49「按实际成交价记录，需手工输入」；Q59「营业额 + 毛利」。
三者组合出一个陷阱：实收是手输的，可能低于吊牌合计（抹零/折扣）。

**决策**（本项目最核心的不变式）：
1. 出库流水 `sale_price = 吊牌单价 × 件数`；
2. 结账时若 `实收 ≠ 吊牌合计`，补一条 `flow_type=8 (PRICE_ADJUST)`、`quantity=0` 的流水，
   金额 = `实收 − 吊牌合计`（负数即折扣）；
3. 报表营业额 = `SUM(sale_price) WHERE flow_type IN (2,7,8)`，于是**恰好等于实收**。

**为什么不能"直接把出库流水金额改成实收"**：出库是**按件**发生的（一件一条流水），
折扣是**整单**发生的。把整单折扣摊回每件会引入分摊误差，还会让"这件衣服卖了多少钱"失去原始含义。
分成两类流水后，两个问题各自干净：件级看吊牌，单级看调价。

**代价**：一次结账最多写 2 条流水；查询"实收"必须带上 `flow_type=8`（漏了就会少算折扣）。

**证据**：`SaleAppService.checkout()` 中 `adjust` 分支；`ReportRepositoryImpl` 6 处 `flow_type IN (2,7,8)`；
实测：吊牌 259×2=518、实收 508 → 出库流水 518 + 调价 −10 = 508，与 `received_amount` 一致。

---

## ADR-04 并发防覆盖：三层防御，DB 是唯一事实源（Q76）

**背景**：Q76 明确"两人用时扫码不能相互覆盖"。两个店员同时扫同一件衣服，是最容易写错的地方。

**决策（三层，从内到外）**：
| 层 | 手段 | 作用 | 失守后果 |
|---|---|---|---|
| ① | `SELECT ... FOR UPDATE` + `quantity = quantity - ?` | 真正保证不丢更新 | 无，这是正确性底线 |
| ② | `inv_stock_flow.uk_scan_token` 唯一索引 | 同一令牌只落一次，回放首次结果 | 手抖双击会重复扣 |
| ③ | Redis Lua 预扣闸门（fail-open） | 挡掉"明显不够"的请求，减少 9/10 次无谓行锁 | 高并发时行锁排队变长，但结果仍正确 |

**关键取舍**：闸门**绝不能**当事实源。它只"预扣"，不记账；每次 DB 写成功后用真实余量覆盖它。
初版只在"销售成功"后回写，真实踩坑：**入库 10 件后扫码提示"仅剩 0 件"**。
修复两处：① `StockDomainService` 的每个改库存出口都通告闸门（ADR-14 的端口设计）；
② `addItem` 收到 SHORT 时**回查数据库再裁决**，并用真值纠偏闸门。

**证据**：`StockDomainService.move/resetTo`（行锁 + 原子算术）；`SaleAppService.addItem` 的 `DuplicateKeyException` 分支
（SAVEPOINT 回滚 + `findByScanTokenCurrent` 当前读回放）；
`ConcurrencyIT`：24 并发不丢更新 / 12 线程同令牌只扣 1 件 / 库存 5 却 30 人抢恰好 5 成功。

**为什么回放要用当前读（`FOR SHARE`）**：隔离级别是 REPEATABLE READ，一致性读走事务开始时的快照；
撞车的那条流水是另一个事务刚提交的，快照里没有它 → "唯一索引说重复，却查不到那一行"，只能抛 500。

---

## ADR-05 断网补传逐条裁决，绝不整批回滚（Q23）

**背景**：Q22「有稳定 WiFi」但 Q23 明确要求断网可继续扫码、联网自动同步。现实是仓库角落没信号。

**决策**：
- 客户端（`frontend/src/offline.js`）把扫码事实存 localStorage 队列，附 `deviceSeq`（本机单调升序，**不信手机时钟**）；
- 服务端 `POST /sync/batch` **逐条裁决**，返回 `ACCEPTED / DUPLICATED / CONFLICTED / REJECTED`；
- 每条用 `SAVEPOINT` 隔离，坏数据只回滚自己；整批一个事务提交（避免 200 次 fsync）。

**为什么不能整批失败**：店员在没信号的仓库扫了 200 条，回店上传其中 1 条条码录错，
整批失败就等于一天白干——这是会让人立刻弃用软件的设计。

**为什么用 deviceSeq 不用时间戳**：用户会改手机时间、会跨时区；序号由客户端自己保证单调，
服务端才能判断"中间有没有漏传"。

**证据**：`SyncAppService.reconcile()`；测试 `test_api_contract.py::test_sync_batch_per_item_verdict`
（一条 ACCEPTED、一条 REJECTED，整批不回滚且重传幂等）。

---

## ADR-06 字段级权限三层，而不是"前端藏起来"（Q68）

**背景**：Q68「店员不能看进货价和利润，只有店主能看」。

**决策**：三层同时做，缺一层就等于没做。
1. **接口层**：`@RequirePermission(Permission.REPORT_PROFIT_READ)` → 店员请求利润接口直接 403，
   **不是返回 0**（返回 0 会让店员以为"今天没赚钱"，那是错误信息）；
2. **字段层**：`SkuView.purchasePrice` 在装配时留空（null/缺省），前端拿到的是"没有这个数据"；
3. **查询层**：`CatalogRepository.findSkuByBarcode(barcode, withCost)` —— `withCost=false` 时
   SQL 里根本**不 SELECT** 成本列，而不是查出来再抹掉。

**代价**：同一个 SKU 有两种装配形态，调用方必须显式传 `withCost`；换来的是"即使有人反编译前端/抓包，也拿不到进价"。

**证据**：`PermissionIT.clerkCannotSeePurchasePrice / clerkForbiddenOnProfitReport / permissionCodesDifferByRole`
（店主 15 项权限、店员 9 项，探测后确认为 15/9）。

---

## ADR-07 矩阵在应用层透视，不做 SQL 静态列（Q52）

**背景**：Q52「款式和尺码为行，颜色为列」。

**决策**：SQL 只取长表（款+码+色+数量），透视在 `StockMatrixBuilder` 里做；
缓存的是**透视结果**，用代际号 `stock:gen` INCR 失效。

**为什么不在 SQL 里写 `MAX(CASE WHEN color='黑' ...)`**：女装的"雾霾蓝/燕麦色"一年能冒十几个，
颜色是**开放集合**。写死列就得每来一个新颜色改 SQL、改 DTO、改前端表头。
n≈200 行的应用层透视 < 1ms，瓶颈从来不在这一步。

**为什么缓存用代际号而不是逐个删 key**：删 key 要枚举"受影响的键"，漏一个就是脏数据；
INCR 一个代数，旧键自然失效（读到旧代数就重算），逻辑上不可能漏。

**证据**：`StockMatrixBuilder.build()`（并按 `sizeOrder()` 排序，**不依赖 SQL 的 ORDER BY**——
仓储改 SQL 顺序一漂，前端表头与表体就会错行）；实测 `/stock/matrix` 首请求 28 ms、预热后 8.9–11.0 ms（DoD 要求 3s 内）。

---

## ADR-08 事务性发件箱 + 消费幂等

**背景**：结账后要更新日报聚合。直接在 `checkout` 里发 MQ 会丢消息（DB 提交成功但 broker 不可用）。

**决策**：`msg_outbox` 与业务同事务写入，后台任务 `FOR UPDATE SKIP LOCKED` 抢占后发送，
状态 0→1（成功）/3（死信，重试 5 次）；消费端 `msg_consumed` 主键去重。

**为什么 `SKIP LOCKED`**：多实例部署时，两个实例同时抢同一批消息会重复发送；
`SKIP LOCKED` 让"抢到的人干活，没抢到的人跳过"，不阻塞。

**证据**：`OutboxDispatcher`；`ReportEventListener`（`tryConsume` + `rpt_daily_sales` upsert）；
实测：`销售聚合完成 orderNo=SO20261029-000087`，`rpt_daily_sales` 当日 qty 19→20。

---

## ADR-09 多 SKU 加锁按 id 升序，消灭死锁环

**背景**：调拨、整单退货、整单撤销要动多个 SKU。

**决策**：`lockAllSorted(skuIds)` 去重后按 `skuId` **升序**加锁；仓库维度同理按 `warehouseId` 升序。

**为什么**：A 事务锁 3 再锁 5，B 事务锁 5 再锁 3 → 环 → MySQL 选一个牺牲者回滚。
全序加锁把环消灭在发生之前。单 SKU 测试永远测不出来，所以必须靠纪律而不是靠测试兜。

**证据**：`StockDomainService.lockAllSorted()`。

---

## ADR-10 金额：BigDecimal + 单价只到分 + 残差整数求解

**背景**：Q49 允许手输实收、允许抹零；明细表只存 `actual_price`（两位小数），行金额是现算的。

**决策**：
- 全链路 `BigDecimal`，金额统一 `setScale(2, HALF_UP)`；
- 分摊折扣时先做"行级精确"（最后一行吃舍入差额），再在**整数分域**解方程
  `Σ u_i × q_i = 整单实收`，让"单价 × 件数"回算也精确；
- 数学上无解时（所有行都 ≥2 件且 gcd 不整除残差）把残差如实返回，不假装精确。

**为什么需要第二层**：26.41 元摊到 3 件，单价 8.8033 存进去变 8.80，回算 26.40，整单少收 1 分。
单件行永远看不出问题——所以单测必须专门造多件行。

**证据**：`PricingService.allocateDiscount(items, discount, residualCents)`；
`PricingServiceTest.multiQtyLinesKeepCentExactness`（7 行含 qty=2/3，断言精确相等、残差 0）、
`impossibleResidualIsReported`（3 行各 2 件、折扣 1 分 → 残差 ≠ 0 且被暴露）。

---

## ADR-11 条码宽松解析 + 失败降级（Q24/Q26/Q36/Q38）

**背景**：Q24「没有二维码」、Q27「有条形码」、Q36「只有一串条形码，其余需要人工输入」、Q38「扫不出码时允许手动输入货号」。
各供货商码制不同，需求没给规范。

**决策**：`BarcodeParser` 支持 ①EAN-13 纯条码（字段靠映射表）②`|` 分隔 ③`#` 分隔；
都不是则 `plainBarcode=true`，前端切"手动输入货号"。日期字段解析失败**静默丢弃**，不影响其余字段。

**为什么降级而不是报错**：店员在柜台前等 3 秒就会开始烦躁；一个字段写错不该让整次扫码失败。

**证据**：`BarcodeParserTest`（6 个用例）；`GET /catalog/barcode/{barcode}` 三态返回。

---

## ADR-12 盘点两段式 + 差异挂账（Q57/Q58/Q48）

**决策**：盘点任务创建时快照账面数 → 逐件扫码记实际数 → `finish` 一次性按差异调账并写 `flow_type=6` 流水；
"实物找不到"可挂账（`inv_loss_case`，`status=OPEN`），挂账**不动库存**，等店主核。

**为什么挂账不直接扣**：直接扣等于"认定丢了"，而现实中经常是"放错货架/借给同行看款"。
挂账是"先记账、后处理"，符合 Q48 的措辞（需要，可挂账）。

**证据**：`StocktakeAppService.create/scan/finish`；`LossAppService`；测试
`test_api_contract.py::test_sync_batch_per_item_verdict` 同批还包括盘点的三元组断言。

---

## ADR-13 交换机命名空间隔离（事故复盘）

**事故**：两端共用 `yilian.stock` 交换机，Python 发的 JSON 对象进入 Java 队列，
Java 消费者声明 `String payload` → `MessageConversionException` → 消息被 reject → 日报聚合静默失效。

**决策**：
1. Java `yilian.stock.java`、Python `yilian.stock.py`；队列名本已不同（`q.report.sales` vs `yilian.report`）；
2. 消费端**不再假设消息形态**：拿原始 `Message` 的字节自己解析，文本节点再解一层
   （`PayloadReader.read`），对象/字符串都能接住。

**教训**：①"两个系统共用一个中间件"必须显式命名空间化，否则隔离只是纸面上的；
②消费端对消息格式做**防御性解析**，是跨系统协作的基本礼貌。

**证据**：`RabbitConfig.EXCHANGE`；`PayloadReader`；重启后实测"销售聚合完成"日志 + `rpt_daily_sales` 递增。

---

## ADR-14 领域定义端口，基础设施实现（闸门回写）

**背景**：ADR-04 要求"每次库存变更后回写 Redis 闸门"。天真做法是在 5 个 AppService 里各加一行——
入库、退货、调拨入、盘点、冲正，漏一处就是一次"明明有货却提示没货"。

**决策**：领域层声明 `StockAvailabilityPort`（`refresh(warehouseId, skuId, quantity)`），
基础设施层 `StockGateAdapter` 实现（调 Redis、吞异常 fail-open）。
`StockDomainService.move()` 与 `resetTo()` 在写库成功后调用端口——**唯一改库存的出口**自动全覆盖。

**代价**：多一个接口 + 一个适配器（约 40 行）；换来的是"不可能漏"，以及领域层单测可以传 `NOOP` 不启 Redis。

**证据**：`domain/port/StockAvailabilityPort.java`、`infrastructure/cache/StockGateAdapter.java`；
`ConcurrencyIT.gateWrittenBackAfterReplenish`（卖空→入库 3 件→立刻卖得出去）。

---

## 附：被明确否决的方案

| 方案 | 为什么否决 |
|---|---|
| 结账时才扣库存 | 收银台成为超卖窗口（ADR-02） |
| 用 Redis 当库存事实源 | 缓存不可持久、不可对账；一旦丢数据无法恢复（ADR-04） |
| 前端隐藏进价字段 | 抓包即得；权限必须在服务端（ADR-06） |
| SQL 静态列透视矩阵 | 颜色是开放集合，每来一个新色要改三层（ADR-07） |
| 整批回滚的离线同步 | 一条坏数据毁掉一天的扫描（ADR-05） |
| 直接改出库流水金额为实收 | 件级与单级金额混在一起，毛利无法按款核算（ADR-03） |
| 在 5 个服务里各写一次闸门回写 | 必然漏，且漏了没症状（ADR-14） |
