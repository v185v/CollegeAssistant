package xyz.v185v.collegeassistant.common;

import java.util.List;

/**
 * 通用分页响应模型。
 *
 * @param items 当前页数据
 * @param total 符合条件的总记录数
 * @param page  从 1 开始的当前页码
 * @param size  每页记录数
 * @param pages 总页数
 * @param <T>   数据项类型
 */
public record PageResult<T>(
        List<T> items,
        long total,
        int page,
        int size,
        int pages
) {
}
