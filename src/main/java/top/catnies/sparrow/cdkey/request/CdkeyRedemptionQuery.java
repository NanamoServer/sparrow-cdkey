package top.catnies.sparrow.cdkey.request;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 成功兑换记录的组合筛选, 可空字段不参与过滤.
 *
 * @param definitionId 定义内部编号, 删除后同名重建不会复用
 * @param playerUuid 兑换者 UUID, 不依赖历史名字是否变化
 */
public record CdkeyRedemptionQuery(@Nullable Long definitionId, @Nullable UUID playerUuid) {
}