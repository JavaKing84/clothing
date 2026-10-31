package com.yilian.clothing.infrastructure.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 负库存告警消费端（Q47 强制出库后的兜底）。
 *
 * <p>强制出库是允许的（货在店里但没入库），但**店主必须知道**。
 * 这个消费者目前只落日志，预留了推微信模板消息的位置：
 * 真正接公众号时，只在这里加一次 HTTP 调用，业务代码一行不动。</p>
 */
@Component
public class StockAlertListener {

    private static final Logger log = LoggerFactory.getLogger(StockAlertListener.class);

    private final ObjectMapper objectMapper;

    public StockAlertListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_NOTIFY)
    public void onMessage(org.springframework.amqp.core.Message message) {
        try {
            JsonNode node = PayloadReader.read(objectMapper, message.getBody());
            String type = node.path("type").asText();
            if ("STOCK_NEGATIVE".equals(type)) {
                log.warn("[负库存告警] 货号={} 尺码={} 颜色={} 余量={} 操作人={} 原因={}",
                        node.path("spuNo").asText(), node.path("size").asText(), node.path("color").asText(),
                        node.path("afterQty").asInt(), node.path("operator").asText(),
                        node.path("reason").asText());
            }
        } catch (Exception e) {
            // 告警消费者吞异常是有意的：负库存提醒失败，不该把主流程拖下水。
            // 但一定要留日志——"告警发不出去"本身也是需要人知道的事。
            log.warn("库存事件解析失败，已忽略本条: {}", e.getMessage());
        }
    }
}
