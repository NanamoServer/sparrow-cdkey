package top.catnies.sparrow.cdkey;

import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;

/**
 * 保存本轮配置读取后的管理显示参数, 兑换规则的绝对时间独立保存在数据库.
 *
 * @param timeZone 管理员输入和显示时间使用的时区
 * @param pageSize 每页条数, 范围为 1 至 16
 */
public record CdkeyConfig(@NotNull ZoneId timeZone, int pageSize) {
}