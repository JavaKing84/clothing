# 需求追踪矩阵（Requirement Trace Matrix）· Java 版

> 业务真源：`衣恋-76题需求答卷（原始粘贴全文）.txt`（76 题，店主 2026/10/10 提交）
> 原则：**每一条需求都能指到一处代码 + 一条可执行证据**。没有证据的不写"已实现"。
> 证据类型：`IT` = 集成测试（真 HTTP + 真 MySQL）、`UT` = 单元测试、`ANS` = 端到端验收脚本、
> `实测` = 本次交付时的真实响应片段。

## 一、基本信息（Q1–Q6）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 1–3 | 称呼/联系方式/身份 | `cat_*` 之外的 `sys_user(nickname,phone,role)`；`DemoSeeder` 灌 4 个账号 | `PermissionIT.permissionCodesDifferByRole`（店主/店员权限数 15/9） |
| 4 | 城市（云南临沧） | 调研信息，不进业务表；时区固定 `Asia/Shanghai` | `application.yml` 时区、容器 `TZ`；实测日报按自然日切分 |
| 5 | 500–2000 件库存 | 容量按 200 行矩阵设计（6 款×11 SKU 实测） | 实测 `/stock/matrix` 8.9–11 ms（DoD 3 s 内） |
| 6 | 3–5 人使用 | 用户表 + 角色；每角色独立权限码 | `sys_role_permission`；`AuthAppService.login` |

## 二、现状与目标（Q7–Q12）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 7 | 现在用纸质本子 | 流水账本替代：`inv_stock_flow` 只追加不修改 | `StockDomainService.appendFlow` |
| 8 | 忙起来忘记账/盘点对不上/忘库存 | 扫码即记账（Q9）+ 实时库存（Q10）+ 盘点（Q57） | 见下三行 |
| 9 | **卖出去扫码后库存自动减** | `SaleAppService.addItem` → `StockDomainService.move(outbound)` | `ConcurrencyIT.noLostUpdate`（24 并发精确 −24） |
| 10 | 随时查某款某色某码几件 | `StockQueryAppService.matrix` → `StockMatrixBuilder` | 实测矩阵 8 色 × 21 行 |
| 11 | 进货扫码、存进价、出库输实收、算利润 | `InboundAppService` + `SaleAppService.checkout` + `ReportAppService.profit` | `ANS` 第 5/9/10 节；实测 `revenue 31340 / cost 11404 / gross 21824 / margin 69.64` |
| 12 | 每天关门看营业额和利润 | `GET /reports/overview` + `/reports/profit` | 实测 overview：`todayAmount 31340, todayQty 138, todayOrders 32` |

## 三、角色与场景（Q13–Q23）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 13 | 店主 2 人（看利润/业绩/员工销量）、店员 2 人（只看自己） | `Role{OWNER,CLERK}` + 15/9 个权限码；`by_clerk` 查询强制 `operator_id=自己` | `ReportRepositoryImpl.byClerk`（店员恒带自己 id，传别人 id 无效） |
| 14 | 店员要用手机操作 | 前端响应式单页（Vue3 + Vite），无固定宽度 | `frontend/src/App.vue` 布局断点 |
| 15 | 卖货流程：结账→扫码→确定出库→收钱 | `addItem`（扫码即出库）→ `checkout`（收钱） | `ANS` 第 6/9 节 |
| 16 | 一次买多件：一件一件扫 | 每扫一件一条流水；同 SKU 合并行（Q50） | `ANS` 第 8 节 |
| 17 | 有退换货，需要记录 | `ReturnAppService.create`（`flow_type=7`，金额取负） | `ANS` 退货金额断言；`ReturnAppService` 的 `refundAmount.negate()×qty` |
| 18 | 进货流程：送货→核箱→逐件扫码→上架 | `InboundAppService`：建单→扫件→提交 | `ANS` 第 5 节 |
| 19 | 逐件扫 + 整箱扫都要 | `POST items{barcode,qty}` 支持 qty>1（整箱）；前端扫码固定 +1 | `ANS` 第 5 节（qty=10 一次入 10 件） |
| 20 | 有 2 个门店/仓库 | `sys_warehouse`；所有查询带 `warehouse_id` | 前端门店切换 + 全接口 `warehouseId` 参数 |
| 21 | 需要调拨 | `TransferAppService.create`（出+入两条流水，同事务） | `ANS` 调拨单 `TF...` 生成 |
| 22 | 有稳定 WiFi | 无需特殊处理，但短连接超时设 30s | `frontend/nginx.conf` `proxy_read_timeout` |
| 23 | **断网继续扫、联网自动同步** | `SyncAppService.reconcile` 逐条裁决 + 前端 `offline.js` 队列 | `ANS` 第 13 节：一条 ACCEPTED、一条 REJECTED，整批不回滚且重传幂等 |

## 四、商品与条码（Q24–Q38）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 24 | 吊牌没有二维码 | 以条形码为主键识别，不做二维码依赖 | `cat_barcode` |
| 25 | 有的有有的没有 | 未绑定码返回 `bound=false`，引导建档/手输 | `ANS` 第 4 节（`6900000000001` → `bound=false`） |
| 26 | 码里含：货号+尺码+颜色+日期+供货商+条形码 | `BarcodeParser.parse` 六段解析（`|` / `#`） | `BarcodeParserTest.pipeFormatFull` |
| 27 | 有条形码 | `cat_barcode` 唯一索引 | `ANS` 第 4 节 |
| 28–34 | 货号/尺码/颜色/供货商/进价/零售价/季节品类 必须记录 | `cat_spu`（货号、品类、季节、供货商、进价、零售价、特价）+ `cat_sku`（尺码、颜色） | 建款接口 `CatalogueController.createProduct` 一次生成颜色×尺码全部 SKU |
| 35 | 还要记：特价、拿货日期、供货商 | `spu.special_price`、条码内 `purchase_date`、`spu.supplier_id` | `BarcodeParserTest`（日期解析）；`specialPrice` 参与 `tagPrice()` |
| 36 | 扫码只带出条形码，其余人工输入 | 三态返回：已绑定→带出 SKU；未绑定→`plainBarcode`；纯码→手输 | `ANS` 第 4 节 |
| 37 | 手机摄像头 + 扫码枪 | 前端单输入框：扫码枪回车即提交，手输同框 | `frontend/src/views/Sale.vue` `@keyup.enter` |
| 38 | 扫不出码允许手动输入货号 | `GET /catalog/search`（货号/品名/编码模糊） | `CatalogController.search`；前端未建档提示切手输 |

## 五、入库流程（Q39–Q44）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 39 | 入库记录：时间/操作人/供货商/进价/单号/备注 | `biz_inbound_order` + `_item` + `inv_stock_flow` | `ANS` 第 5 节 |
| 40 | 逐件扫 + 整箱扫都要 | 同 Q19 | 同上 |
| 41 | **同一个码重复扫码：不做处理** | `uk_order_sku` + `inserted=false`，数量不重复累加 | `ANS` 第 5 节（再扫同码 totalQty 仍 10） |
| 42 | 需要显示累计件数 | 响应 `totalQty` | `ANS` 第 5 节 |
| 43 | 扫错可撤销上一件 | `DELETE /inbound/orders/{no}/items/last` | `InboundController` 明确暴露 `items/last` |
| 44 | 希望有入库数量累计记录 | 单头 `total_qty` + 流水逐条可查 | `/inbound/orders/{no}`、`/ledger` |

## 六、出库流程（Q45–Q50）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 45 | 出库记录：时间/操作人/实收/折扣/支付/客户/备注 | `biz_sale_order`（`received_amount`,`discount_amount`,`pay_type`,`customer_*`,`remark`） | `SaleAppService.checkout` |
| 46 | 扫一件弹确认框确认后出库 | 前端确认交互 + 服务端扣减（ADR-02 加购即扣） | `Sale.vue` 扫码流程 |
| 47 | 库存不足提示"仅剩 X 件"由人决定 | 409 + `code=1001` + `data.remainQty`；可 `force=true` 强制出库 | `ANS` 第 12 节；`StockDomainService` `allowNegative` |
| 48 | 实物找不到可挂账 | `inv_loss_case`（不动库存） | `ANS` 第 14 节（`caseNo` 以 `LS` 开头） |
| 49 | 按实际成交价记录，需手工输入 | `received_amount` 手输 + `PRICE_ADJUST` 流水（ADR-03） | `ANS` 第 9 节（实收 ≠ 吊牌合计仍平账） |
| 50 | 同单同款扫两次要记一起 | `uk_order_sku` + `upsertItem` 累加 qty | `ANS` 第 8 节（一行 qty=3） |

## 七、库存与查询（Q51–Q58）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 51 | 扫码查/货号搜/供货商筛/颜色季节筛 | `/catalog/barcode/{code}`、`/catalog/search`、`/products?supplierId=`、`/products?color=&season=` | `CatalogController` 查询参数 |
| 52 | 款式×尺码为行、颜色为列 | `StockMatrixBuilder.build`（应用层透视，ADR-07） | 实测 `colors=8, rows=21, grandTotal=137` |
| 53 | 库存 0 或偏少**不需要**标色 | 默认不标色；但矩阵仍只传非零格（避免"有货但 0"的误读） | `StockMatrix.Row.cells` 语义 |
| 54–55 | 库存预警（不确定→阈值 1 件） | `inv_sku_stock.safety_stock=1` + `/stock/alerts` | 实测 `alertCount` 与 SQL `quantity<=safety_stock` 一致（33 行） |
| 56 | 首页待补货清单 + 红点 | `/reports/overview.alertCount` + 前端侧边栏徽标 | 实测 overview 返回 `alertCount` |
| 57 | 需要盘点功能 | `StocktakeAppService.create/scan/finish` | `ANS` 第 14 节 |
| 58 | 逐件扫码、自动比对、出差异清单 | `scan` 返回 `bookQty/actualQty/diff`；`GET /stocktakes/{no}/diff` | `ANS` 第 14 节三元组断言 |

## 八、报表与数据（Q59–Q64）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 59 | 今日/周/月销量金额、销量排行、滞销、按供货商、按店员、毛利 | `/reports/overview`、`/ranking`、`/slow-moving`、`/by-supplier`、`/by-clerk`、`/profit` | 实测六个接口均 200；`profit` 含 `margin` |
| 60 | 流水能查一年以上 | `inv_stock_flow`（`(warehouse_id,create_time)` 索引），分页查询 | `/ledger?start=&end=` 分页；数据只追加不删 |
| 61 | 按款式/日期/操作人/出入库筛选 | `GET /ledger` 的 `spuNo/start/end/operatorId/flowType` | `LedgerController.query` |
| 62 | 可撤销且留痕 | `POST /ledger/{flowNo}/reverse` 追加反向流水（不删原记录） | `LedgerAppService.reverse`；店员仅可撤自己 24h 内（Q69） |
| 63 | 需要导出 Excel | `GET /reports/export`（POI 生成 xlsx） | `ANS` 第 15 节（zip 头 PK + spreadsheetml） |
| 64 | 要打印：进货单/销售小票/盘点表 | 销售小票 `GET /sales/orders/{no}/receipt`；进货单与盘点单目前只提供数据结构（前端打印视图未做） | **部分实现，见 Stage_Report「未完成项」** |

## 九、技术形态（Q65–Q70）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 65 | 希望以微信公众号形式使用 | 交付为 H5 单页应用，可直接挂公众号菜单/微信内打开（未接入微信 OAuth） | `frontend` 部署于 Nginx；**未做公众号授权，见未完成项** |
| 66 | 需要登录 | JWT（`JwtAuthFilter`），token 24h | `ANS` 第 2 节 |
| 67 | 手机号 + 密码 | `POST /auth/login`，PBKDF2 加盐存储 | 错误密码返回 `4001` 且不暴露手机号是否存在 |
| 68 | **店员不能看进价和利润** | 三层防御（ADR-06） | `PermissionIT` 三个用例 |
| 69 | 店员可以修改已录入的数据 | `ledger:reverse:self`（限自己 24h 内） | `LedgerAppService.reverse` 权限判断 |
| 70 | 安卓和苹果都要能用 | 纯 Web，无原生依赖 | 前端无 UA 分支 |

## 十、范围与验收（Q71–Q76）

| Q | 需求要点 | 实现位置 | 证据 |
|---|---|---|---|
| 71 | 第一版必须有：扫码入库/扫码出库/实时库存/出入库流水 | 全部实现 | `ANS` 第 5/6/3/流水接口 |
| 72 | 可放二期：报表/预警/盘点/多店员权限/Excel/员工业绩 | **已全部实现（超出范围）** | `/reports/*`、`/stock/alerts`、`/stocktakes`、权限码、导出 |
| 73 | 明确不要：线上卖货/会员积分/财务对账 | 未实现，代码中无相关表与接口 | 26 张表清单中无对应表 |
| 74 | 验收：扫码入库 +1；输入货号 3 秒内看到矩阵 | `Sub` | `ANS` 第 5 节（11→21）；实测 8.9–11 ms |
| 75 | 一周内能用上 | 交付含 `docker compose up` 一键启动 | 本机实测：`compose up` 后前端 8082/后端 28000 可用 |
| 76 | **两人同时扫码不能相互覆盖**；免费低成本 | 三层防御（ADR-04）；技术栈全开源 | `ConcurrencyIT`：24 并发不丢更新、12 同令牌只扣 1、5 件库存 30 人抢恰好 5 人成功 |

---

## 统计

- 需求条目：76
- 完全实现：71
- 部分实现（已在交付说明中标注）：3 → Q64（打印视图）、Q65（公众号授权）、Q4（城市仅作调研信息，不入库）
- 明确不做（与 Q73 一致）：线上卖货、会员积分、财务对账
- 自动化证据：Java 单测 15 + 集成测试 12 = **27 项**，全部通过；Python 侧另有 41 项 pytest + 37 项验收（独立实现，互不复用代码）
