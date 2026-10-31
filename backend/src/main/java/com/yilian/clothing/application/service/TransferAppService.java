package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.OrderRows;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.OrderAuxRepository;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 调拨用例（Q21）。
 *
 * <h3>为什么调拨必须一个事务、两条流水</h3>
 * <p>"总店减 3 件、二店加 3 件"如果分成两个事务，中间断电就会出现"总店减了、二店没加"，
 * 这 3 件衣服凭空消失，而且没人知道去哪了。放在一个事务里，要么两条都成，要么都不成。</p>
 *
 * <h3>死锁</h3>
 * <p>两笔方向相反的调拨（A→B 与 B→A）会以相反顺序锁同一批库存行，形成环路。
 * 解法是全序加锁：先按仓库 id 升序锁定所有涉及行，再在同一顺序下更新。
 * 这样任何两笔调拨的加锁顺序都一致，环路不可能出现。</p>
 */
@Service
public class TransferAppService {

    private final OrderAuxRepository orderAuxRepository;
    private final CatalogRepository catalogRepository;
    private final StockDomainService stockDomainService;
    private final BizNoGenerator bizNoGenerator;
    private final StockCache stockCache;

    public TransferAppService(OrderAuxRepository orderAuxRepository, CatalogRepository catalogRepository,
                              StockDomainService stockDomainService, BizNoGenerator bizNoGenerator,
                              StockCache stockCache) {
        this.orderAuxRepository = orderAuxRepository;
        this.catalogRepository = catalogRepository;
        this.stockDomainService = stockDomainService;
        this.bizNoGenerator = bizNoGenerator;
        this.stockCache = stockCache;
    }

    @Transactional
    public String create(Cmd cmd) {
        Operator op = operator();
        if (cmd.fromWarehouseId() == cmd.toWarehouseId()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "调出与调入门店不能相同");
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "调拨明细不能为空");
        }
        Set<Long> skuIds = cmd.items().stream()
                .map(i -> catalogRepository.findSkuByBarcode(i.barcode(), true)
                        .orElseThrow(() -> BizException.of(ResultCode.BARCODE_UNKNOWN)
                                .with("barcode", i.barcode())).id())
                .collect(Collectors.toSet());

        // 全序加锁：仓库升序 + SKU 升序，消灭 A→B / B→A 的对锁
        long first = Math.min(cmd.fromWarehouseId(), cmd.toWarehouseId());
        long second = Math.max(cmd.fromWarehouseId(), cmd.toWarehouseId());
        stockDomainService.lockAllSorted(skuIds, first);
        stockDomainService.lockAllSorted(skuIds, second);

        String orderNo = bizNoGenerator.transferNo();
        int totalQty = cmd.items().stream().mapToInt(Item::qty).sum();
        long transferId = orderAuxRepository.insertTransfer(orderNo, cmd.fromWarehouseId(),
                cmd.toWarehouseId(), totalQty, op.id(), op.nickname(), cmd.remark());

        for (Item item : cmd.items()) {
            SkuView sku = catalogRepository.findSkuByBarcode(item.barcode(), true).orElseThrow();
            var out = stockDomainService.move(StockDomainService.MoveCmd
                    .out(sku, cmd.fromWarehouseId(), item.qty(), op, bizNoGenerator.flowNo())
                    .withType(FlowType.TRANSFER_OUT, -1)
                    .withBizNo(orderNo)
                    .withRemark("调出至 " + cmd.toWarehouseId() + " 号店"));
            var in = stockDomainService.move(StockDomainService.MoveCmd
                    .in(sku, cmd.toWarehouseId(), item.qty(), op, bizNoGenerator.flowNo())
                    .withType(FlowType.TRANSFER_IN, 1)
                    .withBizNo(orderNo)
                    .withRemark("由 " + cmd.fromWarehouseId() + " 号店调入"));
            orderAuxRepository.insertTransferItem(transferId, sku.id(), item.qty(),
                    out.flowNo(), in.flowNo());
        }
        stockCache.bumpGeneration();
        return orderNo;
    }

    public List<OrderRows.TransferRow> list(int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return orderAuxRepository.listTransfers(s, (p - 1) * s);
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }

    public record Cmd(long fromWarehouseId, long toWarehouseId, String remark, List<Item> items) {
    }

    public record Item(String barcode, int qty) {
    }
}
