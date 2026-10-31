package com.yilian.clothing.interfaces;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.infrastructure.support.TraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常翻译：任何异常都必须变成一个"前端能看懂"的 JSON。
 *
 * <p>三条原则：
 * <ol>
 *   <li>业务异常（BizException）打 WARN，不打 ERROR —— 缺货一天几百次，打 ERROR 会把真事故淹没；</li>
 *   <li>未知异常打 ERROR 并带 traceId，返回给用户的 msg 里**不出现堆栈和 SQL**（信息泄漏）；</li>
 *   <li>HTTP 状态码必须与业务语义一致，否则前端拦截器无法统一处理。</li>
 * </ol></p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("业务异常 {} {} code={} msg={}", request.getMethod(), request.getRequestURI(),
                e.resultCode().code(), e.getMessage());
        Result<Object> body = new Result<>(e.resultCode().code(), e.getMessage(),
                e.payload().isEmpty() ? null : e.payload(), TraceId.current());
        return ResponseEntity.status(e.resultCode().status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleInvalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        Result<Object> body = new Result<>(ResultCode.PARAM_ERROR.code(),
                "参数校验失败：" + fields.entrySet().stream()
                        .map(x -> x.getKey() + " " + x.getValue()).collect(Collectors.joining("；")),
                fields, TraceId.current());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraint(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage).collect(Collectors.joining("；"));
        return ResponseEntity.badRequest()
                .body(new Result<>(ResultCode.PARAM_ERROR.code(), detail, null, TraceId.current()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Object>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(new Result<>(ResultCode.PARAM_ERROR.code(), "请求参数格式不正确", null, TraceId.current()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(new Result<>(ResultCode.NOT_FOUND.code(), "接口不存在", null, TraceId.current()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnknown(Exception e, HttpServletRequest request) {
        // 这里必须打 ERROR 并带完整堆栈：这是"不该发生的事"发生了
        log.error("未处理异常 {} {} traceId={}", request.getMethod(), request.getRequestURI(),
                TraceId.current(), e);
        return ResponseEntity.status(500)
                .body(new Result<>(ResultCode.SYSTEM_ERROR.code(),
                        ResultCode.SYSTEM_ERROR.msg() + "（编号 " + TraceId.current() + "）", null,
                        TraceId.current()));
    }
}
