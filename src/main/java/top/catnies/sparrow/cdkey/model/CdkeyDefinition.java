package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;

/**
 * 描述数据库中的礼包码或礼品, 内部编号在整个定义生命周期内保持不变.
 * 配置版本随编辑递增, 已用次数独立累计.
 *
 * @param id 数据库分配的正编号
 * @param kind 礼包码或礼品
 * @param identifier 规范化后的礼包码文本或礼品 ID
 * @param rules 当前兑换规则
 * @param usedCount 已核销总次数
 * @param version 从 1 开始的配置版本
 * @param creator 创建管理员的身份快照
 * @param createdAt 创建时刻, UTC epoch 毫秒
 * @param updatedAt 最近编辑时刻, UTC epoch 毫秒
 */
public record CdkeyDefinition(
        long id,
        @NotNull CdkeyKind kind,
        @NotNull String identifier,
        @NotNull CdkeyRules rules,
        long usedCount,
        long version,
        @NotNull CdkeyActor creator,
        long createdAt,
        long updatedAt
) {
}