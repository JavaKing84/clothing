package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.CatalogAppService;
import com.yilian.clothing.application.service.StockQueryAppService;
import com.yilian.clothing.domain.model.StockAlert;
import com.yilian.clothing.domain.model.StockMatrix;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 库存查询：矩阵视图 + 待补货 + 单 SKU（Q10/Q51/Q52/Q55/Q56）。 */
@Tag(name = "04 库存查询", description = "矩阵视图（款式+尺码为行、颜色为列）")
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockQueryAppService stockQueryAppService;
    private final CatalogAppService catalogAppService;

    public StockController(StockQueryAppService stockQueryAppService, CatalogAppService catalogAppService) {
        this.stockQueryAppService = stockQueryAppService;
        this.catalogAppService = catalogAppService;
    }

    @Operation(summary = "库存矩阵", description = "rows=款式+尺码，columns=颜色；cells 只含非零，减少 60% 体积")
    @GetMapping("/matrix")
    public Result<StockMatrix> matrix(@RequestParam(defaultValue = "1") long warehouseId,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Long supplierId,
                                      @RequestParam(required = false) String season,
                                      @RequestParam(required = false) String color,
                                      @RequestParam(required = false) String category) {
        return Result.ok(stockQueryAppService.matrix(warehouseId, keyword, supplierId, season, color, category));
    }

    @Operation(summary = "单 SKU 各仓库存")
    @GetMapping("/sku/{skuId}")
    public Result<Map<String, Object>> sku(@PathVariable long skuId) {
        return Result.ok(stockQueryAppService.skuStock(skuId));
    }

    @Operation(summary = "待补货清单（Q55 低于安全库存，默认 1 件）")
    @GetMapping("/alerts")
    public Result<List<StockAlert>> alerts(@RequestParam(defaultValue = "1") long warehouseId) {
        return Result.ok(stockQueryAppService.alerts(warehouseId));
    }

    @Operation(summary = "扫码解析（等价于 /catalog/barcode/{barcode}，语义更贴近收银场景）")
    @GetMapping("/resolve/{barcode}")
    public Result<CatalogAppService.ScanView> resolve(@PathVariable String barcode,
                                                      @RequestParam(defaultValue = "1") long warehouseId) {
        return Result.ok(catalogAppService.resolve(barcode, warehouseId));
    }
}
