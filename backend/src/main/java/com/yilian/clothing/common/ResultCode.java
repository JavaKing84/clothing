package com.yilian.clothing.common;

import org.springframework.http.HttpStatus;

/**
 * 业务码表（共享内核，四层都可引用，不含框架依赖）。
 *
 * <p>为什么业务码和 HTTP 状态码要分开：HTTP 状态码是给中间件看的（网关重试、监控告警），
 * 业务码是给人看的（店员要知道是"库存不足"还是"条码不认识"）。
 * 两者混用会让 409 同时表示"缺货"和"重复提交"，前端只能靠字符串匹配，一改就崩。</p>
 */
public enum ResultCode {

    SUCCESS(0, "ok", HttpStatus.OK),
    PARAM_ERROR(400, "参数校验失败", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(401, "未登录或登录已过期", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "没有该操作权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(404, "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT(409, "操作冲突，请刷新后重试", HttpStatus.CONFLICT),
    RATE_LIMITED(429, "操作太频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),

    /** Q47：库存不足要告诉操作人"仅剩 X 件"，data.remainQty 携带剩余量 */
    STOCK_SHORTAGE(1001, "库存不足", HttpStatus.CONFLICT),
    STOCK_ROW_MISSING(1002, "该尺码颜色没有库存记录", HttpStatus.NOT_FOUND),
    ORDER_STATE_ILLEGAL(1003, "单据状态不允许该操作", HttpStatus.CONFLICT),
    FORCE_REASON_REQUIRED(1004, "强制出库必须填写原因", HttpStatus.BAD_REQUEST),

    TOKEN_MISSING(2001, "缺少扫码幂等令牌", HttpStatus.BAD_REQUEST),
    TOKEN_REPLAY(2002, "该扫码请求已处理", HttpStatus.OK),
    BARCODE_UNKNOWN(2003, "条码无法识别，请手动输入货号", HttpStatus.NOT_FOUND),

    SKU_NOT_FOUND(3001, "货号不存在，请先录入该款式", HttpStatus.NOT_FOUND),
    SPU_NOT_FOUND(3002, "款式不存在", HttpStatus.NOT_FOUND),
    SPU_NO_DUPLICATED(3003, "货号已存在", HttpStatus.CONFLICT),

    LOGIN_FAILED(4001, "手机号或密码错误", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED(4002, "账号已停用，请联系店主", HttpStatus.FORBIDDEN),
    LOGIN_LOCKED(4003, "密码错误次数过多，请 10 分钟后再试", HttpStatus.TOO_MANY_REQUESTS),

    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String msg;
    private final HttpStatus status;

    ResultCode(int code, String msg, HttpStatus status) {
        this.code = code;
        this.msg = msg;
        this.status = status;
    }

    public int code() {
        return code;
    }

    public String msg() {
        return msg;
    }

    public HttpStatus status() {
        return status;
    }
}
