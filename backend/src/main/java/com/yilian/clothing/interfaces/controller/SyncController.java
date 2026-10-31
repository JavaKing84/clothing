package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.SyncAppService;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.ReconcileBatch;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 离线同步（Q23）：逐条裁决，整批留痕。 */
@Tag(name = "11 离线同步", description = "断网扫码后批量对账")
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncAppService syncAppService;

    public SyncController(SyncAppService syncAppService) {
        this.syncAppService = syncAppService;
    }

    @Operation(summary = "离线批次对账",
            description = "返回每条记录的 ACCEPTED/DUPLICATED/CONFLICTED/REJECTED，绝不整批回滚")
    @PostMapping("/batch")
    @RequirePermission(Permission.SALE_CREATE)
    public Result<ReconcileBatch> batch(@Valid @RequestBody SyncRequest request) {
        List<SyncAppService.Item> items = request.items().stream()
                .map(i -> new SyncAppService.Item(i.clientToken(), i.deviceSeq(), i.barcode(),
                        i.scanType(), i.quantity(), request.warehouseId()))
                .toList();
        return Result.ok(syncAppService.batch(new SyncAppService.Cmd(request.batchNo(),
                request.deviceId(), request.warehouseId(), items)));
    }

    @Operation(summary = "批次结果回查")
    @GetMapping("/batches/{batchNo}")
    public Result<ReconcileBatch> detail(@PathVariable String batchNo) {
        return Result.ok(syncAppService.batchDetail(batchNo));
    }

    @Operation(summary = "断网前预热：商品+库存快照")
    @GetMapping("/bootstrap")
    public Result<SyncAppService.Bootstrap> bootstrap(@RequestParam(defaultValue = "1") long warehouseId) {
        return Result.ok(syncAppService.bootstrap(warehouseId));
    }

    public record SyncRequest(@NotBlank String batchNo, @NotBlank String deviceId,
                              long warehouseId,
                              @NotEmpty List<SyncItem> items) {
        public SyncRequest {
            if (warehouseId <= 0) {
                warehouseId = 1L;
            }
        }
    }

    public record SyncItem(@NotBlank String clientToken, @Min(1) long deviceSeq,
                           @NotBlank String barcode, int scanType, @Min(1) int quantity) {
    }
}
