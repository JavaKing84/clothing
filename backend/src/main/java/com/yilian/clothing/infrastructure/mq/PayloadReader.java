package com.yilian.clothing.infrastructure.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一把 MQ 消息体读成 {@link JsonNode}。
 *
 * <h3>为什么需要这一层</h3>
 * <p>踩过两个连环坑，都跟"消息体到底是什么类型"有关：</p>
 * <ol>
 *   <li>发布端把 JSON 文本当 String 发，转换器会给消息打上 {@code __TypeId__=java.lang.String}，
 *       消息体其实是**带引号的 JSON 字符串**；</li>
 *   <li>另一个实现（或别的服务）直接发 JSON 对象，消息体是**裸对象**。</li>
 * </ol>
 * <p>消费端若把形参声明成 {@code String}，遇到对象就抛
 * {@code Cannot deserialize String from Object}；声明成 {@code JsonNode}，
 * 遇到字符串又抛 {@code Cannot convert from String to JsonNode}——
 * 两种写法各错一半，而且都只在"真的有单成交"时才暴露。</p>
 *
 * <p>所以这里放弃让转换器猜类型：拿原始 {@code byte[]} 自己解析，
 * 文本节点再解一层。代价是几行代码，换来的是"不管谁发什么形状都能接住"。</p>
 */
final class PayloadReader {

    private PayloadReader() {
    }

    static JsonNode read(ObjectMapper mapper, byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            // 发布端把 JSON 当字符串发时，body 是 "\"{...}\""，要再解一层
            if (root != null && root.isTextual()) {
                return mapper.readTree(root.asText());
            }
            return root;
        } catch (Exception e) {
            throw new IllegalArgumentException("消息体不是合法 JSON，无法消费", e);
        }
    }
}
