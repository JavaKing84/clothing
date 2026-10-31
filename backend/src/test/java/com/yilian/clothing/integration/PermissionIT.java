package com.yilian.clothing.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字段级权限（Q68）：店员看不见进价和利润。
 *
 * <p>三层防御各有一条断言：
 * ① DTO 装配层留空（purchasePrice 是 null，不是 0——0 会被误读成"零成本高毛利"）；
 * ② 接口层无权限直接 403，而不是返回 0 让人以为"今天没赚钱"；
 * ③ 查询层 SQL 根本不 SELECT 成本列，所以不是"查出来再抹掉"。</p>
 *
 * <p>第 ③ 层用黑盒测不出来，只能由代码结构保证；这里验证的是 ①② 的对外表现。</p>
 */
class PermissionIT extends IntegrationTestSupport {

    @Test
    @DisplayName("店主能看到进价，店员看到的是 null（不是 0）")
    void clerkCannotSeePurchasePrice() {
        String owner = login("13800000000");
        String clerk = login("13900000001");

        var ownerSku = get("/api/v1/catalog/barcode/" + BARCODE, owner).data().path("sku");
        var clerkSku = get("/api/v1/catalog/barcode/" + BARCODE, clerk).data().path("sku");

        assertThat(ownerSku.path("purchasePrice").asDouble()).isGreaterThan(0);
        // 店员视角下这个字段可能是显式 null（Python 版），也可能整个键都缺席
        // （本版 SkuView 标了 @JsonInclude(NON_NULL)）。两者对前端等价：
        // `sku.purchasePrice` 都是 falsy。关键是**绝不能出现真实进价**。
        var clerkPrice = clerkSku.path("purchasePrice");
        assertThat(clerkPrice.isNull() || clerkPrice.isMissingNode())
                .as("店员看到的进价必须为空，实际=%s", clerkPrice)
                .isTrue();
        // 其余字段照常可用，不能因为权限就把整条 SKU 藏起来
        assertThat(clerkSku.path("spuNo").asText()).isEqualTo("W26-1003");
        assertThat(clerkSku.path("retailPrice").asDouble()).isGreaterThan(0);
    }

    @Test
    @DisplayName("店员请求利润报表返回 403，店主同一接口正常")
    void clerkForbiddenOnProfitReport() {
        String owner = login("13800000000");
        String clerk = login("13900000001");

        Api forbidden = get("/api/v1/reports/profit?warehouseId=1", clerk);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo(403);

        assertThat(get("/api/v1/reports/profit?warehouseId=1", owner).ok())
                .as("接口本身是好的，只是店员无权").isTrue();
    }

    @Test
    @DisplayName("未登录访问受保护接口被拒绝，而不是当游客放行")
    void anonymousRejected() {
        Api r = get("/api/v1/stock/matrix?warehouseId=1", null);
        assertThat(r.status()).isIn(401, 403);
    }

    @Test
    @DisplayName("店长与店主权限码数量不同，说明权限是真的按角色装配的")
    void permissionCodesDifferByRole() {
        var ownerPerms = post("/api/v1/auth/login",
                java.util.Map.of("phone", "13800000000", "password", "123456"), null)
                .data().path("user").path("permissions");
        var clerkPerms = post("/api/v1/auth/login",
                java.util.Map.of("phone", "13900000001", "password", "123456"), null)
                .data().path("user").path("permissions");

        assertThat(ownerPerms.size()).isEqualTo(15);
        assertThat(clerkPerms.size()).isEqualTo(9);
        assertThat(clerkPerms.toString()).doesNotContain("report:profit");
    }
}
