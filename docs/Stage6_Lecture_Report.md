# Stage 6 · 深度讲义交付报告（Java 版）

## 1. 交付物

| 文件 | 位置 | 规格 |
|---|---|---|
| `Java后端深度讲义.pdf` | `C:\Users\26808\Desktop\` | **44 页 / 1.64 MB / A4**，`%PDF-1.4` 头 + `%%EOF` 尾完整 |
| HTML 源 | `docs/lecture/java.html` | 单文件，含 2 张内联 SVG 图（四层架构图、并发时序图） |
| 样式 | `docs/lecture/lecture.css` | A4 打印样式，与 Python 版共用一份 |

## 2. 生成方式（可复现）

```bash
cd D:\!javaproject\clothing\docs\lecture
"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" ^
  --headless --disable-gpu --no-pdf-header-footer ^
  --print-to-pdf="C:\Users\26808\Desktop\Java后端深度讲义.pdf" ^
  "file:///D:/!javaproject/clothing/docs/lecture/java.html"
```
真实输出：`1721330 bytes written to file C:/Users/26808/Desktop/Java后端深度讲义.pdf`

**工具链说明**：本机无 wkhtmltopdf / pandoc / weasyprint / LaTeX，但 Edge 是 Chromium 内核，
`--print-to-pdf` 走的是与 Chrome 相同的打印管线（支持 `@page`、`page-break-*`、`print-color-adjust`），
中文用系统「微软雅黑」，字体内嵌，无需额外安装。**没有降级，也不需要 `Blocking_Report.md`。**

## 3. 内容结构（12 章，三层递进）

| 章 | 主题 | 三层递进的落点 |
|---|---|---|
| 1 | 需求怎么变成架构 | 76 题 → 3 条改架构的需求 → 四层是怎么被"逼"出来的 |
| 2 | 四层的职责与依赖方向 | 架构图 / 一个调用链的四层台词 / Spring Bean 与 CGLIB 代理 / 端口与适配器 |
| 3 | 唯一改库存出口 | move() 逐行 / `FOR UPDATE` 在 InnoDB 里锁住什么（唯一索引 = 锁粒度开关）/ lockOrCreate 的二次加锁 |
| 4 | 两人同时扫码不覆盖 | 三层防御 + 时序图 / 12 线程 11 个 500 的完整复盘（SAVEPOINT + `FOR SHARE` 当前读） |
| 5 | 金额精确到分 | 三条口径 / 1 分误差现场 / 分域残差求解（gcd 判别法 + solvePair） |
| 6 | 矩阵与业务排序 | 为什么透视不下推 SQL / L 排到 M 前面的真实事故 / sizeOrder 与颜色降序 |
| 7 | 字段级权限三层 | 403 vs 返回 0 / 为什么最里层要"根本不 SELECT" / `@JsonInclude` 导致的断言陷阱 |
| 8 | @Transactional 四个陷阱 | 锁释放时点 / 同类自调用 / 异常被吞 / 事务里做远程调用 |
| 9 | 事务性发件箱 | 三种写法对比 / SKIP LOCKED / 指数退避转死信 / 反序列化事故与命名空间串台 |
| 10 | 断网还能卖货 | deviceSeq 与 clientToken 的分工 / 逐条裁决四态 / 为什么绝不整批回滚 |
| 11 | 测试在测什么 | 27 项的分层判据 / CountDownLatch 造并发窗口 / 故意改坏实现验证用例有效性 |
| 12 | 面试速查 20 问 | 并发 8 问 + 金额 5 问 + 架构 7 问 + 3 个送命题 |

**每章固定三块**：`【技术本质总结】`（一句话本质 + 判据）、`【架构师视角】`（代价、权衡、退出条件）、
`【面试追问与应答】`（3–4 个追问，含"你这个系统涨到 3 万人哪里先垮"这类送命题）。

## 4. 代码溯源清单（讲义中逐行讲解的符号，全部为真实文件）

| 讲义位置 | 真实符号 | 文件 |
|---|---|---|
| 第 2 章 | `SaleController.addItem` / `SaleAppService.addItem` / `StockDomainService.move` / `StockRepositoryImpl.lockForUpdate` | `interfaces/controller/SaleController.java` 等 |
| 第 2 章 | `StockAvailabilityPort` / `StockGateAdapter` | `domain/port/` · `infrastructure/cache/` |
| 第 3 章 | `StockDomainService.move` / `lockOrCreate` / `lockAllSorted` / `MoveCmd` | `domain/service/StockDomainService.java` |
| 第 3 章 | `UPDATE ... SET quantity = quantity - :delta` | `infrastructure/persistence/StockRepositoryImpl.java` |
| 第 4 章 | `createSavepoint` / `rollbackToSavepoint` / `DuplicateKeyException` 分支 | `application/service/SaleAppService.java:144-180` |
| 第 4 章 | `findByScanTokenCurrent`（`FOR SHARE`） | `domain/repository/FlowRepository.java` + `FlowRepositoryImpl` |
| 第 4 章 | `StockGate`（Lua `ACQUIRE` / `warmUp` / `reset` / `refund`） | `infrastructure/cache/StockGate.java` |
| 第 5 章 | `PricingService.allocateDiscount` / `cents` / `solveResidual` / `solvePair` | `domain/service/PricingService.java` |
| 第 6 章 | `StockMatrixBuilder.build` / `sizeOrder` | `domain/service/StockMatrixBuilder.java` |
| 第 7 章 | `PermissionInterceptor.preHandle` / `RequirePermission` | `infrastructure/security/` |
| 第 9 章 | `OutboxDispatcher.dispatch` / `PayloadReader.read` / `RabbitConfig.EXCHANGE` | `infrastructure/mq/` |
| 第 10 章 | `SyncAppService.batch` / `process` | `application/service/SyncAppService.java` |
| 第 11 章 | `ConcurrencyIT` / `PermissionIT` / `CountDownLatch` 造并发 | `src/test/java/.../integration/` |

## 5. 验证

| 项 | 结果 |
|---|---|
| PDF 结构 | `%PDF-1.4` 开头、`%%EOF` 结尾、`/Type /Page` 计数 44、`/ObjStm` 不存在（未压缩对象流，兼容性好） |
| 中文渲染 | 屏幕渲染核对通过（封面/目录/正文/表格/SVG 均正常，无溢出） |
| 目录对齐 | `第 N 章` 与标题分两列，标题起点 26mm，与正文左边界对齐 |
| 打印分页 | 每章 `page-break-before: always`；`pre` 与 `figure` 设 `page-break-inside: avoid`，代码块不会被切两半 |

## 6. 与 Python 讲义的差异（同结构、不同内容）

同一 12 章骨架，但每章的"底层机制"落到 Python 生态的真实机制上：
事务边界在 **HTTP 中间件**（yield 依赖退出晚于响应）、`begin_nested()` 对应 SAVEPOINT、
`expire_on_commit=False` 与 `MissingGreenlet`、`request.state` vs `contextvars`、
Lua 原子脚本、`Decimal` 的三个坑、ThreadPoolExecutor 造并发的原理。

两份讲义对照阅读，能看清"哪些是本质约束、哪些是语言/框架特性"——这是双语言实现最大的教学价值。
