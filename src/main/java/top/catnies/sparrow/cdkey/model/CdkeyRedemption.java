package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 记录已经派发命令并写入数据库的一次兑换, 用于按定义或玩家 UUID 追溯.
 *
 * @param id 兑换记录的数据库编号
 * @param definitionId 礼包码或礼品的内部编号
 * @param tokenCode 完整 CDKEY, 人工礼包码兑换时为 null
 * @param playerUuid 兑换玩家 UUID
 * @param playerName 兑换时的玩家名称
 * @param redeemedAt 命令派发起始时刻, UTC epoch 毫秒
 * @param snapshot 当时的命令与规则
 */
public record CdkeyRedemption(
        long id,
        long definitionId,
        @Nullable String tokenCode,
        @NotNull UUID playerUuid,
        @NotNull String playerName,
        long redeemedAt,
        @NotNull CdkeySnapshot snapshot
) {
}