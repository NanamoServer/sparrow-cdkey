package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 保存一次兑换实际采用的规则与命令, 礼品后续编辑时这份历史内容保持不变.
 *
 * @param definitionId 兑换对象的内部编号
 * @param definitionVersion 兑换采用的配置版本
 * @param kind 礼包码或礼品
 * @param identifier 当时的礼包码文本或礼品 ID
 * @param rules 当时的命令模板和兑换条件
 * @param resolvedCommands 使用兑换者上下文解析后的命令, 按派发顺序排列
 */
public record CdkeySnapshot(
        long definitionId,
        long definitionVersion,
        @NotNull CdkeyKind kind,
        @NotNull String identifier,
        @NotNull CdkeyRules rules,
        @NotNull List<String> resolvedCommands
) {

    /**
     * 固定实际派发的命令列表, 使历史记录可以独立读取.
     *
     * @param definitionId 兑换对象的内部编号
     * @param definitionVersion 兑换采用的配置版本
     * @param kind 兑换对象的类型
     * @param identifier 兑换时的礼包码文本或礼品 ID
     * @param rules 兑换时采用的命令模板和条件
     * @param resolvedCommands 解析后的命令, 按派发顺序排列
     */
    public CdkeySnapshot {
        resolvedCommands = List.copyOf(resolvedCommands);
    }
}