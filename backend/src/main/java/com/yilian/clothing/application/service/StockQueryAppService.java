package com.yilian.clothing.application.service;

import com.yilian.clothing.domain.model.StockAlert;
import com.yilian.clothing.domain.model.StockMatrix;
import com.yilian.clothing.domain.model.StockMatrixRow;
import com.yilian.clothing.domain.repository.StockRepository;
import com.yilian.clothing.domain.service.StockMatrixBuilder;
import com.yilian.clothing.infrastructure.cache.StockCache;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存查询用例（Q10/Q51/Q52）。
 *
 * <p>矩阵接口是整个系统访问量最高的读接口：店员随时在看，
 * 店主一天看几十次（Q74 要求 3 秒内出结果）。因此这里是唯一上了缓存的地方。</p>
 *
 * <p>缓存策略（ADR-07）：key 带代际号，写操作 INCR 一次即全站失效；
 * TTL 60 秒兜底，防止代际号丢事件（比如有人直接改库）。</p>
 */
@Service
public class StockQueryAppService {

    private final StockRepository stockRepository;
    private final StockCache stockCache;

    public StockQueryAppService(StockRepository stockRepository, StockCache stockCache) {
        this.stockRepository = stockRepository;
        this.stockCache = stockCache;
    }

    public StockMatrix matrix(long warehouseId, String keyword, Long supplierId, String season,
                              String color, String category) {
        String signature = signature(warehouseId, keyword, supplierId, season, color, category);
        String key = stockCache.matrixKey(warehouseId, signature);
        return stockCache.get(key, StockMatrix.class).orElseGet(() -> {
            List<StockMatrixRow> rows = stockRepository.matrixRows(warehouseId, keyword, supplierId, season,
                    color, category);
            StockMatrix matrix = StockMatrixBuilder.build(rows);
            stockCache.putMatrix(key, matrix);
            return matrix;
        });
    }

    public List<StockAlert> alerts(long warehouseId) {
        String key = stockCache.alertKey(warehouseId);
        return stockCache.get(key, StockAlert[].class)
                .map(java.util.Arrays::asList)
                .orElseGet(() -> {
                    List<StockAlert> alerts = stockRepository.alerts(warehouseId);
                    stockCache.putAlerts(key, alerts);
                    return alerts;
                });
    }

    /** 扫码后点开某件看"各仓还有几件" */
    public Map<String, Object> skuStock(long skuId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skuId", skuId);
        result.put("warehouses", List.of(
                Map.of("warehouseId", 1, "quantity", stockRepository.find(skuId, 1).map(s -> s.quantity()).orElse(0)),
                Map.of("warehouseId", 2, "quantity", stockRepository.find(skuId, 2).map(s -> s.quantity()).orElse(0))));
        return result;
    }

    public long totalQuantity(long warehouseId) {
        return stockRepository.totalQuantity(warehouseId);
    }

    /**
     * 缓存签名：把查询条件拼成稳定字符串再哈希。
     * 直接用 String.hashCode 会有碰撞（两个不同条件命中同一缓存），
     * 但代价只是"偶发看到别人的筛选结果 60 秒"——在门店场景可接受，
     * 所以不引入 MD5/SHA 的计算开销。这个取舍要写下来，否则后人会以为是 bug。
     */
    private String signature(long warehouseId, String keyword, Long supplierId, String season,
                             String color, String category) {
        return Integer.toHexString(java.util.Objects.hash(warehouseId, keyword, supplierId, season, color, category));
    }
}
