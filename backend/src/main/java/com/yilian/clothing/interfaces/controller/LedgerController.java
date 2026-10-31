package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.LedgerAppService;
import com.yilian.clothing.common.PageResult;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 出入库流水与撤销（Q60/Q61/Q62/Q69）。 */
@Tag(name = "09 流水", description = "只追加账本；撤销走冲正，不改原记录")
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerAppService ledgerAppService;

    public LedgerController(LedgerAppService ledgerAppService) {
        this.ledgerAppService = ledgerAppService;
    }

    @Operation(summary = "流水查询（按款式/日期/操作人/类型）")
    @GetMapping
    public Result<PageResult<StockFlow>> query(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) String spuNo,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        FlowType flowType = type == null ? null : FlowType.of(type);
        return Result.ok(ledgerAppService.query(warehouseId, flowType, spuNo, operatorId, from, to, page, size));
    }

    @Operation(summary = "撤销流水（Q62 留痕）",
            description = "店员只能撤自己 24 小时内的；店主可撤任意。撤销=插入反向流水")
    @PostMapping("/{flowNo}/reverse")
    @RequirePermission({Permission.LEDGER_REVERSE, Permission.LEDGER_REVERSE_SELF})
    public Result<LedgerAppService.ReverseResult> reverse(@PathVariable String flowNo,
                                                          @RequestBody(required = false) ReverseRequest request) {
        return Result.ok(ledgerAppService.reverse(flowNo, request == null || request.reason() == null
                ? "手工撤销" : request.reason()));
    }

    public record ReverseRequest(String reason) {
    }
}
