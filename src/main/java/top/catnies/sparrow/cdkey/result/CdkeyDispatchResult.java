package top.catnies.sparrow.cdkey.result;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 将派发动作的结果交还等待中的兑换事务, 供核销和历史快照使用.
 *
 * @param status SUCCESS 表示已尝试派发, 其他状态表示派发前拒绝
 * @param redeemedAt 派发起始时刻, UTC epoch 毫秒, 拒绝时为 0
 * @param resolvedCommands 实际尝试派发的命令, 按顺序保存, 拒绝时为空
 */
public record CdkeyDispatchResult(@NotNull CdkeyRedeemResult.Status status, long redeemedAt, @NotNull List<String> resolvedCommands) {

    /**
     * 固定实际尝试派发的命令列表, 供后台事务写入.
     *
     * @param status 派发结果或业务拒绝
     * @param redeemedAt 派发起始时刻, UTC epoch 毫秒
     * @param resolvedCommands 按顺序尝试派发的命令
     */
    public CdkeyDispatchResult {
        resolvedCommands = List.copyOf(resolvedCommands);
    }
}