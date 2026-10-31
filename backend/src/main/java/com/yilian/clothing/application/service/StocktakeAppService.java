package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.domain.model.StocktakeTask;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.FlowRepository;
import com.yilian.clothing.domain.repository.StocktakeRepository;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 盘点用例（Q57/Q58）。
 *
 * <h3>为什么不直接改库存</h3>
 * <p>盘点差异是最需要"解释"的数据：这个月亏了 12 件，是丢货？是卖漏扫？是供货商少发？
 * 如果直接把 {@code quantity} 改成实际数，差异就永久消失了，月底对不上账时无从追查。
 * 因此差异必须写成 {@code STOCKTAKE} 流水，保留 before/after。</p>
 *
 * <h3>盘点期间还在卖货怎么办</h3>
 * <p>建单时快照 {@code book_qty} 与 {@code book_version}。完结时若发现库存版本已变
 * （说明期间有销售/入库），差异清单仍然按快照算，但店主能看到"这期间有变动"的提示——
 * 这比"暂停营业两小时"现实得多。</p>
 */
@Service
public class StocktakeAppService {

    private final StocktakeRepository stocktakeRepository;
    private final CatalogRepository catalogRepository;
    private final FlowRepository flowRepository;
    private final StockDomainService stockDomainService;
    private final BizNoGenerator bizNoGenerator;
    private final StockCache stockCache;

    public StocktakeAppService(StocktakeRepository stocktakeRepository, CatalogRepository catalogRepository,
                               FlowRepository flowRepository, StockDomainService stockDomainService,
                               BizNoGenerator bizNoGenerator, StockCache stockCache) {
        this.stocktakeRepository = stocktakeRepository;
        this.catalogRepository = catalogRepository;
        this.flowRepository = flowRepository;
        this.stockDomainService = stockDomainService;
        this.bizNoGenerator = bizNoGenerator;
        this.stockCache = stockCache;
    }

    @Transactional
    public StocktakeTask create(long warehouseId) {
        Operator op = operator();
        String taskNo = bizNoGenerator.stocktakeNo();
        long taskId = stocktakeRepository.insertTask(taskNo, warehouseId, op.id(), op.nickname());
        int size = stocktakeRepository.snapshotItems(taskId, warehouseId);
        stocktakeRepository.updateTotalItems(taskId, size);
        return stocktakeRepository.findByNo(taskNo, false).orElseThrow();
    }

    @Transactional
    public ScanResult scan(String taskNo, String barcode, int qty) {
        StocktakeTask task = requireDoing(taskNo);
        SkuView sku = catalogRepository.findSkuByBarcode(barcode, true)
                .orElseThrow(() -> BizException.of(ResultCode.BARCODE_UNKNOWN).with("barcode", barcode));
        int rows = stocktakeRepository.scan(taskNo, sku.id(), qty);
        if (rows == 0) {
            // 盘点范围是建单时的快照；盘点期间新入库的 SKU 不在范围内。
            // 这不是 bug，而是"盘点口径必须固定"的必然结果
            throw BizException.of(ResultCode.SKU_NOT_FOUND, "该商品不在本次盘点范围内");
        }
        stocktakeRepository.bumpScanned(task.id(), 0);
        StocktakeTask fresh = stocktakeRepository.findByNo(taskNo, true).orElseThrow();
        int actual = fresh.items().stream().filter(i -> i.skuId().equals(sku.id()))
                .mapToInt(StocktakeTask.Item::actualQty).findFirst().orElse(0);
        int book = fresh.items().stream().filter(i -> i.skuId().equals(sku.id()))
                .mapToInt(StocktakeTask.Item::bookQty).findFirst().orElse(0);
        return new ScanResult(taskNo, sku.spuNo(), sku.spuName(), sku.size(), sku.color(),
                book, actual, actual - book, fresh.scannedItems(), fresh.totalItems());
    }

    /**
     * 完成盘点：逐条按差异调账并写流水。
     *
     * <p>这里**没有**用 {@code updateActual} 再算差，而是直接拿 {@code findDiffItems} 的结果。
     * 因为实际数量是扫码累加出来的，任何二次计算都可能与展示给店员看到的数字不一致。</p>
     */
    @Transactional
    public FinishResult finish(String taskNo) {
        StocktakeTask task = requireDoing(taskNo);
        Operator op = operator();
        List<StocktakeTask.Item> diffs = stocktakeRepository.findDiffItems(task.id());
        for (StocktakeTask.Item item : diffs) {
            SkuView sku = catalogRepository.findSkuById(item.skuId(), true)
                    .orElseThrow(() -> BizException.of(ResultCode.SKU_NOT_FOUND));
            int delta = item.diff();
            int[] beforeAfter = stockDomainService.resetTo(item.skuId(), task.warehouseId(),
                    item.actualQty());
            StockFlow flow = StockFlow.builder()
                    .flowNo(bizNoGenerator.flowNo())
                    .flowType(FlowType.STOCKTAKE)
                    .sku(sku)
                    .warehouseId(task.warehouseId())
                    .qty(Math.abs(delta), FlowType.STOCKTAKE)
                    .signedQty(delta)
                    .beforeAfter(beforeAfter[0], beforeAfter[1])
                    .operator(op)
                    .bizNo(taskNo)
                    .remark(delta > 0 ? "盘盈" : "盘亏")
                    .build();
            flowRepository.append(flow);
        }
        stocktakeRepository.finish(task.id(), diffs.size());
        stockCache.bumpGeneration();
        return new FinishResult(taskNo, diffs.size(),
                diffs.stream().mapToInt(StocktakeTask.Item::diff).sum());
    }

    public StocktakeTask detail(String taskNo) {
        return stocktakeRepository.findByNo(taskNo, true)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "盘点单不存在"));
    }

    public List<StocktakeTask.Item> diff(String taskNo) {
        StocktakeTask task = stocktakeRepository.findByNo(taskNo, false)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "盘点单不存在"));
        return stocktakeRepository.findDiffItems(task.id());
    }

    private StocktakeTask requireDoing(String taskNo) {
        StocktakeTask task = stocktakeRepository.findByNo(taskNo, false)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "盘点单不存在"));
        if (!task.doing()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "该盘点单已完成");
        }
        return task;
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }

    public record ScanResult(String taskNo, String spuNo, String spuName, String size, String color,
                             int bookQty, int actualQty, int diff, int scannedItems, int totalItems) {
    }

    public record FinishResult(String taskNo, int diffItems, int totalDiffQty) {
    }
}
