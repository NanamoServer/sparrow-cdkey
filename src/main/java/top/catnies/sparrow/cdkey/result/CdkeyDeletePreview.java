package top.catnies.sparrow.cdkey.result;

/**
 * 为删除确认页提供定义版本和关联数量, 确认时仍须重新核对版本.
 * 生成和兑换不会增加配置版本, 实际删除数量以删除结果为准.
 *
 * @param id 定义编号
 * @param version 读取到的配置版本
 * @param batches 所属生成批次数量
 * @param tokens 所属完整码数量, 包含已用码
 * @param redemptions 成功兑换记录数量
 * @param playerUsages 玩家次数统计行数
 * @param unusedTokens 尚未兑换的完整码数量, 已包含在 tokens 内
 */
public record CdkeyDeletePreview(long id, long version, long batches, long tokens, long redemptions, long playerUsages, long unusedTokens) {
}