package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.ReconcileBatch;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.StockMatrix;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.FlowRepository;
import com.yilian.clothing.domain.repository.SupportRepository;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 离线同步用例（Q23）。
 *
 * <h3>核心原则：逐条裁决，绝不整批回滚</h3>
 * <p>离线一小时可能攒了 40 条记录，其中一条因为库存不足无法入账。
 * 如果整批回滚，另外 39 条也被退回去，店员面对一个"同步失败"的红叉，
 * 完全不知道该改哪一条——这是最糟的失败方式。</p>
 *
 * <p>因此：每条独立裁决（ACCEPTED/DUPLICATED/CONFLICTED/REJECTED），
 * 把冲突的那条留给人工处理，其余先入账。整批结果写进
 * {@code scan_reconcile_batch/item}，事后可查"那晚到底扫了什么"。</p>
 *
 * <h3>排序与幂等</h3>
 * <ul>
 *   <li>按 {@code deviceSeq} 升序：手机时钟不可信（可能被手动改过、可能跨时区），
 *       设备内自增序号才真实反映"先扫后扫"；</li>
 *   <li>幂等靠 {@code inv_stock_flow.uk_client_token} 唯一索引，
 *       而不是"先查有没有再插"（并发下会双插）。</li>
 * </ul>
 *
 * <h3>为什么不生成销售单</h3>
 * <p>离线时无法保证单据完整（可能只扫了一半就断网）。一条一条的流水比"半张单据"更接近事实，
 * 因此离线补录统一挂 {@code biz_no = OFF-批次号}，报表按流水统计天然正确。</p>
 */
@Service
public class SyncAppService {

    private final SupportRepository supportRepository;
    private final CatalogRepository catalogRepository;
    private final FlowRepository flowRepository;
    private final StockDomainService stockDomainService;
    private final StockQueryAppService stockQueryAppService;
    private final StockCache stockCache;
    private final BizNoGenerator bizNoGenerator;

    public SyncAppService(SupportRepository supportRepository, CatalogRepository catalogRepository,
                          FlowRepository flowRepository, StockDomainService stockDomainService,
                          StockQueryAppService stockQueryAppService, StockCache stockCache,
                          BizNoGenerator bizNoGenerator) {
        this.supportRepository = supportRepository;
        this.catalogRepository = catalogRepository;
        this.flowRepository = flowRepository;
        this.stockDomainService = stockDomainService;
        this.stockQueryAppService = stockQueryAppService;
        this.stockCache = stockCache;
        this.bizNoGenerator = bizNoGenerator;
    }

    @Transactional
    public ReconcileBatch batch(Cmd cmd) {
        Operator op = operator();
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "同步内容为空");
        }
        if (cmd.items().size() > 50) {
            throw BizException.of(ResultCode.PARAM_ERROR, "单批最多 50 条，请分批上传");
        }
        // 整批重传（网络超时后客户端不确定是否成功）：直接返回上次结果，不重复入账
        var existing = supportRepository.findReconcileBatch(cmd.batchNo());
        if (existing.isPresent()) {
            return existing.get();
        }

        long batchId = supportRepository.insertReconcileBatch(cmd.batchNo(), cmd.deviceId(), cmd.items().size());
        String bizNo = "OFF-" + cmd.batchNo();
        List<ReconcileBatch.Item> results = new ArrayList<>();
        int accepted = 0, duplicated = 0, conflicted = 0, rejected = 0;

        List<Item> sorted = new ArrayList<>(cmd.items());
        sorted.sort(Comparator.comparingLong(Item::deviceSeq));

        for (Item item : sorted) {
            ReconcileBatch.Item result = process(item, op, bizNo);
            switch (result.result()) {
                case ACCEPTED -> accepted++;
                case DUPLICATED -> duplicated++;
                case CONFLICTED -> conflicted++;
                case REJECTED -> rejected++;
            }
            results.add(result);
            supportRepository.insertReconcileItem(batchId, result);
        }
        supportRepository.updateReconcileCounters(batchId, accepted, duplicated, conflicted, rejected);
        if (accepted > 0) {
            stockCache.bumpGeneration();
        }
        return supportRepository.findReconcileBatch(cmd.batchNo()).orElseThrow();
    }

    private ReconcileBatch.Item process(Item item, Operator op, String bizNo) {
        // 1) 令牌级幂等：这条离线记录之前已经成功入账过
        var existingFlow = flowRepository.findByClientToken(item.clientToken());
        if (existingFlow.isPresent()) {
            return new ReconcileBatch.Item(null, item.clientToken(), item.deviceSeq(), item.barcode(),
                    item.scanType(), item.quantity(), ReconcileBatch.Result.DUPLICATED,
                    existingFlow.get().flowNo(), "该记录已同步");
        }
        // 2) 条码必须认识，否则无法入账（Q38：让店员手输补录）
        SkuView sku = catalogRepository.findSkuByBarcode(item.barcode(), true).orElse(null);
        if (sku == null) {
            return new ReconcileBatch.Item(null, item.clientToken(), item.deviceSeq(), item.barcode(),
                    item.scanType(), item.quantity(), ReconcileBatch.Result.REJECTED, null, "BARCODE_UNKNOWN");
        }
        try {
            boolean inbound = item.scanType() == 1;
            var flow = stockDomainService.move(StockDomainService.MoveCmd
                    .in(sku, item.warehouseId(), item.quantity(), op, bizNoGenerator.flowNo())
                    .withType(inbound ? FlowType.IN : FlowType.SALE, inbound ? 1 : -1)
                    .withBizNo(bizNo)
                    .withClientToken(item.clientToken())
                    .withPrices(null, sku.purchasePrice())
                    .withRemark("离线补录"));
            return new ReconcileBatch.Item(null, item.clientToken(), item.deviceSeq(), item.barcode(),
                    item.scanType(), item.quantity(), ReconcileBatch.Result.ACCEPTED, flow.flowNo(), null);
        } catch (BizException e) {
            // 库存不足：不进库存、不抛给整批，交给人工处理（Q47「由操作人决定」）
            String reason = e.resultCode() == ResultCode.STOCK_SHORTAGE
                    ? "INSUFFICIENT_STOCK:" + e.payload().getOrDefault("remainQty", 0)
                    : e.resultCode().name();
            return new ReconcileBatch.Item(null, item.clientToken(), item.deviceSeq(), item.barcode(),
                    item.scanType(), item.quantity(), ReconcileBatch.Result.CONFLICTED, null, reason);
        }
    }

    /**
     * 断网前预热：把矩阵（含 SKU/颜色/尺码/数量）整包给客户端，
     * 客户端存进 IndexedDB，断网时仍能"扫出这是什么、还剩几件"。
     */
    public Bootstrap bootstrap(long warehouseId) {
        StockMatrix matrix = stockQueryAppService.matrix(warehouseId, null, null, null, null, null);
        return new Bootstrap(supportRepository.maxCatalogVersion(), matrix);
    }

    public ReconcileBatch batchDetail(String batchNo) {
        return supportRepository.findReconcileBatch(batchNo)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "批次不存在"));
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }

    public record Cmd(String batchNo, String deviceId, long warehouseId, List<Item> items) {
    }

    public record Item(String clientToken, long deviceSeq, String barcode, int scanType, int quantity,
                       long warehouseId) {
    }

    public record Bootstrap(String serverVersion, StockMatrix matrix) {
    }
}
