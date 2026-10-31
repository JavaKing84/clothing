package com.yilian.clothing.infrastructure.demo;

import com.yilian.clothing.application.service.BizNoGenerator;
import com.yilian.clothing.application.service.InboundAppService;
import com.yilian.clothing.application.service.SaleAppService;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.Role;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Stock;
import com.yilian.clothing.domain.model.UserAccount;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.SaleRepository;
import com.yilian.clothing.domain.repository.StockRepository;
import com.yilian.clothing.domain.repository.SupportRepository;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 演示数据生成器（ADR-01 的配套）。
 *
 * <p>为什么不直接往库里 INSERT 流水：手写的流水必然与库存算不平
 * （少写一条、符号写反、before/after 对不上），而这套系统的卖点恰恰是"账实一致"。
 * 因此这里**调用真实的 AppService** 来造数据——它同时也是一次回归测试：
 * 如果演示数据造不出来，说明业务链路是坏的。</p>
 *
 * <p>只在 {@code app.demo.enabled=true} 时执行，且库里有流水就跳过（幂等）。</p>
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final SupportRepository supportRepository;
    private final CatalogRepository catalogRepository;
    private final StockRepository stockRepository;
    private final SaleRepository saleRepository;
    private final SaleAppService saleAppService;
    private final InboundAppService inboundAppService;
    private final BizNoGenerator bizNoGenerator;
    private final JdbcTemplate jdbc;

    @Value("${app.demo.enabled:false}")
    private boolean enabled;

    public DemoSeeder(SupportRepository supportRepository, CatalogRepository catalogRepository,
                      StockRepository stockRepository, SaleRepository saleRepository,
                      SaleAppService saleAppService, InboundAppService inboundAppService,
                      BizNoGenerator bizNoGenerator, JdbcTemplate jdbc) {
        this.supportRepository = supportRepository;
        this.catalogRepository = catalogRepository;
        this.stockRepository = stockRepository;
        this.saleRepository = saleRepository;
        this.saleAppService = saleAppService;
        this.inboundAppService = inboundAppService;
        this.bizNoGenerator = bizNoGenerator;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_flow", Long.class);
        if (existing != null && existing > 0) {
            log.info("演示数据已存在（流水 {} 条），跳过生成", existing);
            return;
        }
        UserAccount owner = supportRepository.findUserByPhone("13800000000").orElse(null);
        if (owner == null) {
            log.warn("未找到店主账号，跳过演示数据");
            return;
        }
        Operator operator = new Operator(owner.id(), owner.phone(), owner.nickname(), Role.OWNER,
                owner.shopId(), supportRepository.findPermissions(Role.OWNER.name()));
        AuthContext.set(operator);
        try {
            seedInbound();
            seedSales();
            log.info("演示数据生成完成");
        } catch (Exception e) {
            // 演示数据失败不能阻止服务启动：门店要开门做生意
            log.error("演示数据生成失败（不影响服务启动）", e);
        } finally {
            AuthContext.clear();
        }
    }

    /** 一张真实入库单：2 个款，验证"入库—库存增加—流水"闭环 */
    private void seedInbound() {
        var cmd = new InboundAppService.CreateCmd(1L, 1L, "秋季补货（演示数据）", List.of(
                new InboundAppService.ItemCmd("YL-W26-1002-OT-M", 4, new BigDecimal("96.00")),
                new InboundAppService.ItemCmd("YL-W26-1004-IV-L", 3, new BigDecimal("168.00"))));
        var order = inboundAppService.create(cmd);
        inboundAppService.submit(order.orderNo());
        log.info("演示入库单 {} 已提交", order.orderNo());
    }

    /** 近 3 天若干笔销售：走真实的加购/结账链路，折扣按 9 折 */
    private void seedSales() {
        List<SkuView> pool = new ArrayList<>();
        for (long spuId = 1; spuId <= 6; spuId++) {
            for (SkuView sku : catalogRepository.listSkusBySpu(spuId, true)) {
                int qty = stockRepository.find(sku.id(), 1).map(Stock::quantity).orElse(0);
                if (qty > 1) {
                    pool.add(sku);
                }
            }
        }
        if (pool.isEmpty()) {
            return;
        }
        String[] payTypes = {"CASH", "WECHAT", "ALIPAY"};
        for (int day = 2; day >= 0; day--) {
            int orders = 3 + day;   // 越近的单越多，趋势图才有形状
            for (int i = 0; i < orders; i++) {
                var order = saleAppService.open(1L, "演示数据");
                int items = 1 + (i % 2);
                BigDecimal tag = BigDecimal.ZERO;
                for (int k = 0; k < items; k++) {
                    SkuView sku = pool.get((day * 7 + i * 3 + k) % pool.size());
                    var result = saleAppService.addItem(order.orderNo(), "YL-" + sku.skuCode(), 1,
                            UUID.randomUUID().toString(), false, null);
                    tag = tag.add(sku.tagPrice());
                    if (result.remainQty() <= 0) {
                        break;
                    }
                }
                if (tag.signum() == 0) {
                    saleAppService.cancel(order.orderNo(), "演示数据跳过");
                    continue;
                }
                BigDecimal received = tag.multiply(new BigDecimal("0.90")).setScale(0, RoundingMode.HALF_UP);
                saleAppService.checkout(order.orderNo(), new SaleAppService.CheckoutCmd(
                        received, tag.subtract(received), payTypes[i % payTypes.length],
                        null, null, "演示数据"));
                backdate("inv_stock_flow", "biz_no", order.orderNo(), day);
                backdate("biz_sale_order", "order_no", order.orderNo(), day);
            }
        }
        log.info("演示销售数据：近 3 天，共 {} 笔", 3 + 4 + 5);
    }

    private void backdate(String table, String column, String value, int days) {
        if (days <= 0) {
            return;
        }
        jdbc.update("UPDATE " + table + " SET create_time = create_time - INTERVAL ? DAY WHERE " + column + " = ?",
                days, value);
    }
}
