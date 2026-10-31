package com.yilian.clothing.domain.model;

import java.time.LocalDateTime;

/** 事务性发件箱的一条待投递消息（ADR：DB 事务内写，事务外投递）。 */
public record OutboxMessage(
        Long id,
        String bizNo,
        String msgType,
        String exchange,
        String routingKey,
        String payload,
        int status,
        int retryCount,
        LocalDateTime nextRetryAt
) {
}
