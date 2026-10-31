package com.yilian.clothing.interfaces;

import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.infrastructure.support.TraceId;

/**
 * 统一响应体。两套实现（Java/Python）返回的 JSON 字段名必须逐字一致，
 * 这样同一份前端代码可以对接任一后端（见 Requirement_Trace_Matrix「契约总表」）。
 *
 * <p>用 record 而不是 Lombok @Data：record 的 equals/hashCode/toString 由编译器生成且不可变，
 * 响应对象本来就不该被下游修改。</p>
 *
 * @param code    业务码，0 表示成功；非 0 见 {@link ResultCode}
 * @param msg     给人看的提示，前端可直接弹
 * @param data    业务数据，失败时为 null
 * @param traceId 链路 ID，报错时用户截图给我们，就能在日志里 grep 出整条调用
 */
public record Result<T>(int code, String msg, T data, String traceId) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data, TraceId.current());
    }

    public static Result<Void> ok() {
        return new Result<>(0, "ok", null, TraceId.current());
    }

    public static <T> Result<T> fail(ResultCode rc) {
        return new Result<>(rc.code(), rc.msg(), null, TraceId.current());
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null, TraceId.current());
    }
}
