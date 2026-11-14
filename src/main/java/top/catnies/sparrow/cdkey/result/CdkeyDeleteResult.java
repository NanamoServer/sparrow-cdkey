package top.catnies.sparrow.cdkey.result;

import org.jetbrains.annotations.NotNull;

/**
 * 返回本次硬删除实际清除的数量, 结果仅供即时反馈.
 *
 * @param status 删除结果
 * @param batches 删除的批次数量, 拒绝时为 0
 * @param tokens 删除的完整码数量, 拒绝时为 0
 * @param playerUsages 删除的个人次数行数, 拒绝时为 0
 * @param redemptions 删除的兑换记录数量, 拒绝时为 0
 */
public record CdkeyDeleteResult(@NotNull Status status, long batches, long tokens, long playerUsages, long redemptions) {

    /**
     * 只有成功结果表示定义及关联业务记录已经全部删除.
     */
    public enum Status {
        SUCCESS,          // 删除事务已经提交
        NOT_FOUND,        // 定义已经不存在
        VERSION_CONFLICT  // 配置版本与确认页不符
    }
}