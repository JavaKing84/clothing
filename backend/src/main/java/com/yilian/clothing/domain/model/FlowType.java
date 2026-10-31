package com.yilian.clothing.domain.model;

/**
 * 流水类型。数字编码写库（省空间、便于按类型建索引），
 * 语义留在枚举里，避免代码里出现魔法数字 2。
 */
public enum FlowType {

    IN(1, "入库"),
    SALE(2, "出库"),
    REVERSE(3, "撤销冲正"),
    TRANSFER_OUT(4, "调拨出"),
    TRANSFER_IN(5, "调拨入"),
    STOCKTAKE(6, "盘点调整"),
    RETURN(7, "退货入库"),
    /** 数量为 0 的价格调整流水：整单折扣/抹零，只影响金额不影响库存 */
    PRICE_ADJUST(8, "价格调整");

    private final int code;
    private final String label;

    FlowType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static FlowType of(int code) {
        for (FlowType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知流水类型: " + code);
    }

    /** 是否减少库存（决定 signed_qty 符号）。冲正另算：它取原方向的反。 */
    public boolean isOutbound() {
        return this == SALE || this == TRANSFER_OUT;
    }

    /** 计入销售额的类型（Q59）：只有销售与退货影响营业额 */
    public boolean affectsRevenue() {
        return this == SALE || this == RETURN || this == PRICE_ADJUST;
    }
}
