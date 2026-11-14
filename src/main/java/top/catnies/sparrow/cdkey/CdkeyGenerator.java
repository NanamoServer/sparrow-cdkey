package top.catnies.sparrow.cdkey;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * 组合数据库唯一编号和 80 位随机段, 生成无需查重的可兑换码.
 * 批次编号占高位, 批内序号占低 4 位, 同一数据库分配的不同位置具有不同前缀.
 */
public final class CdkeyGenerator {
    static final String PREFIX = "CDK-"; // 生成码占用的固定前缀
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford Base32 的大写字母表
    private static final SecureRandom RANDOM = new SecureRandom();         // 生成不可预测的随机段

    private CdkeyGenerator() {
    }

    /**
     * 判断输入是否属于自动生成码的命名空间, 前缀中的 ASCII 字母忽略大小写.
     *
     * @param code 已去除首尾空白的兑换码
     * @return 以 CDK- 或其 ASCII 大小写变体开头时为 true
     */
    public static boolean hasPrefix(@NotNull String code) {
        if (code.length() < PREFIX.length()) return false;
        for (int index = 0; index < PREFIX.length(); index++) {
            char character = code.charAt(index);
            if (character >= 'a' && character <= 'z') {
                character -= 'a' - 'A';
            }
            if (character != PREFIX.charAt(index)) return false;
        }
        return true;
    }

    /**
     * 为已经取得数据库编号的批次生成一个完整码.
     *
     * @param batchId 数据库分配的正批次编号
     * @param ordinal 批内序号, 范围为 1 至 16
     * @return CDK- 前缀, 合并编号和 16 位随机段组成的完整码, 总长 22 至 35 个 ASCII 字符
     */
    @NotNull
    public static String generate(long batchId, int ordinal) {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        BigInteger value = new BigInteger(1, bytes);
        char[] random = new char[16];
        // 16 个 Base32 字符完整承载 80 位随机信息, 每个字符使用 5 位.
        for (int index = random.length - 1; index >= 0; index--) {
            random[index] = ALPHABET.charAt(value.intValue() & 31);
            value = value.shiftRight(5);
        }
        // 序号先转为 0..15 再占用低 4 位. BigInteger 保留最大 long 批次编号, 左移不会溢出.
        BigInteger position = BigInteger.valueOf(batchId - 1).shiftLeft(4).add(BigInteger.valueOf(ordinal - 1));
        return PREFIX + encode(position) + "-" + new String(random);
    }

    /**
     * 将非负合并编号编码成无前导零的 Crockford Base32, 零编号编码为 0.
     *
     * @param value 批次与序号合并后的非负编号
     * @return 数值的 Base32 表示
     */
    @NotNull
    private static String encode(BigInteger value) {
        char[] characters = new char[14];
        int start = characters.length;
        do {
            characters[--start] = ALPHABET.charAt(value.intValue() & 31);
            value = value.shiftRight(5);
        } while (value.signum() != 0);
        return new String(characters, start, characters.length - start);
    }
}