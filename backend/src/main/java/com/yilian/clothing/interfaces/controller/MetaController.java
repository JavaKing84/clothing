package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.MetaAppService;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.Warehouse;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 元数据：尺码/颜色/季节/品类/支付方式/仓库/员工。 */
@Tag(name = "02 元数据", description = "下拉选项与组织架构")
@RestController
@RequestMapping("/api/v1")
public class MetaController {

    private final MetaAppService metaAppService;

    public MetaController(MetaAppService metaAppService) {
        this.metaAppService = metaAppService;
    }

    @Operation(summary = "全部下拉选项（建档页/结账页共用，避免前端硬编码）")
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta() {
        return Result.ok(metaAppService.meta());
    }

    @Operation(summary = "门店/仓库列表（Q20 两个门店）")
    @GetMapping("/warehouses")
    public Result<List<Warehouse>> warehouses() {
        return Result.ok(metaAppService.warehouses());
    }

    @Operation(summary = "员工列表（仅店主）")
    @GetMapping("/users")
    @RequirePermission(Permission.USER_MANAGE)
    public Result<List<Map<String, Object>>> users() {
        return Result.ok(metaAppService.users());
    }
}
