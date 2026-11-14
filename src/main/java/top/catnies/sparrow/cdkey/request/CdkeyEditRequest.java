package top.catnies.sparrow.cdkey.request;

import top.catnies.sparrow.cdkey.model.CdkeyRules;
import org.jetbrains.annotations.NotNull;

/**
 * 携带表单打开时的版本保存新规则, 标识和已用次数由原定义保留.
 *
 * @param id 定义的正编号
 * @param expectedVersion 表单依据的配置版本
 * @param rules 已校验并符合定义种类限制的新规则
 * @param now 保存时刻, UTC epoch 毫秒
 */
public record CdkeyEditRequest(long id, long expectedVersion, @NotNull CdkeyRules rules, long now) {
}