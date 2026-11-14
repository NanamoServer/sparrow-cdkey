package top.catnies.sparrow.cdkey;

import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.result.CdkeyDispatchResult;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * 在 SQLite 兑换事务中衔接玩家条件检查和奖励命令派发.
 */
@FunctionalInterface
@ApiStatus.Internal
public interface CdkeyRedemptionAction {

    /**
     * 检查原连接并尝试派发奖励, 后台兑换事务等到派发结束再写入核销记录.
     *
     * @param definition 当前兑换事务读到的定义与规则快照
     * @return 派发结果, <strong>Paper 主线程不得同步等待本次兑换</strong>
     */
    @NotNull
    CompletableFuture<CdkeyDispatchResult> dispatch(@NotNull CdkeyDefinition definition);
}