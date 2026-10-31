package com.yilian.clothing.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务异常。继承 RuntimeException 而不是 Exception：
 * 业务失败是"可预期的控制流"（缺货、无权限），不该强迫每层 try-catch，
 * 由全局异常处理器统一翻译成 HTTP 响应。
 *
 * <p>刻意**不**让缺货这类高频业务异常打 ERROR 日志——一天几百次缺货弹窗，
 * 会把真正的事故淹掉。它们打 WARN，且只带关键字段。</p>
 */
public class BizException extends RuntimeException {

    private final ResultCode resultCode;
    private final Map<String, Object> payload = new LinkedHashMap<>();

    public BizException(ResultCode resultCode) {
        super(resultCode.msg());
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String msg) {
        super(msg);
        this.resultCode = resultCode;
    }

    public static BizException of(ResultCode rc) {
        return new BizException(rc);
    }

    public static BizException of(ResultCode rc, String msg) {
        return new BizException(rc, msg);
    }

    /** 附加结构化数据，最终进响应体 data 字段（如 remainQty、orderNo）。 */
    public BizException with(String key, Object value) {
        this.payload.put(key, value);
        return this;
    }

    public ResultCode resultCode() {
        return resultCode;
    }

    public Map<String, Object> payload() {
        return payload;
    }
}
