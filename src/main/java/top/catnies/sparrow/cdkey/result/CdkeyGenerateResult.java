package top.catnies.sparrow.cdkey.result;

import top.catnies.sparrow.cdkey.model.CdkeyBatch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 返回整批提交后的生成结果, 只有成功结果携带可分发的码.
 *
 * @param status 生成结果
 * @param batch 成功时的批次, 拒绝时为 null
 * @param codes 按批内序号排列的完整码, 拒绝时为空列表
 */
public record CdkeyGenerateResult(@NotNull Status status, @Nullable CdkeyBatch batch, @NotNull List<String> codes) {

    /**
     * 保存不可变码列表, 供多个管理页面读取同一批结果.
     *
     * @param status 生成结果
     * @param batch 成功时的批次
     * @param codes 完整码列表
     */
    public CdkeyGenerateResult {
        codes = List.copyOf(codes);
    }

    /**
     * 区分已经提交的批次和生成前的业务拒绝.
     */
    public enum Status {
        SUCCESS,          // 整批码已经提交
        NOT_FOUND,        // 定义已经不存在
        VERSION_CONFLICT, // 配置已经被其他编辑更新
        NOT_GIFT          // 人工礼包码不能生成 CDKEY
    }
}