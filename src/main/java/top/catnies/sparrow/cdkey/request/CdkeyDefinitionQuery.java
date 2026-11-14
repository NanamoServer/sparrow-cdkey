package top.catnies.sparrow.cdkey.request;

import top.catnies.sparrow.cdkey.model.CdkeyKind;
import org.jetbrains.annotations.Nullable;

/**
 * 指定定义列表的类型与时间筛选, 多次分页查询可共用同一个时间基准.
 *
 * @param kind 指定类型, null 表示两种类型
 * @param state 指定时间状态, null 表示全部状态
 * @param now 判断起止边界使用的 UTC epoch 毫秒
 */
public record CdkeyDefinitionQuery(@Nullable CdkeyKind kind, @Nullable TimeState state, long now) {

    /**
     * 按开始包含和结束不包含的规则划分时间状态, 次数耗尽独立展示.
     */
    public enum TimeState {
        UPCOMING, // 尚未到达开始时刻
        ACTIVE,   // 已开始且尚未到达有限的结束时刻
        EXPIRED   // 已到达有限的结束时刻
    }
}