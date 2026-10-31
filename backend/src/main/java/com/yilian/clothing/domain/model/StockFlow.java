package com.yilian.clothing.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出入库流水（只追加，ADR-09）。
 *
 * <p>18 个字段，如果用全参构造器，调用处会出现 18 个连在一起的实参——这是典型的
 * "telescoping constructor" 反模式，改一个字段顺序就静默出错。
 * 因此提供 {@link Builder}：显式命名参数，编译器帮你检查类型。</p>
 *
 * <p>为什么流水要冗余 spuNo/spuName/size/color：流水是**账本**，账本必须自证。
 * 三年后款式改名了，账本上的名字不能跟着变，否则"去年卖的是什么"就说不清了。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StockFlow(
        Long id,
        String flowNo,
        FlowType flowType,
        Long skuId,
        Long spuId,
        String spuNo,
        String spuName,
        String size,
        String color,
        Long warehouseId,
        int quantity,
        int signedQty,
        int beforeQty,
        int afterQty,
        BigDecimal salePrice,
        BigDecimal purchasePrice,
        String payType,
        Long operatorId,
        String operatorName,
        String bizNo,
        String scanToken,
        String clientToken,
        int status,
        String revertFlowNo,
        String remark,
        LocalDateTime createTime
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long id;
        private String flowNo;
        private FlowType flowType;
        private Long skuId;
        private Long spuId;
        private String spuNo;
        private String spuName;
        private String size;
        private String color;
        private Long warehouseId;
        private int quantity;
        private int signedQty;
        private int beforeQty;
        private int afterQty;
        private BigDecimal salePrice;
        private BigDecimal purchasePrice;
        private String payType;
        private Long operatorId;
        private String operatorName;
        private String bizNo;
        private String scanToken;
        private String clientToken;
        private int status = 1;
        private String revertFlowNo;
        private String remark;
        private LocalDateTime createTime = LocalDateTime.now();

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder flowNo(String v) {
            this.flowNo = v;
            return this;
        }

        public Builder flowType(FlowType v) {
            this.flowType = v;
            return this;
        }

        public Builder sku(SkuView sku) {
            this.skuId = sku.id();
            this.spuId = sku.spuId();
            this.spuNo = sku.spuNo();
            this.spuName = sku.spuName();
            this.size = sku.size();
            this.color = sku.color();
            return this;
        }

        public Builder warehouseId(Long v) {
            this.warehouseId = v;
            return this;
        }

        /** quantity 与 signedQty 一起设，避免出现"数量正、方向反"的自相矛盾数据 */
        public Builder qty(int quantity, FlowType type) {
            this.quantity = quantity;
            this.signedQty = type.isOutbound() ? -quantity : quantity;
            return this;
        }

        /** 冲正专用：符号取原方向的反 */
        public Builder reverseQty(int quantity, FlowType originalType) {
            this.quantity = quantity;
            this.signedQty = originalType.isOutbound() ? quantity : -quantity;
            return this;
        }

        /** 显式指定带符号数量（盘点调整这种可正可负的场景用） */
        public Builder signedQty(int v) {
            this.signedQty = v;
            return this;
        }

        public Builder beforeAfter(int before, int after) {
            this.beforeQty = before;
            this.afterQty = after;
            return this;
        }

        public Builder prices(BigDecimal salePrice, BigDecimal purchasePrice) {
            this.salePrice = salePrice;
            this.purchasePrice = purchasePrice;
            return this;
        }

        public Builder payType(String v) {
            this.payType = v;
            return this;
        }

        public Builder operator(Operator op) {
            this.operatorId = op.id();
            this.operatorName = op.nickname();
            return this;
        }

        public Builder bizNo(String v) {
            this.bizNo = v;
            return this;
        }

        public Builder scanToken(String v) {
            this.scanToken = v;
            return this;
        }

        public Builder clientToken(String v) {
            this.clientToken = v;
            return this;
        }

        public Builder status(int v) {
            this.status = v;
            return this;
        }

        public Builder revertFlowNo(String v) {
            this.revertFlowNo = v;
            return this;
        }

        public Builder remark(String v) {
            this.remark = v;
            return this;
        }

        public Builder createTime(LocalDateTime v) {
            this.createTime = v;
            return this;
        }

        public StockFlow build() {
            return new StockFlow(id, flowNo, flowType, skuId, spuId, spuNo, spuName, size, color, warehouseId,
                    quantity, signedQty, beforeQty, afterQty, salePrice, purchasePrice, payType, operatorId,
                    operatorName, bizNo, scanToken, clientToken, status, revertFlowNo, remark, createTime);
        }
    }
}
