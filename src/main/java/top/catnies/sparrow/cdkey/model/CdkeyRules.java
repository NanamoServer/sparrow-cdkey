package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 保存一次编辑后的兑换规则, 命令模板在实际兑换时使用目标玩家解析.
 *
 * @param commands 按派发顺序排列的控制台命令模板
 * @param startsAt 包含的开始时刻, UTC epoch 毫秒
 * @param endsAt 不包含的结束时刻, UTC epoch 毫秒, -1 表示不限期
 * @param totalLimit 总兑换上限, -1 表示无限, 礼品固定为 -1
 * @param playerLimit 每位玩家的兑换上限, -1 表示无限, 礼包码固定为 1
 * @param permission 额外权限节点, 空字符串表示无额外权限
 */
public record CdkeyRules(
        @NotNull List<String> commands,
        long startsAt,
        long endsAt,
        long totalLimit,
        long playerLimit,
        @NotNull String permission
) {

    /**
     * 固定命令模板的顺序和内容, 供定义和历史快照共享.
     *
     * @param commands 按派发顺序排列的控制台命令模板
     * @param startsAt 包含的开始时刻, UTC epoch 毫秒
     * @param endsAt 不包含的结束时刻, UTC epoch 毫秒, -1 表示不限期
     * @param totalLimit 总兑换上限, -1 表示无限
     * @param playerLimit 每位玩家的兑换上限, -1 表示无限
     * @param permission 额外权限节点, 空字符串表示无额外权限
     */
    public CdkeyRules {
        commands = List.copyOf(commands);
    }
}