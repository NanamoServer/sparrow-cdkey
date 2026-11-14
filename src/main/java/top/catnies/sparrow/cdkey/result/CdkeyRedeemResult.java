package top.catnies.sparrow.cdkey.result;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 表达兑换被拒绝或已派发并核销, 命令效果由对应命令负责.
 *
 * @param status 兑换结果
 * @param recordId 成功提交后的历史编号, 业务拒绝时为 null
 */
public record CdkeyRedeemResult(@NotNull Status status, @Nullable Long recordId) {

    /**
     * 业务拒绝均发生在开始派发之前, LEFT 的结果不向新连接发送.
     */
    public enum Status {
        SUCCESS,       // 已尝试派发命令, 核销事务已提交
        UNKNOWN_CODE,  // 未找到当前有效定义或完整码
        NOT_STARTED,   // 尚未到达开始时刻
        EXPIRED,       // 已到达结束时刻
        TOTAL_LIMIT,   // 总次数已用尽
        PLAYER_LIMIT,  // 当前玩家次数已用尽
        ALREADY_USED,  // 此 CDKEY 已经核销
        NO_PERMISSION, // 缺少兑换入口或额外权限
        UNAVAILABLE,   // 插件停止受理兑换
        LEFT           // 原连接已经离开本服游戏
    }
}