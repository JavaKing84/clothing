package com.yilian.clothing.infrastructure.mq;

import com.yilian.clothing.domain.model.OutboxMessage;
import com.yilian.clothing.domain.repository.SupportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事务性发件箱投递器（Transactional Outbox）。
 *
 * <h3>它解决的是"双写不一致"</h3>
 * <p>结账时要做两件事：写数据库、发 MQ。如果先写库再发 MQ，发 MQ 失败 → 库里有单、报表永远少一笔；
 * 如果先发 MQ 再写库，写库失败 → 报表多一笔、实际没卖。
 * 这两个窗口无法用"重试"消除，因为失败点不可控。</p>
 *
 * <p>解法：把"要发什么消息"和业务数据写在**同一个本地事务**里（msg_outbox 表），
 * 再由这个调度器把消息搬到 MQ。事务原子性保证"业务成功 ⇔ 消息待发"，
 * 投递失败就重试，最终一致。</p>
 *
 * <h3>关键细节</h3>
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED}：多实例部署时各拿各的，不互相阻塞；</li>
 *   <li>抢占后置为 status=8（发送中），避免同一行被两个实例同时发；</li>
 *   <li>指数退避 + 最多 5 次 → 死信，绝不无限重试（一条坏消息能堵死整个队列）；</li>
 *   <li>消费端必须幂等：MQ 只保证"至少一次"，重复投递是常态不是异常。</li>
 * </ul>
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final SupportRepository supportRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxDispatcher(SupportRepository supportRepository, RabbitTemplate rabbitTemplate) {
        this.supportRepository = supportRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 3000)
    @Transactional
    public void dispatch() {
        List<OutboxMessage> batch = supportRepository.claimOutbox(20);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxMessage msg : batch) {
            try {
                rabbitTemplate.convertAndSend(msg.exchange(), msg.routingKey(), msg.payload());
                supportRepository.markOutboxSent(msg.id());
            } catch (Exception e) {
                int retry = msg.retryCount() + 1;
                // 指数退避：1s, 2s, 4s, 8s, 16s。固定间隔重试在 broker 抖动时会打满 CPU
                long delaySeconds = Math.min(60, 1L << Math.min(retry, 5));
                supportRepository.markOutboxFailed(msg.id(), retry,
                        LocalDateTime.now().plusSeconds(delaySeconds));
                log.warn("outbox 投递失败 id={} biz={} retry={} err={}", msg.id(), msg.bizNo(), retry,
                        e.getMessage());
            }
        }
        log.debug("outbox 本轮投递 {} 条", batch.size());
    }
}
