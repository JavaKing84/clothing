package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.SaleAppService;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.SaleOrder;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 扫码出库/收银（Q9/Q15/Q16/Q45~Q50）。 */
@Tag(name = "06 销售", description = "加购即扣库存；结账补价格调整流水")
@RestController
@RequestMapping("/api/v1/sales")
public class SaleController {

    private final SaleAppService saleAppService;

    public SaleController(SaleAppService saleAppService) {
        this.saleAppService = saleAppService;
    }

    @Operation(summary = "开单（购物车）")
    @PostMapping("/orders")
    @RequirePermission(Permission.SALE_CREATE)
    public Result<SaleOrder> open(@Valid @RequestBody OpenRequest request) {
        return Result.ok(saleAppService.open(request.warehouseId(), request.remark()));
    }

    @Operation(summary = "扫一件加入购物车",
            description = "必须带 scanToken（UUID）；库存不足返回 409 + code=1001 + data.remainQty")
    @PostMapping("/orders/{orderNo}/items")
    @RequirePermission(Permission.SALE_CREATE)
    public Result<SaleAppService.AddResult> addItem(@PathVariable String orderNo,
                                                    @Valid @RequestBody AddItemRequest request) {
        return Result.ok(saleAppService.addItem(orderNo, request.barcode(), request.qty(),
                request.scanToken(), request.force(), request.reason()));
    }

    @Operation(summary = "移除明细（顾客放回，库存回补）")
    @DeleteMapping("/orders/{orderNo}/items/{itemId}")
    @RequirePermission(Permission.SALE_CREATE)
    public Result<SaleOrder> removeItem(@PathVariable String orderNo, @PathVariable long itemId) {
        return Result.ok(saleAppService.removeItem(orderNo, itemId));
    }

    @Operation(summary = "结账（Q45/Q49：实收金额手输）")
    @PostMapping("/orders/{orderNo}/checkout")
    @RequirePermission(Permission.SALE_CREATE)
    public Result<SaleOrder> checkout(@PathVariable String orderNo,
                                      @Valid @RequestBody CheckoutRequest request) {
        return Result.ok(saleAppService.checkout(orderNo, new SaleAppService.CheckoutCmd(
                request.receivedAmount(), request.discountAmount(), request.payType(),
                request.customerName(), request.customerPhone(), request.remark())));
    }

    @Operation(summary = "取消未结账的单（逐条冲正）")
    @PostMapping("/orders/{orderNo}/cancel")
    @RequirePermission(Permission.SALE_CREATE)
    public Result<SaleOrder> cancel(@PathVariable String orderNo,
                                    @RequestBody(required = false) CancelRequest request) {
        return Result.ok(saleAppService.cancel(orderNo, request == null ? null : request.reason()));
    }

    @Operation(summary = "单据详情")
    @GetMapping("/orders/{orderNo}")
    public Result<SaleOrder> detail(@PathVariable String orderNo) {
        return Result.ok(saleAppService.detail(orderNo));
    }

    @Operation(summary = "小票数据（Q64 打印）")
    @GetMapping("/orders/{orderNo}/receipt")
    public Result<SaleAppService.Receipt> receipt(@PathVariable String orderNo) {
        return Result.ok(saleAppService.receipt(orderNo));
    }

    @Operation(summary = "当日单据列表（店员只看自己的）")
    @GetMapping("/orders")
    public Result<List<SaleOrder>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(saleAppService.list(date, page, size));
    }

    public record OpenRequest(Long warehouseId, String remark) {
        public OpenRequest {
            if (warehouseId == null) {
                warehouseId = 1L;
            }
        }
    }

    public record AddItemRequest(@NotBlank String barcode, @Min(1) int qty,
                                 @NotBlank String scanToken, boolean force, String reason) {
    }

    public record CheckoutRequest(BigDecimal receivedAmount, BigDecimal discountAmount, String payType,
                                  String customerName, String customerPhone, String remark) {
    }

    public record CancelRequest(String reason) {
    }
}
