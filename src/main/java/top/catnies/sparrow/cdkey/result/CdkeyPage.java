package top.catnies.sparrow.cdkey.result;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 将同一次存储任务读取的匹配总数和当前页交给管理页面.
 *
 * @param page 从 1 开始的页码
 * @param pageSize 每页数量
 * @param total 计数查询时的匹配数量
 * @param entries 本页数据, 超出当前数据范围时为空
 * @param <T> 列表条目类型
 */
public record CdkeyPage<T>(int page, int pageSize, long total, @NotNull List<T> entries) {

    /**
     * 保存不可变分页内容, 与查询返回的可变列表分离.
     *
     * @param page 从 1 开始的页码
     * @param pageSize 每页数量
     * @param total 匹配总数
     * @param entries 当前页条目
     */
    public CdkeyPage {
        entries = List.copyOf(entries);
    }
}