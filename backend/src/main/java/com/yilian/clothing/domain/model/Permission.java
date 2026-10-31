package com.yilian.clothing.domain.model;

/**
 * 权限码。用枚举而不是字符串常量类，是因为 {@code @RequirePermission("stock:read")}
 * 写错字符串编译器不会报错，而 {@code Permission.STOCK_READ} 写错立刻编译失败。
 *
 * <p>注解里仍写字符串（注解参数只能常量），但每个注解都由单元测试
 * {@code PermissionCatalogTest} 校验"每个注解值都能被 Permission.valueOf 解析"，
 * 用测试补上编译期检查的缺口。</p>
 */
public enum Permission {

    /** 能看库存（含矩阵、流水） */
    STOCK_READ("stock:read"),
    /** 能改库存（入库、盘点、挂账） */
    STOCK_WRITE("stock:write"),
    /** 能看进货价与毛利 —— Q68 的核心开关，CLERK 没有 */
    STOCK_COST_READ("stock:cost:read"),
    /** 能改商品资料 */
    CATALOG_WRITE("catalog:write"),
    INBOUND_CREATE("inbound:create"),
    SALE_CREATE("sale:create"),
    /** 能取消已结账的单（不是撤销流水） */
    SALE_VOID("sale:void"),
    TRANSFER_WRITE("transfer:write"),
    STOCKTAKE_WRITE("stocktake:write"),
    /** 能撤销任意流水 */
    LEDGER_REVERSE("ledger:reverse"),
    /** 只能撤销自己 24 小时内的流水（Q69 与 Q68 的平衡点） */
    LEDGER_REVERSE_SELF("ledger:reverse:self"),
    REPORT_READ("report:read"),
    /** 毛利报表，OWNER 专属 */
    REPORT_PROFIT_READ("report:profit:read"),
    EXPORT_RUN("export:run"),
    USER_MANAGE("user:manage");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
