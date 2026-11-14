package top.catnies.sparrow.cdkey.request;

import top.catnies.sparrow.cdkey.model.CdkeyActor;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRules;
import org.jetbrains.annotations.NotNull;

/**
 * 将已完成业务校验的创建输入交给存储, 数据库分配内部编号.
 *
 * @param kind 礼包码或礼品
 * @param identifier 已规范化的标识, <strong>调用方须完成长度与字符校验</strong>
 * @param rules 符合对应类型限制的规则
 * @param actor 创建管理员的身份快照
 * @param now 创建时刻, UTC epoch 毫秒
 */
public record CdkeyCreateRequest(@NotNull CdkeyKind kind, @NotNull String identifier, @NotNull CdkeyRules rules, @NotNull CdkeyActor actor, long now) {
}