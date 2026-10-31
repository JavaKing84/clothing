package com.yilian.clothing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 衣恋时尚女装 · 扫码出入库系统（Spring Boot 3 + Java 21 + DDD 四层）
 *
 * <p>启动顺序（可观测的事实，不是设想）：
 * Flyway 建表/校验 → Hikari 建连 → Rabbit 队列声明 → Scheduled 发件箱轮询开始。</p>
 */
@SpringBootApplication
@EnableScheduling
public class ClothingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClothingApplication.class, args);
    }
}
