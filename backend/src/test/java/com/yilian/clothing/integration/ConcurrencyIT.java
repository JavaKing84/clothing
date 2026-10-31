package com.yilian.clothing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发一致性（Q76 / DoD：两人同时扫码不覆盖、并发不超卖）。
 *
 * <p>这类缺陷在单线程里永远看不到：两条 UPDATE 同时读到旧值，
 * {@code set qty = 读到的值 + 1} 就会丢掉一次。唯一可信的验证方式，
 * 是真的让 30 个线程同时打进去，然后数库存。</p>
 *
 * <p>三层防御各测一遍：
 * ① 行锁 + 原子算术；② 幂等令牌；③ 不超卖（闸门 + DB 兜底）。</p>
 */
class ConcurrencyIT extends IntegrationTestSupport {

    private static final int THREADS = 24;

    /** 用 CountDownLatch 把线程卡在同一起跑线，否则前面的请求早就执行完，测不出并发。 */
    private List<Api> race(int count, java.util.function.IntFunction<Api> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Api>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int idx = i;
                tasks.add(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return action.apply(idx);
                });
            }
            List<Future<Api>> futures = new ArrayList<>();
            for (Callable<Api> t : tasks) {
                futures.add(pool.submit(t));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<Api> results = new ArrayList<>();
            for (Future<Api> f : futures) {
                results.add(f.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("24 人同时扫同一 SKU 各 1 件，库存精确减 24，没有一次丢失（Q76）")
    void noLostUpdate() throws Exception {
        String token = login("13800000000");
        int start = 80;
        setStock(BARCODE, start, token);
        String orderNo = openOrder(token);

        List<Api> results = race(THREADS, i -> addItem(orderNo, BARCODE, 1, UUID.randomUUID().toString(), token));

        long ok = results.stream().filter(Api::ok).count();
        assertThat(ok).as("并发加购全部成功").isEqualTo(THREADS);
        assertThat(stockOf(BARCODE, token)).as("库存必须精确减少 %d 件", THREADS).isEqualTo(start - THREADS);

        // 同款同码只占一行（Q50），数量累加而不是新增行
        JsonNode items = get("/api/v1/sales/orders/" + orderNo, token).data().path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("qty").asInt()).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("同一 scanToken 被 12 个线程重放，只扣 1 件，其余拿 duplicated=true")
    void sameScanTokenUnderConcurrency() throws Exception {
        String token = login("13800000000");
        int start = 30;
        setStock(BARCODE, start, token);
        String orderNo = openOrder(token);
        String scanToken = UUID.randomUUID().toString();

        List<Api> results = race(12, i -> addItem(orderNo, BARCODE, 1, scanToken, token));

        long accepted = results.stream()
                .filter(r -> r.ok() && !r.data().path("duplicated").asBoolean(false))
                .count();
        long duplicated = results.stream()
                .filter(r -> r.data().path("duplicated").asBoolean(false))
                .count();

        assertThat(accepted).as("同一令牌只能被接受一次").isEqualTo(1);
        assertThat(duplicated).as("其余请求必须回放首次结果").isEqualTo(11);
        assertThat(stockOf(BARCODE, token)).isEqualTo(start - 1);

        JsonNode items = get("/api/v1/sales/orders/" + orderNo, token).data().path("items");
        assertThat(items.get(0).path("qty").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("库存 5 件、30 人同时抢：恰好 5 人成功、25 人 409，库存不会卖成负数")
    void noOversell() throws Exception {
        String token = login("13800000000");
        int start = 5;
        setStock(BARCODE, start, token);
        String orderNo = openOrder(token);

        AtomicInteger okCount = new AtomicInteger();
        List<Api> results = race(30, i -> addItem(orderNo, BARCODE, 1, UUID.randomUUID().toString(), token));

        results.stream().filter(Api::ok).forEach(r -> okCount.incrementAndGet());
        long shortage = results.stream().filter(r -> r.code() == 1001).count();

        assertThat(okCount.get()).as("成功件数必须等于库存").isEqualTo(start);
        assertThat(shortage).as("其余必须明确告知库存不足").isEqualTo(30 - start);
        assertThat(stockOf(BARCODE, token)).as("库存不能被卖穿").isZero();
    }

    @Test
    @DisplayName("补货后闸门回写：卖空再入库 3 件，立刻卖得出去（闸门陈旧值回归）")
    void gateWrittenBackAfterReplenish() {
        String token = login("13800000000");
        setStock(BARCODE, 1, token);
        assertThat(addItem(openOrder(token), BARCODE, 1, UUID.randomUUID().toString(), token).ok()).isTrue();
        assertThat(stockOf(BARCODE, token)).isZero();

        // 入库 3 件（真实入库单）
        String inboundNo = post("/api/v1/inbound/orders",
                java.util.Map.of("warehouseId", 1, "supplierId", 1, "remark", "并发测试补货"), token)
                .data().path("orderNo").asText();
        post("/api/v1/inbound/orders/" + inboundNo + "/items",
                java.util.Map.of("barcode", BARCODE, "qty", 3), token);
        post("/api/v1/inbound/orders/" + inboundNo + "/submit", null, token);

        Api r = addItem(openOrder(token), BARCODE, 1, UUID.randomUUID().toString(), token);
        assertThat(r.ok()).as("补货后仍误判缺货，说明闸门没回写：%s", r.body()).isTrue();
        assertThat(stockOf(BARCODE, token)).isEqualTo(2);
    }
}
