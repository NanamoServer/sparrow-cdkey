package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 保存管理员操作发生时的身份, 当时的名字随定义或批次保存.
 *
 * @param uuid 管理员 UUID
 * @param name 操作时的玩家名称
 */
public record CdkeyActor(@NotNull UUID uuid, @NotNull String name) {
}