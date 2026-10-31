package com.yilian.clothing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 集成测试底座：启动真实 Spring 容器 + 真实 MySQL/Redis，然后**打真 HTTP**。
 *
 * <p>为什么不用 MockMvc 或 Mockito：本项目最容易出事的地方不在算法，
 * 而在"行锁、事务边界、幂等唯一索引、字段级权限"这些只有真库才能暴露的环节。
 * 开发期真实踩到的 1062 并发撞车、开单后立刻查详情 404，都是靠真 HTTP 才现形的。</p>
 *
 * <p>HTTP 客户端用 JDK 自带的 {@link HttpClient} 而不是 TestRestTemplate：
 * 它线程安全、无连接池握手差异，30 个线程打进去测的是数据库的并发，不是客户端的排队。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTestSupport {

    /** 种子数据里真实存在、带进价的一条：货号 W26-1003 深蓝 L（进价 78） */
    protected static final String BARCODE = "YL-W26-1003-NV-L";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    protected String base() {
        return "http://127.0.0.1:" + port;
    }

    /** 一次调用的结果：状态码 + 解析后的 JSON，权限/库存不足都要看状态码。 */
    protected record Api(int status, JsonNode body) {
        public int code() {
            return body.path("code").asInt(-1);
        }

        public JsonNode data() {
            return body.path("data");
        }

        public boolean ok() {
            return status == 200 && code() == 0;
        }
    }

    protected Api call(String method, String path, Object body, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(60));
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Api(resp.statusCode(), json.readTree(resp.body()));
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " 调用失败", e);
        }
    }

    protected Api get(String path, String token) {
        return call("GET", path, null, token);
    }

    protected Api post(String path, Object body, String token) {
        return call("POST", path, body, token);
    }

    /** 登录拿 token。店主 15 个权限码，店员 9 个（Q66/Q67）。 */
    protected String login(String phone) {
        Api r = post("/api/v1/auth/login", java.util.Map.of("phone", phone, "password", "123456"), null);
        if (!r.ok()) {
            throw new IllegalStateException("登录失败：" + r.body());
        }
        return r.data().path("token").asText();
    }

    protected int stockOf(String barcode, String token) {
        return get("/api/v1/catalog/barcode/" + barcode, token).data().path("stockQty").asInt();
    }

    /**
     * 用盘点把库存精确改成 qty。
     *
     * <p>走真实业务路径而不是直接 UPDATE：盘点会写调账流水、会刷新 Redis 闸门，
     * 这样"置数"本身就顺带验证了补货回写链路。</p>
     */
    protected void setStock(String barcode, int qty, String token) {
        String taskNo = post("/api/v1/stocktakes", java.util.Map.of("warehouseId", 1), token)
                .data().path("taskNo").asText();
        Api scan = post("/api/v1/stocktakes/" + taskNo + "/scan",
                java.util.Map.of("barcode", barcode, "qty", qty), token);
        if (!scan.ok()) {
            throw new IllegalStateException("盘点扫码失败：" + scan.body());
        }
        Api fin = post("/api/v1/stocktakes/" + taskNo + "/finish", null, token);
        if (!fin.ok()) {
            throw new IllegalStateException("盘点完成失败：" + fin.body());
        }
        int after = stockOf(barcode, token);
        if (after != qty) {
            throw new IllegalStateException("盘点置数失败：期望 " + qty + "，实际 " + after);
        }
    }

    protected String openOrder(String token) {
        Api r = post("/api/v1/sales/orders", java.util.Map.of("warehouseId", 1), token);
        if (!r.ok()) {
            throw new IllegalStateException("开单失败：" + r.body());
        }
        return r.data().path("orderNo").asText();
    }

    protected Api addItem(String orderNo, String barcode, int qty, String scanToken, String token) {
        return post("/api/v1/sales/orders/" + orderNo + "/items",
                java.util.Map.of("barcode", barcode, "qty", qty, "scanToken", scanToken), token);
    }
}
