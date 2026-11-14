package top.catnies.sparrow.cdkey.request;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 固定受理兑换时的码和玩家身份, 供核销和写入兑换历史.
 *
 * @param normalizedCode 去除首尾空白的兑换码, 人工码保留大小写, 自动生成码转为 ASCII 大写
 * @param playerUuid 兑换玩家 UUID
 * @param playerName 受理时的玩家名
 */
public record CdkeyRedeemRequest(@NotNull String normalizedCode, @NotNull UUID playerUuid, @NotNull String playerName) {
}