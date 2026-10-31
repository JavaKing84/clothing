package com.yilian.clothing.application.service;

import com.yilian.clothing.domain.repository.SupportRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 业务单号生成器。
 *
 * <p>格式：{@code 前缀 + yyyyMMdd + 6 位日序号}，例如 {@code SO20261029-000012}。
 * 为什么不用 UUID：店员要在电话里念单号、要在打印的小票上找单号、要在纸质进货单上手写单号。
 * UUID 在这三个场景里都是灾难。</p>
 *
 * <p>为什么序号按日归零：一年后单号仍然是 6 位，不会长到念不出来；
 * 且"今天第 12 单"本身就是店主能理解的信息。</p>
 *
 * <p>注意：序号是在**事务内**从 sys_seq 取的，事务回滚会浪费一个号（跳号）。
 * 这是有意接受的代价——为了保证"不重号"，宁可"有断号"。财务上断号可解释，重号不可解释。</p>
 */
@Component
public class BizNoGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final SupportRepository supportRepository;

    public BizNoGenerator(SupportRepository supportRepository) {
        this.supportRepository = supportRepository;
    }

    public String next(String prefix, String biz) {
        long seq = supportRepository.nextSeq(biz);
        return prefix + LocalDate.now().format(DAY) + "-" + String.format("%06d", seq);
    }

    public String flowNo() {
        return next("FL", "FLOW");
    }

    public String inboundNo() {
        return next("IN", "IN");
    }

    public String saleNo() {
        return next("SO", "SO");
    }

    public String returnNo() {
        return next("RT", "RT");
    }

    public String transferNo() {
        return next("TF", "TF");
    }

    public String stocktakeNo() {
        return next("ST", "ST");
    }

    public String lossNo() {
        return next("LS", "LS");
    }
}
