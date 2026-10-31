package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.LossAppService;
import com.yilian.clothing.application.service.StocktakeAppService;
import com.yilian.clothing.domain.model.LossCase;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.StocktakeTask;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 盘点与挂账（Q48/Q57/Q58）。 */
@Tag(name = "08 盘点与挂账", description = "快照账面 → 扫码实盘 → 差异调账")
@RestController
@RequestMapping("/api/v1")
public class StocktakeController {

    private final StocktakeAppService stocktakeAppService;
    private final LossAppService lossAppService;

    public StocktakeController(StocktakeAppService stocktakeAppService, LossAppService lossAppService) {
        this.stocktakeAppService = stocktakeAppService;
        this.lossAppService = lossAppService;
    }

    @Operation(summary = "创建盘点任务（自动快照账面数量）")
    @PostMapping("/stocktakes")
    @RequirePermission(Permission.STOCKTAKE_WRITE)
    public Result<StocktakeTask> create(@Valid @RequestBody CreateStocktakeRequest request) {
        return Result.ok(stocktakeAppService.create(request.warehouseId()));
    }

    @Operation(summary = "扫码盘点（同件扫两次算两件）")
    @PostMapping("/stocktakes/{taskNo}/scan")
    @RequirePermission(Permission.STOCKTAKE_WRITE)
    public Result<StocktakeAppService.ScanResult> scan(@PathVariable String taskNo,
                                                       @Valid @RequestBody ScanRequest request) {
        return Result.ok(stocktakeAppService.scan(taskNo, request.barcode(), request.qty()));
    }

    @Operation(summary = "完成盘点（按差异调账并写流水）")
    @PostMapping("/stocktakes/{taskNo}/finish")
    @RequirePermission(Permission.STOCKTAKE_WRITE)
    public Result<StocktakeAppService.FinishResult> finish(@PathVariable String taskNo) {
        return Result.ok(stocktakeAppService.finish(taskNo));
    }

    @Operation(summary = "盘点任务详情")
    @GetMapping("/stocktakes/{taskNo}")
    public Result<StocktakeTask> detail(@PathVariable String taskNo) {
        return Result.ok(stocktakeAppService.detail(taskNo));
    }

    @Operation(summary = "差异清单（Q58）")
    @GetMapping("/stocktakes/{taskNo}/diff")
    public Result<List<StocktakeTask.Item>> diff(@PathVariable String taskNo) {
        return Result.ok(stocktakeAppService.diff(taskNo));
    }

    @Operation(summary = "挂账：实物找不到（Q48）")
    @PostMapping("/loss-cases")
    @RequirePermission(Permission.STOCK_WRITE)
    public Result<Map<String, String>> openLoss(@Valid @RequestBody LossRequest request) {
        String caseNo = lossAppService.open(request.barcode(), request.warehouseId(), request.qty(),
                request.reason());
        return Result.ok(Map.of("caseNo", caseNo));
    }

    @Operation(summary = "核销挂账：deduct=true 确认丢失（扣库存），false 表示找到（不动账）")
    @PostMapping("/loss-cases/{caseNo}/close")
    @RequirePermission(Permission.STOCK_WRITE)
    public Result<Void> closeLoss(@PathVariable String caseNo,
                                  @RequestParam(defaultValue = "true") boolean deduct) {
        lossAppService.close(caseNo, deduct);
        return Result.ok();
    }

    @Operation(summary = "挂账列表")
    @GetMapping("/loss-cases")
    public Result<List<LossCase>> listLoss(@RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return Result.ok(lossAppService.list(status, page, size));
    }

    public record CreateStocktakeRequest(@NotNull Long warehouseId) {
    }

    public record ScanRequest(@NotBlank String barcode, @Min(1) int qty) {
    }

    public record LossRequest(@NotBlank String barcode, @NotNull Long warehouseId, @Min(1) int qty,
                              String reason) {
    }
}
