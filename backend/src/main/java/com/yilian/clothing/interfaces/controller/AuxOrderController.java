package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.ReturnAppService;
import com.yilian.clothing.application.service.TransferAppService;
import com.yilian.clothing.domain.model.OrderRows;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 退货与调拨（Q17/Q21）。 */
@Tag(name = "07 退货与调拨", description = "退货记负收入；调拨一出一入原子完成")
@RestController
@RequestMapping("/api/v1")
public class AuxOrderController {

    private final ReturnAppService returnAppService;
    private final TransferAppService transferAppService;

    public AuxOrderController(ReturnAppService returnAppService, TransferAppService transferAppService) {
        this.returnAppService = returnAppService;
        this.transferAppService = transferAppService;
    }

    @Operation(summary = "退货/换货（Q17）")
    @PostMapping("/returns")
    @RequirePermission(Permission.SALE_CREATE)
    public Result<Map<String, String>> createReturn(@Valid @RequestBody ReturnRequest request) {
        List<ReturnAppService.Item> items = request.items().stream()
                .map(i -> new ReturnAppService.Item(i.barcode(), i.qty(), i.refundAmount()))
                .toList();
        String no = returnAppService.create(new ReturnAppService.Cmd(request.saleOrderNo(),
                request.warehouseId(), request.returnType(), request.restock(), request.reason(), items));
        return Result.ok(Map.of("orderNo", no));
    }

    @Operation(summary = "退货列表")
    @GetMapping("/returns")
    public Result<List<OrderRows.ReturnRow>> listReturns(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return Result.ok(returnAppService.list(page, size));
    }

    @Operation(summary = "门店调拨（Q21）：按仓库+SKU 全序加锁，避免对锁死循环")
    @PostMapping("/transfers")
    @RequirePermission(Permission.TRANSFER_WRITE)
    public Result<Map<String, String>> createTransfer(@Valid @RequestBody TransferRequest request) {
        List<TransferAppService.Item> items = request.items().stream()
                .map(i -> new TransferAppService.Item(i.barcode(), i.qty()))
                .toList();
        String no = transferAppService.create(new TransferAppService.Cmd(
                request.fromWarehouseId(), request.toWarehouseId(), request.remark(), items));
        return Result.ok(Map.of("orderNo", no));
    }

    @Operation(summary = "调拨列表")
    @GetMapping("/transfers")
    public Result<List<OrderRows.TransferRow>> listTransfers(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(transferAppService.list(page, size));
    }

    public record ReturnRequest(String saleOrderNo, @NotNull Long warehouseId, String returnType,
                                boolean restock, String reason, @Valid List<ReturnItem> items) {
    }

    public record ReturnItem(@NotBlank String barcode, @Min(1) int qty, @NotNull BigDecimal refundAmount) {
    }

    public record TransferRequest(@NotNull Long fromWarehouseId, @NotNull Long toWarehouseId,
                                  String remark, @Valid List<TransferItem> items) {
    }

    public record TransferItem(@NotBlank String barcode, @Min(1) int qty) {
    }
}
