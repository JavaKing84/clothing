package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.CatalogAppService;
import com.yilian.clothing.common.PageResult;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Spu;
import com.yilian.clothing.domain.model.Supplier;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 商品建档与扫码解析（Q24~Q38）。 */
@Tag(name = "03 商品目录", description = "建档、查款、扫码解析")
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogAppService catalogAppService;

    public CatalogController(CatalogAppService catalogAppService) {
        this.catalogAppService = catalogAppService;
    }

    @Operation(summary = "款式分页查询")
    @GetMapping("/products")
    public Result<PageResult<Spu>> list(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Long supplierId,
                                        @RequestParam(required = false) String season,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return Result.ok(catalogAppService.list(keyword, supplierId, season, category, page, size));
    }

    @Operation(summary = "款式详情（含各 SKU 现有库存）")
    @GetMapping("/products/{spuNo}")
    public Result<CatalogAppService.SpuDetail> detail(@PathVariable String spuNo) {
        return Result.ok(catalogAppService.detail(spuNo));
    }

    @Operation(summary = "建档：一次生成 颜色×尺码 全部 SKU", description = "仅店主可操作")
    @PostMapping("/products")
    @RequirePermission(Permission.CATALOG_WRITE)
    public Result<Map<String, String>> create(@Valid @RequestBody CreateRequest request) {
        String spuNo = catalogAppService.create(new CatalogAppService.CreateSpuCmd(
                request.spuNo(), request.spuName(), request.category(), request.season(),
                request.supplierId(), request.purchasePrice(), request.retailPrice(),
                request.specialPrice(), request.purchaseDate(), request.colors(), request.sizes(),
                request.printBarcode()));
        return Result.ok(Map.of("spuNo", spuNo));
    }

    @Operation(summary = "改价（乐观锁，需带 version）")
    @PutMapping("/products/{spuNo}")
    @RequirePermission(Permission.CATALOG_WRITE)
    public Result<Void> updatePrice(@PathVariable String spuNo, @Valid @RequestBody PriceRequest request) {
        catalogAppService.updatePrice(spuNo, request.purchasePrice(), request.retailPrice(),
                request.specialPrice(), request.version());
        return Result.ok();
    }

    @Operation(summary = "供货商列表（Q31）")
    @GetMapping("/suppliers")
    public Result<List<Supplier>> suppliers() {
        return Result.ok(catalogAppService.suppliers());
    }

    @Operation(summary = "新增供货商")
    @PostMapping("/suppliers")
    @RequirePermission(Permission.CATALOG_WRITE)
    public Result<Map<String, Long>> createSupplier(@Valid @RequestBody SupplierRequest request) {
        Long id = catalogAppService.createSupplier(request.supplierName(), request.contact(), request.phone());
        return Result.ok(Map.of("id", id));
    }

    @Operation(summary = "扫码解析（Q36/Q38）",
            description = "bound=false 表示该码未建档，前端应引导建档或手输货号")
    @GetMapping("/barcode/{barcode}")
    public Result<CatalogAppService.ScanView> resolve(@PathVariable String barcode,
                                                      @RequestParam(defaultValue = "1") long warehouseId) {
        return Result.ok(catalogAppService.resolve(barcode, warehouseId));
    }

    @Operation(summary = "按货号/品名搜索 SKU（Q38 扫不出码时手输）")
    @GetMapping("/search")
    public Result<List<SkuView>> search(@RequestParam String keyword) {
        return Result.ok(catalogAppService.search(keyword));
    }

    public record CreateRequest(
            @NotBlank String spuNo,
            @NotBlank String spuName,
            @NotBlank String category,
            @NotBlank String season,
            @NotNull Long supplierId,
            @NotNull @DecimalMin("0.00") BigDecimal purchasePrice,
            @NotNull @DecimalMin("0.00") BigDecimal retailPrice,
            BigDecimal specialPrice,
            LocalDate purchaseDate,
            @NotEmpty List<String> colors,
            @NotEmpty List<String> sizes,
            boolean printBarcode) {
    }

    public record PriceRequest(BigDecimal purchasePrice, BigDecimal retailPrice, BigDecimal specialPrice,
                               Integer version) {
    }

    public record SupplierRequest(@NotBlank String supplierName, String contact, String phone) {
    }
}
