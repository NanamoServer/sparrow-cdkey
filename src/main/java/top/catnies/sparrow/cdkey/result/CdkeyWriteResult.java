package top.catnies.sparrow.cdkey.result;

import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 表达定义写入的业务结果, 数据库故障由异步调用的异常通道传递.
 *
 * @param status 写入结果
 * @param definition 成功时写入的定义, 业务拒绝时为 null
 */
public record CdkeyWriteResult(@NotNull Status status, @Nullable CdkeyDefinition definition) {

    /**
     * 区分完成写入和可向管理员解释的业务拒绝.
     */
    public enum Status {
        SUCCESS, // 定义已经持久化
        DUPLICATE_IDENTIFIER, // 同类定义中已有相同标识
        NOT_FOUND, // 操作对象已经不存在
        VERSION_CONFLICT // 提交表单所依据的版本已过时
    }
}