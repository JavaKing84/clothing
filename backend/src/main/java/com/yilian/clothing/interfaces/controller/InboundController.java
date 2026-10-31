package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.InboundAppService;
import com.yilian.clothing.domain.model.InboundOrder;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** 扫码入库（Q18/Q39~Q44）。 */
@Tag(name = "05 入库", description = "DRAFT 只记账，SUBMIT 才动库存")
@RestController
@RequestMapping("/api/v1/inbound")
public class InboundController {

    private final InboundAppService inboundAppService;

    public InboundController(InboundAppService inboundAppService) {
        this.inboundAppService = inboundAppService;
    }

    @Operation(summary = "建入库单（可一次带多条明细，对应 Q40 的整单录入）")
    @PostMapping("/orders")
    @RequirePermission(Permission.INBOUND_CREATE)
    public Result<InboundOrder> create(@Valid @RequestBody CreateRequest request) {
        List<InboundAppService.ItemCmd> items = request.items() == null ? List.of()
                : request.items().stream()
                .map(i -> new InboundAppService.ItemCmd(i.barcode(), i.qty(), i.purchasePrice()))
                .toList();
        return Result.ok(inboundAppService.create(new InboundAppService.CreateCmd(
                request.warehouseId(), request.supplierId(), request.remark(), items)));
    }

    @Operation(summary = "扫一件加入入库单（Q41 同单同码不重复计数）")
    @PostMapping("/orders/{orderNo}/items")
    @RequirePermission(Permission.INBOUND_CREATE)
    public Result<InboundAppService.AddResult> addItem(@PathVariable String orderNo,
                                                       @Valid @RequestBody ItemRequest request) {
        return Result.ok(inboundAppService.addItem(orderNo, request.barcode(), request.qty(),
                request.purchasePrice()));
    }

    @Operation(summary = "撤销上一件（Q43）")
    @DeleteMapping("/orders/{orderNo}/items/last")
    @RequirePermission(Permission.INBOUND_CREATE)
    public Result<InboundAppService.UndoResult> undoLast(@PathVariable String orderNo) {
        return Result.ok(inboundAppService.undoLast(orderNo));
    }

    @Operation(summary = "提交入库（唯一真正加库存的入口）")
    @PostMapping("/orders/{orderNo}/submit")
    @RequirePermission(Permission.INBOUND_CREATE)
    public Result<InboundAppService.SubmitResult> submit(@PathVariable String orderNo) {
        return Result.ok(inboundAppService.submit(orderNo));
    }

    @Operation(summary = "入库单详情（含累计件数 Q42）")
    @GetMapping("/orders/{orderNo}")
    public Result<InboundOrder> detail(@PathVariable String orderNo) {
        return Result.ok(inboundAppService.detail(orderNo));
    }

    @Operation(summary = "入库单列表")
    @GetMapping("/orders")
    public Result<List<InboundOrder>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return Result.ok(inboundAppService.list(page, size));
    }

    public record CreateRequest(@NotNull Long warehouseId, @NotNull Long supplierId,
                                String remark, List<ItemRequest> items) {
    }

    public record ItemRequest(@NotBlank String barcode, @Min(1) int qty, BigDecimal purchasePrice) {
    }
}
