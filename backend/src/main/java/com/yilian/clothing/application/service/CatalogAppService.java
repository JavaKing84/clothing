package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.PageResult;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Spu;
import com.yilian.clothing.domain.model.Stock;
import com.yilian.clothing.domain.model.Supplier;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.StockRepository;
import com.yilian.clothing.domain.service.BarcodeParser;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品目录用例：建档、查询、扫码解析。
 *
 * <p>Q24「吊牌上没有二维码」与 Q36「扫码只带出一串条形码」决定了本类最核心的一个方法：
 * {@link #resolve(String)}。它必须能回答三种情况：
 * <ol>
 *   <li>码在库里 → 带出完整商品信息与当前库存；</li>
 *   <li>码不在库里，但码里编了货号（Q26）→ 解析出货号/尺码/颜色，引导建档并自动绑定；</li>
 *   <li>两者都没有 → 返回 bound=false，前端切到"手动输入货号"（Q38）。</li>
 * </ol>
 * 这三种情况的区分，是"扫码枪一扫就能用"与"扫码后还要手填五个字段"的分水岭。</p>
 */
@Service
public class CatalogAppService {

    private final CatalogRepository catalogRepository;
    private final StockRepository stockRepository;
    private final StockCache stockCache;

    public CatalogAppService(CatalogRepository catalogRepository, StockRepository stockRepository,
                             StockCache stockCache) {
        this.catalogRepository = catalogRepository;
        this.stockRepository = stockRepository;
        this.stockCache = stockCache;
    }

    private boolean canSeeCost() {
        Operator op = AuthContext.current();
        return op != null && op.canSeeCost();
    }

    public PageResult<Spu> list(String keyword, Long supplierId, String season, String category,
                                int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        List<Spu> rows = catalogRepository.listSpus(keyword, supplierId, season, category,
                s, (p - 1) * s, canSeeCost());
        long total = catalogRepository.countSpus(keyword, supplierId, season, category);
        return PageResult.of(rows, total, p, s);
    }

    public SpuDetail detail(String spuNo) {
        Spu spu = catalogRepository.findSpuByNo(spuNo, canSeeCost())
                .orElseThrow(() -> BizException.of(ResultCode.SPU_NOT_FOUND));
        List<SkuView> skus = catalogRepository.listSkusBySpu(spu.id(), canSeeCost());
        List<SkuStock> rows = new ArrayList<>();
        for (SkuView sku : skus) {
            int qty = stockRepository.find(sku.id(), 1).map(Stock::quantity).orElse(0);
            rows.add(new SkuStock(sku.id(), sku.skuCode(), sku.size(), sku.color(), qty));
        }
        return new SpuDetail(spu, rows);
    }

    /**
     * 建档：一个款式 × 多个颜色 × 多个尺码 → 生成全部 SKU 与初始库存行。
     *
     * <p>为什么一次建整组 SKU 而不是逐个建：店主办货是按"款"进的，
     * 一次进 2 色 4 码就是 8 个 SKU，逐个建要重复填 8 次货号与价格，必然填错。</p>
     */
    @Transactional
    public String create(CreateSpuCmd cmd) {
        if (cmd.colors() == null || cmd.colors().isEmpty() || cmd.sizes() == null || cmd.sizes().isEmpty()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "颜色与尺码至少各填一个");
        }
        Spu spu = new Spu(null, cmd.spuNo(), cmd.spuName(), cmd.category(), cmd.season(), cmd.supplierId(),
                null, cmd.purchasePrice(), cmd.retailPrice(), cmd.specialPrice(), cmd.purchaseDate(), 1, 0, null);
        Long spuId = catalogRepository.insertSpu(spu);

        for (String color : cmd.colors()) {
            for (String size : cmd.sizes()) {
                String skuCode = cmd.spuNo() + "-" + color + "-" + size;
                Long skuId = catalogRepository.insertSku(spuId, skuCode, size, color);
                // 初始库存 0：新货还没入库，先有"格子"再有货，扫码入库时才有地方加
                stockRepository.insertIfAbsent(skuId, 1, 0, 1);
                stockRepository.insertIfAbsent(skuId, 2, 0, 1);
                if (cmd.printBarcode()) {
                    // 自印码：YL-款号-色-码。Q24「没有二维码」的解法就在这里
                    catalogRepository.insertBarcode("YL-" + skuCode, skuId, 2);
                }
            }
        }
        stockCache.bumpGeneration();
        return cmd.spuNo();
    }

    @Transactional
    public void updatePrice(String spuNo, BigDecimal purchasePrice, BigDecimal retailPrice,
                            BigDecimal specialPrice, Integer version) {
        Spu spu = catalogRepository.findSpuByNo(spuNo, true)
                .orElseThrow(() -> BizException.of(ResultCode.SPU_NOT_FOUND));
        // 只改价格也要用版本号：两个店主同时改价，不能"后写的赢"
        catalogRepository.updateSpuPrice(spu.id(), purchasePrice, retailPrice, specialPrice,
                version == null ? spu.version() : version);
    }

    public List<Supplier> suppliers() {
        return catalogRepository.listSuppliers();
    }

    @Transactional
    public Long createSupplier(String name, String contact, String phone) {
        return catalogRepository.insertSupplier(name, contact, phone);
    }

    /**
     * 扫码解析（Q36/Q38）。
     *
     * @param barcode 扫到的原始内容
     * @param warehouseId 查库存的仓库
     */
    public ScanView resolve(String barcode, long warehouseId) {
        BarcodeParser.Parsed parsed = BarcodeParser.parse(barcode);
        SkuView sku = catalogRepository.findSkuByBarcode(barcode, canSeeCost()).orElse(null);
        if (sku == null && parsed.spuNo() != null) {
            // 码里编了货号但没绑定：按货号+尺码+颜色找 SKU（Q26 的价值就在这里）
            sku = matchByParsed(parsed);
        }
        if (sku == null) {
            return new ScanView(barcode, false, parsed.spuNo(), parsed.size(), parsed.color(),
                    parsed.supplierName(), parsed.purchaseDate(), null, 0, parsed.plainBarcode());
        }
        int qty = stockRepository.find(sku.id(), warehouseId).map(Stock::quantity).orElse(0);
        return new ScanView(barcode, true, sku.spuNo(), sku.size(), sku.color(), null, null, sku, qty,
                parsed.plainBarcode());
    }

    private SkuView matchByParsed(BarcodeParser.Parsed parsed) {
        if (parsed.size() == null || parsed.color() == null) {
            return null;
        }
        return catalogRepository.findSpuByNo(parsed.spuNo(), canSeeCost())
                .flatMap(spu -> catalogRepository.listSkusBySpu(spu.id(), canSeeCost()).stream()
                        .filter(k -> k.size().equalsIgnoreCase(parsed.size())
                                && k.color().equals(parsed.color()))
                        .findFirst())
                .orElse(null);
    }

    /** Q38：扫不出码时手输货号 → 返回该款全部 SKU 供选择 */
    public List<SkuView> search(String keyword) {
        return catalogRepository.searchSkusByKeyword(keyword, 50, canSeeCost());
    }

    public Map<String, Object> meta() {
        return Map.of(
                "sizes", List.of("S", "M", "L", "XL", "XXL"),
                "colors", List.of("黑色", "白色", "米白", "杏色", "燕麦色", "碎花蓝", "深蓝", "雾霾蓝"),
                "seasons", List.of("春季", "夏季", "秋季", "冬季", "四季"),
                "categories", List.of("连衣裙", "上衣", "针织衫", "裤装", "半裙", "外套"),
                "payTypes", List.of(
                        Map.of("code", "CASH", "label", "现金"),
                        Map.of("code", "WECHAT", "label", "微信"),
                        Map.of("code", "ALIPAY", "label", "支付宝"),
                        Map.of("code", "CARD", "label", "刷卡")),
                "suppliers", catalogRepository.listSuppliers(),
                "seasonsInUse", catalogRepository.distinctSeasons(),
                "categoriesInUse", catalogRepository.distinctCategories()
        );
    }

    /**
     * @param bound   是否已绑定 SKU。false 时前端应引导建档（Q36）或手输货号（Q38）
     * @param sku     命中的 SKU，可能为 null
     * @param stockQty 该 SKU 在指定仓的账面数
     */
    public record ScanView(String barcode, boolean bound, String parsedSpuNo, String parsedSize,
                           String parsedColor, String parsedSupplier, LocalDate parsedDate,
                           SkuView sku, int stockQty, boolean plainBarcode) {
    }

    public record SkuStock(Long skuId, String skuCode, String size, String color, int quantity) {
    }

    public record SpuDetail(Spu spu, List<SkuStock> skus) {
    }

    public record CreateSpuCmd(String spuNo, String spuName, String category, String season, Long supplierId,
                               BigDecimal purchasePrice, BigDecimal retailPrice, BigDecimal specialPrice,
                               LocalDate purchaseDate, List<String> colors, List<String> sizes,
                               boolean printBarcode) {
    }
}
