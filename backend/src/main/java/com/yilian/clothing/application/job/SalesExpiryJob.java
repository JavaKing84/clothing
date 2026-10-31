package com.yilian.clothing.application.job;

import com.yilian.clothing.application.service.SaleAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时未结账的销售单自动取消（ADR-11）。
 *
 * <p>为什么用 {@code @Scheduled} 而不是 Redis 延迟队列：
 * 3-5 人的店，OPEN 状态的单最多几十张，每 10 分钟扫一次全表（有 idx_status_time）耗时 &lt; 5ms。
 * 引入延迟队列要处理"消息丢了单就永远不关"的新问题，收益为负。
 * <b>技术选型要匹配规模，不是匹配简历。</b></p>
 *
 * <p>注意这个类单独存在而不是把 {@code @Scheduled} 加在 SaleAppService 上：
 * 同一个类里自调用不会走 Spring 的事务代理，{@code @Transactional} 会静默失效——
 * 这是 Spring AOP 最经典的坑。</p>
 */
@Component
public class SalesExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SalesExpiryJob.class);

    private final SaleAppService saleAppService;

    @Value("${app.stock.open-order-timeout-minutes:30}")
    private int timeoutMinutes;

    public SalesExpiryJob(SaleAppService saleAppService) {
        this.saleAppService = saleAppService;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void run() {
        int closed = saleAppService.cancelExpired(timeoutMinutes);
        if (closed > 0) {
            log.info("自动取消超时未结账单据 {} 张（阈值 {} 分钟）", closed, timeoutMinutes);
        }
    }
}
