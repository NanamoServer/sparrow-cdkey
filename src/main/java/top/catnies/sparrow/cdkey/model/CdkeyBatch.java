package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;

/**
 * 记录一次礼品码生成操作, 批次编号参与每个生成码的唯一编码.
 *
 * @param id 数据库分配且不复用的批次编号
 * @param definitionId 所属礼品的内部编号
 * @param quantity 该批生成数量, 范围为 1 至 16
 * @param creator 生成管理员的身份快照
 * @param createdAt 生成时刻, UTC epoch 毫秒
 */
public record CdkeyBatch(long id, long definitionId, int quantity, @NotNull CdkeyActor creator, long createdAt) {
}