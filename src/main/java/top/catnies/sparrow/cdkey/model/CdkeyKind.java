package top.catnies.sparrow.cdkey.model;

import org.jetbrains.annotations.NotNull;

/**
 * 区分手工填写的礼包码和用于批量生成一次性码的礼品.
 * 类型在创建时确定, 同一个定义的兑换次数始终按该类型的规则累计.
 */
public enum CdkeyKind {
    CODE("code"), // 同一玩家固定只能兑换一次的人工礼包码
    GIFT("gift"); // 按礼品累计个人次数的一次性 CDKEY 来源

    private final String key; // 数据库和历史快照中的类型标识

    /**
     * 绑定类型的持久化标识.
     *
     * @param key 固定的小写标识
     */
    CdkeyKind(@NotNull String key) {
        this.key = key;
    }

    /**
     * 返回数据库和快照共用的类型标识.
     *
     * @return 固定的小写标识
     */
    @NotNull
    public String key() {
        return this.key;
    }

    /**
     * 将持久化标识还原为业务类型.
     *
     * @param key 数据库或快照中的标识
     * @return 对应的业务类型
     * @throws IllegalArgumentException 标识不属于支持的类型时抛出
     */
    @NotNull
    public static CdkeyKind fromKey(@NotNull String key) {
        return switch (key) {
            case "code" -> CODE;
            case "gift" -> GIFT;
            default -> throw new IllegalArgumentException("Unknown CDKEY kind: " + key);
        };
    }
}