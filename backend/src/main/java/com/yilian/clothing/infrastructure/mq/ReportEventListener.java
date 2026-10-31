package com.yilian.clothing.infrastructure.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yilian.clothing.domain.repository.SupportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售事件消费端 —— 维护 {@code rpt_daily_sales} 预聚合。
 *
 * <p>为什么报表不直接查流水还要这张表：首页"近 7 天趋势"要扫 7 天全部流水，
 * 而日聚合表只有 7×SKU 行。随着流水累积（一年 3 万行），这个差距会从 3ms 变成 30ms，
 * 手机上就是"点开首页要等一下"。</p>
 *
 * <p>为什么敢用异步聚合：它是**可重建的派生数据**。任何时候都能用
 * {@code INSERT ... SELECT FROM inv_stock_flow} 重算，错了不会丢账。
 * 这也决定了它不能承载"营业额"这种强一致读数——营业额一律实时查流水。</p>
 */
@Component
public class ReportEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReportEventListener.class);

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final SupportRepository supportRepository;

    public ReportEventListener(ObjectMapper objectMapper, JdbcTemplate jdbc,
                              SupportRepository supportRepository) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.supportRepository = supportRepository;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_REPORT)
    @Transactional
    /**
     * 形参声明为 {@link JsonNode} 而不是 String。
     *
     * <p>踩过的坑：发布端用 {@code Jackson2JsonMessageConverter} 序列化，消息体是**JSON 对象**；
     * 消费端若声明 String，转换器会试图把对象读成字符串，直接抛
     * {@code MismatchedInputException: Cannot deserialize String from Object}，
     * 消息被 reject 进死信队列——日报聚合从此静默失效，而且只在有单成交时才暴露。</p>
     *
     * <p>最终方案是拿原始 {@code Message} 的字节自己解析（见 {@link PayloadReader}）：
     * 对象、字符串、甚至别人塞进来的格式都能接住。消费端**不该假设"消息一定是我的发布端发的"**，
     * 这在多人协作、多服务共用一个 broker 的系统里尤其重要。</p>
     */
    public void onMessage(org.springframework.amqp.core.Message message) {
        try {
            JsonNode root = PayloadReader.read(objectMapper, message.getBody());
            String orderNo = root.path("orderNo").asText();
            String statDate = root.path("statDate").asText();

            // 消费幂等：MQ 至少一次投递，重复消息必须被识别，
            // 否则同一天同一单会被累加两次，报表就永远对不上
            if (!supportRepository.tryConsume(orderNo, "SALE_PAID")) {
                log.debug("重复消息，跳过 orderNo={}", orderNo);
                return;
            }
            for (JsonNode item : root.path("items")) {
                jdbc.update("""
                                INSERT INTO rpt_daily_sales (stat_date, spu_id, sku_id, qty, amount, cost_amount)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON DUPLICATE KEY UPDATE qty         = qty + VALUES(qty),
                                                        amount      = amount + VALUES(amount),
                                                        cost_amount = cost_amount + VALUES(cost_amount)
                                """,
                        statDate, item.path("spuId").asLong(), item.path("skuId").asLong(),
                        item.path("qty").asInt(), item.path("amount").decimalValue(),
                        item.path("cost").decimalValue());
            }
            log.info("销售聚合完成 orderNo={} date={}", orderNo, statDate);
        } catch (RuntimeException e) {
            // 抛出 → Spring AMQP 按 retry 配置重投（最多 3 次）→ 仍失败进死信队列
            // 绝不 catch 后静默 return：那等于承认"这笔账我不要了"
            log.error("销售聚合失败 payload={}", new String(message.getBody()), e);
            throw e;
        } catch (Exception e) {
            log.error("销售聚合失败（受检异常）payload={}", new String(message.getBody()), e);
            throw new IllegalStateException("销售聚合失败", e);
        }
    }
}
