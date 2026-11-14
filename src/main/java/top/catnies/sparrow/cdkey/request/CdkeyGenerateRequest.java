package top.catnies.sparrow.cdkey.request;

import top.catnies.sparrow.cdkey.model.CdkeyActor;
import org.jetbrains.annotations.NotNull;

/**
 * 为指定礼品生成一批码, 生成前须核对管理员看到的配置版本.
 *
 * @param id 礼品的正编号
 * @param expectedVersion 表单依据的配置版本
 * @param quantity 生成数量, 范围为 1 至 16
 * @param actor 执行生成的管理员身份快照
 * @param now 生成时刻, UTC epoch 毫秒
 */
public record CdkeyGenerateRequest(long id, long expectedVersion, int quantity, @NotNull CdkeyActor actor, long now) {
}