package com.yilian.clothing.infrastructure.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑。
 *
 * <p>为什么用 topic exchange 而不是 direct：消息类型会增长（销售、库存变化、负库存告警、
 * 盘点完成……），topic 让新增类型只需加一个 binding，不用改生产者的 exchange。</p>
 *
 * <p>为什么队列要 durable + 消息持久化：门店晚上盘点时断电重启，未消费的消息不能丢。
 * 但"不丢"的最终保证不在 MQ，而在 {@code msg_outbox}——MQ 只是投递通道，
 * 落库才是承诺（ADR：事务性发件箱）。</p>
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "yilian.stock.java";
    public static final String QUEUE_REPORT = "q.report.sales";
    public static final String QUEUE_NOTIFY = "q.notify.stock";
    public static final String RK_SALE_PAID = "sale.paid";
    public static final String RK_STOCK_CHANGED = "stock.changed";
    public static final String RK_STOCK_NEGATIVE = "stock.negative";

    @Bean
    public TopicExchange stockExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue reportQueue() {
        // 报表聚合允许慢，但绝不能被一条坏消息堵死 → 配上死信队列
        return QueueBuilder.durable(QUEUE_REPORT)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey("dead.report")
                .build();
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFY).build();
    }

    @Bean
    public Binding reportBinding() {
        return BindingBuilder.bind(reportQueue()).to(stockExchange()).with("sale.*");
    }

    @Bean
    public Binding notifyBinding() {
        return BindingBuilder.bind(notifyQueue()).to(stockExchange()).with("stock.*");
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        // 打开发送确认：broker 没收到时我们能知道，进而让发件箱重试而不是假装成功
        template.setMandatory(true);
        return template;
    }
}
