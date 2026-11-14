package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 表示一个可交付给玩家的一次性 CDKEY, 是否处于有效期由所属礼品的当前规则决定.
 *
 * @param batchId 所属生成批次编号
 * @param ordinal 批内序号, 从 1 开始
 * @param definitionId 所属礼品的内部编号
 * @param code 可复制的完整大写码
 * @param redeemedAt 核销时刻, UTC epoch 毫秒, null 表示未兑换
 */
public record CdkeyToken(long batchId, int ordinal, long definitionId, @NotNull String code, @Nullable Long redeemedAt) {
}