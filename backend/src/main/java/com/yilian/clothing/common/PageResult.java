package com.yilian.clothing.common;

import java.util.List;

/**
 * 分页结果。total 一定回传：前端要做"第 3 页 / 共 12 页"，
 * 没有总数就只能用"下一页按钮是否可点"来猜，体验很差。
 */
public record PageResult<T>(List<T> records, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return new PageResult<>(records, total, page, size);
    }
}
