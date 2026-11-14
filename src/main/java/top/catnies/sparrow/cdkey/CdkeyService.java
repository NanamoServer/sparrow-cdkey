package top.catnies.sparrow.cdkey;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.catnies.sparrow.cdkey.database.SqliteCdkeyStore;
import top.catnies.sparrow.cdkey.model.CdkeyActor;
import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRedemption;
import top.catnies.sparrow.cdkey.model.CdkeyRules;
import top.catnies.sparrow.cdkey.model.CdkeyToken;
import top.catnies.sparrow.cdkey.request.CdkeyCreateRequest;
import top.catnies.sparrow.cdkey.request.CdkeyDefinitionQuery;
import top.catnies.sparrow.cdkey.request.CdkeyEditRequest;
import top.catnies.sparrow.cdkey.request.CdkeyGenerateRequest;
import top.catnies.sparrow.cdkey.request.CdkeyRedeemRequest;
import top.catnies.sparrow.cdkey.request.CdkeyRedemptionQuery;
import top.catnies.sparrow.cdkey.result.CdkeyDeletePreview;
import top.catnies.sparrow.cdkey.result.CdkeyDeleteResult;
import top.catnies.sparrow.cdkey.result.CdkeyDispatchResult;
import top.catnies.sparrow.cdkey.result.CdkeyGenerateResult;
import top.catnies.sparrow.cdkey.result.CdkeyPage;
import top.catnies.sparrow.cdkey.result.CdkeyRedeemResult;
import top.catnies.sparrow.cdkey.result.CdkeyWriteResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * 受理管理操作和玩家兑换, 在业务入口校验权限, 规则与输入.
 * SQLite 串行处理核销, 控制台奖励命令在 Paper 主线程执行.
 * <strong>管理和兑换入口须由 Paper 主线程发起</strong>, 调用方通过 Future 接收结果.
 */
public final class CdkeyService {
    private static final String MANAGE = "sparrow.command.cdkey.manage"; // 创建, 编辑和生成权限
    private static final String COPY = "sparrow.command.cdkey.copy";     // 获取完整未兑换码的权限
    private static final String DELETE = "sparrow.command.cdkey.delete"; // 删除预览和确认权限
    private static final int COMMAND_LIMIT = 8192; // 多行命令输入允许的 UTF-16 单元数
    private final Set<CompletableFuture<Void>> active = new HashSet<>(); // 在本服务监视器下登记已受理操作
    private final Set<CompletableFuture<CdkeyDispatchResult>> pending = new HashSet<>(); // 尚未开始派发的动作, 停用时由服务结算
    private boolean accepting = true; // 由本服务监视器保护, 停用时关闭入口

    CdkeyService() {
    }

    /**
     * 停止受理并取消尚未开始的派发, 异步等待已受理请求结束.
     *
     * @return 所有本轮请求的收尾通知. 尚未开始的主线程动作已在返回前取消
     */
    @NotNull
    CompletableFuture<Void> stop() {
        List<CompletableFuture<CdkeyDispatchResult>> cancelled;
        CompletableFuture<Void> finished;
        synchronized (this) {
            this.accepting = false;
            cancelled = List.copyOf(this.pending);
            this.pending.clear();
            finished = CompletableFuture.allOf(this.active.toArray(CompletableFuture[]::new));
        }
        // 已取消的动作唤醒各自的数据库事务, 让事务以业务拒绝结束.
        for (CompletableFuture<CdkeyDispatchResult> task : cancelled) {
            task.complete(new CdkeyDispatchResult(CdkeyRedeemResult.Status.UNAVAILABLE, 0, List.of()));
        }
        return finished;
    }

    /**
     * 从 Paper 主线程受理原连接的兑换. 存储线程等待主线程完成奖励派发后提交核销.
     *
     * @param player 提交请求的本服游戏内玩家
     * @param input 人工礼包码或完整 CDKEY
     * @return 提交后的结果或业务拒绝, 派发后的写库失败使 Future 异常完成.
     *         <strong>Paper 主线程不得同步等待此结果</strong>
     */
    @NotNull
    public synchronized CompletableFuture<CdkeyRedeemResult> redeem(@NotNull Player player, @NotNull String input) {
        if (!this.accepting) return CompletableFuture.completedFuture(new CdkeyRedeemResult(CdkeyRedeemResult.Status.UNAVAILABLE, null));
        return this.track(() -> {
            String code = this.normalizeRedeemCode(input);
            if (code == null) return CompletableFuture.completedFuture(new CdkeyRedeemResult(CdkeyRedeemResult.Status.UNKNOWN_CODE, null));
            if (!player.isConnected()) return CompletableFuture.completedFuture(new CdkeyRedeemResult(CdkeyRedeemResult.Status.LEFT, null));
            if (!player.hasPermission(SparrowCdkeyPlugin.REDEEM_PERMISSION)) {
                return CompletableFuture.completedFuture(new CdkeyRedeemResult(CdkeyRedeemResult.Status.NO_PERMISSION, null));
            }
            CdkeyRedeemRequest request = new CdkeyRedeemRequest(code, player.getUniqueId(), player.getName());
            return this.store().redeem(request, definition -> this.dispatch(player, definition));
        });
    }

    /**
     * 将兑换条件检查和奖励派发交给 Paper 主线程的事件循环, 停用时可结算尚未开始的动作.
     *
     * @param player 原连接对应的玩家
     * @param definition 兑换事务读取的定义和规则快照
     * @return 供数据库线程等待的派发结果
     */
    @NotNull
    private CompletableFuture<CdkeyDispatchResult> dispatch(@NotNull Player player, @NotNull CdkeyDefinition definition) {
        CompletableFuture<CdkeyDispatchResult> task = new CompletableFuture<>();
        synchronized (this) {
            if (!this.accepting) return CompletableFuture.completedFuture(new CdkeyDispatchResult(CdkeyRedeemResult.Status.UNAVAILABLE, 0, List.of()));
            this.pending.add(task);
        }
        // Future 同时接收调度失败和主线程动作的异常, 交给等待中的事务回滚.
        CompletableFuture.completedFuture(null).thenCompose(ignored -> CompletableFuture.supplyAsync(
                () -> {
                    synchronized (this) {
                        // 与停用共用取消边界. 已开始的动作由对应事务收尾.
                        if (!this.pending.remove(task)) return new CdkeyDispatchResult(CdkeyRedeemResult.Status.UNAVAILABLE, 0, List.of());
                    }
                    long startedAt = System.currentTimeMillis();
                    CdkeyRedeemResult.Status rejection = this.conditions(player, definition.rules(), startedAt);
                    if (rejection != null) return new CdkeyDispatchResult(rejection, 0, List.of());
                    return this.dispatchCommands(player, definition.rules(), startedAt);
                },
                MinecraftServer.getServer()::scheduleOnMain
        )).whenComplete((result, error) -> {
            synchronized (this) {
                this.pending.remove(task);
            }
            if (error == null) {
                task.complete(result);
            } else {
                task.completeExceptionally(error);
            }
        });
        return task;
    }

    /**
     * 在平台线程按顺序尝试执行控制台命令, 单条外部命令异常仅警告.
     *
     * @param player 用于解析占位符的原请求玩家
     * @param rules 本次兑换的规则快照
     * @param startedAt 最后一次条件检查使用的派发起始毫秒
     * @return 派发时间及实际尝试执行的命令
     */
    @NotNull
    private CdkeyDispatchResult dispatchCommands(@NotNull Player player, @NotNull CdkeyRules rules, long startedAt) {
        List<String> resolved = new ArrayList<>(rules.commands().size());
        // 占位符以纯文本插入, 解析后的实际命令按尝试派发的顺序留在历史中.
        for (int index = 0, size = rules.commands().size(); index < size; index++) {
            try {
                String command = PlainTextComponentSerializer.plainText().serialize(
                        MiniMessage.miniMessage().deserialize(
                                rules.commands().get(index),
                                Placeholder.unparsed("player", player.getName()),
                                Placeholder.unparsed("uuid", player.getUniqueId().toString())
                        )
                );
                resolved.add(command);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException exception) {
                SparrowCdkeyPlugin.instance().getLogger().log(
                        Level.WARNING, "Failed to dispatch CDKEY command " + (index + 1) + " for " + player.getUniqueId(), exception
                );
            }
        }
        return new CdkeyDispatchResult(CdkeyRedeemResult.Status.SUCCESS, startedAt, resolved);
    }

    /**
     * 在派发前用原玩家连接检查有效期和当前权限.
     *
     * @param player 原请求玩家
     * @param rules 本次兑换采用的规则
     * @param now 本次条件判断的 UTC epoch 毫秒
     * @return 拒绝原因, 全部满足时为 null
     */
    @Nullable
    private CdkeyRedeemResult.Status conditions(@NotNull Player player, @NotNull CdkeyRules rules, long now) {
        if (!player.isConnected()) return CdkeyRedeemResult.Status.LEFT;
        if (now < rules.startsAt()) return CdkeyRedeemResult.Status.NOT_STARTED;
        if (rules.endsAt() != -1 && now >= rules.endsAt()) return CdkeyRedeemResult.Status.EXPIRED;
        if (!player.hasPermission(SparrowCdkeyPlugin.REDEEM_PERMISSION)) return CdkeyRedeemResult.Status.NO_PERMISSION;
        if (!rules.permission().isEmpty() && !player.hasPermission(rules.permission())) return CdkeyRedeemResult.Status.NO_PERMISSION;
        return null;
    }

    /**
     * 校验兑换输入, 人工礼包码保留原文, 自动生成码按 ASCII 大写匹配.
     *
     * @param input 原始兑换输入
     * @return 去除首尾空白的兑换码, 长度或字符不合法时为 null
     */
    @Nullable
    private String normalizeRedeemCode(@NotNull String input) {
        String code = input.strip();
        int length = code.codePointCount(0, code.length());
        if (length < 1 || length > 64) return null;
        // 人工码以原文精确匹配, 单行表单的输入排除控制符和无效代理项.
        if (code.codePoints().anyMatch(character -> Character.isISOControl(character)
                || character == 0x2028 || character == 0x2029 || character >= 0xD800 && character <= 0xDFFF)) return null;
        if (!CdkeyGenerator.hasPrefix(code)) return code;
        char[] characters = code.toCharArray();
        for (int index = 0; index < characters.length; index++) {
            if (characters[index] >= 'a' && characters[index] <= 'z') {
                characters[index] -= 'a' - 'A';
            }
        }
        return new String(characters);
    }

    /**
     * 规范化人工礼包码并创建定义, 每位玩家的次数固定为 1.
     *
     * @param actor 发起创建的本服游戏内管理员
     * @param code 去除首尾空白前的人工码
     * @param rules 表单规则, 起止时间已转换为绝对毫秒
     * @return 持久化定义或同名冲突, 无效输入和权限不足由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyWriteResult> createCode(@NotNull Player actor, @NotNull String code, @NotNull CdkeyRules rules) {
        return this.execute(actor, MANAGE, () -> this.store().create(new CdkeyCreateRequest(
                CdkeyKind.CODE,
                this.normalizeCode(code),
                this.normalizeRules(CdkeyKind.CODE, rules),
                this.actor(actor),
                System.currentTimeMillis()
        )));
    }

    /**
     * 创建支持中文的礼品, 礼品标识区分大小写且创建后固定.
     *
     * @param actor 发起创建的本服游戏内管理员
     * @param giftId 去除首尾空白前的礼品标识
     * @param rules 表单规则, 礼品总次数固定为 -1
     * @return 持久化定义或同名冲突, 无效输入和权限不足由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyWriteResult> createGift(@NotNull Player actor, @NotNull String giftId, @NotNull CdkeyRules rules) {
        return this.execute(actor, MANAGE, () -> this.store().create(new CdkeyCreateRequest(
                CdkeyKind.GIFT,
                this.normalizeGiftId(giftId),
                this.normalizeRules(CdkeyKind.GIFT, rules),
                this.actor(actor),
                System.currentTimeMillis()
        )));
    }

    /**
     * 按表单版本更新规则, 新规则作用于后续兑换和旧的未使用 CDKEY.
     *
     * @param actor 发起编辑的本服游戏内管理员
     * @param id 正的定义编号
     * @param expectedVersion 表单依据的配置版本
     * @param rules 待保存的规则, 已用次数和历史保持原值
     * @return 更新后的定义或版本等业务拒绝, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyWriteResult> edit(@NotNull Player actor, long id, long expectedVersion, @NotNull CdkeyRules rules) {
        return this.execute(actor, MANAGE, () -> {
            this.validateTarget(id, expectedVersion);
            // 定义种类创建后固定, 先读取种类规范化规则, 最终版本仍由写事务持锁核对.
            return this.store().find(id).thenCompose(definition -> {
                if (definition == null) return CompletableFuture.completedFuture(new CdkeyWriteResult(CdkeyWriteResult.Status.NOT_FOUND, null));
                if (definition.version() != expectedVersion) return CompletableFuture.completedFuture(new CdkeyWriteResult(CdkeyWriteResult.Status.VERSION_CONFLICT, null));
                CdkeyRules normalized = this.normalizeRules(definition.kind(), rules);
                return this.store().edit(new CdkeyEditRequest(id, expectedVersion, normalized, System.currentTimeMillis()));
            });
        });
    }

    /**
     * 为礼品生成 1 至 16 个码, 返回完整码需要同时具有管理和复制权限.
     *
     * @param actor 发起生成的本服游戏内管理员
     * @param giftId 礼品的正内部编号
     * @param expectedVersion 表单依据的配置版本
     * @param quantity 单批生成数量
     * @return 提交后的整批码或业务拒绝, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyGenerateResult> generate(@NotNull Player actor, long giftId, long expectedVersion, int quantity) {
        return this.execute(actor, MANAGE, () -> {
            this.requirePermission(actor, COPY);
            this.validateTarget(giftId, expectedVersion);
            if (quantity < 1 || quantity > 16) throw new IllegalArgumentException("CDKEY batch quantity must be between 1 and 16");
            return this.store().generate(new CdkeyGenerateRequest(giftId, expectedVersion, quantity, this.actor(actor), System.currentTimeMillis()));
        });
    }

    /**
     * 读取管理详情使用的当前定义和累计次数.
     *
     * @param actor 本服游戏内管理员
     * @param id 正的定义编号
     * @return 当前定义或 null, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<@Nullable CdkeyDefinition> find(@NotNull Player actor, long id) {
        return this.execute(actor, SparrowCdkeyPlugin.VIEW_PERMISSION, () -> {
            this.validateId(id);
            return this.store().find(id);
        });
    }

    /**
     * 按类型和生效时间筛选定义, 返回固定时间基准下的计数和分页.
     *
     * @param actor 本服游戏内管理员
     * @param query 类型与时间状态筛选
     * @param page 从 1 开始且偏移可用 int 表示的页码
     * @return 当前分页, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyPage<CdkeyDefinition>> definitions(@NotNull Player actor, @NotNull CdkeyDefinitionQuery query, int page) {
        return this.execute(actor, SparrowCdkeyPlugin.VIEW_PERMISSION, () -> {
            int size = SparrowCdkeyPlugin.instance().settings().pageSize();
            return this.store().definitions(query, page, this.offset(page, size), size);
        });
    }

    @NotNull
    public CompletableFuture<List<CdkeyActor>> findPlayers(@NotNull Player actor, @NotNull String input) {
        return this.execute(actor, SparrowCdkeyPlugin.VIEW_PERMISSION, () -> {
            String value = input.strip();
            try {
                UUID uuid = UUID.fromString(value);
                return CompletableFuture.completedFuture(List.of(new CdkeyActor(uuid, value)));
            } catch (IllegalArgumentException ignored) {
                // 离线名字只匹配本插件的兑换历史, 不发起联网档案查询.
            }
            Player online = Bukkit.getPlayerExact(value);
            if (online != null) return CompletableFuture.completedFuture(List.of(this.actor(online)));
            return this.store().findPlayers(value);
        });
    }

    /**
     * 按当前配置分页读取成功兑换记录, 支持组合筛选玩家和定义.
     *
     * @param actor 本服游戏内管理员
     * @param query 计数和列表共用的筛选条件
     * @param page 从 1 开始且偏移可用 int 表示的页码
     * @return 当前分页, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyPage<CdkeyRedemption>> redemptions(@NotNull Player actor, @NotNull CdkeyRedemptionQuery query, int page) {
        return this.execute(actor, SparrowCdkeyPlugin.VIEW_PERMISSION, () -> {
            if (query.definitionId() != null) this.validateId(query.definitionId());
            int size = SparrowCdkeyPlugin.instance().settings().pageSize();
            return this.store().redemptions(query, page, this.offset(page, size), size);
        });
    }

    /**
     * 按当前配置分页读取完整未兑换码, 调用者需要查看和复制权限.
     *
     * @param actor 本服游戏内管理员
     * @param definitionId 礼品的正内部编号
     * @param page 从 1 开始且偏移可用 int 表示的页码
     * @return 当前分页, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyPage<CdkeyToken>> tokens(@NotNull Player actor, long definitionId, int page) {
        return this.execute(actor, COPY, () -> {
            this.validateId(definitionId);
            int size = SparrowCdkeyPlugin.instance().settings().pageSize();
            return this.store().tokens(definitionId, page, this.offset(page, size), size);
        });
    }

    /**
     * 将管理页码转成数据库偏移, 使用 long 校验乘积的范围.
     *
     * @param page 从 1 开始的页码
     * @param size 配置中校验通过的分页大小
     * @return 可交给存储的非负偏移
     * @throws IllegalArgumentException 页码小于 1 或偏移超过 int 范围时抛出
     */
    private int offset(int page, int size) {
        long offset = ((long) page - 1) * size;
        if (page < 1 || offset > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid CDKEY page");
        return (int) offset;
    }

    /**
     * 读取单条历史的完整规则快照, 不用当前规则覆盖历史.
     *
     * @param actor 本服游戏内管理员
     * @param id 正的历史编号
     * @return 历史记录, 硬删除后为 null
     */
    @NotNull
    public CompletableFuture<@Nullable CdkeyRedemption> findRedemption(@NotNull Player actor, long id) {
        return this.execute(actor, SparrowCdkeyPlugin.VIEW_PERMISSION, () -> {
            this.validateId(id);
            return this.store().findRedemption(id);
        });
    }

    /**
     * 批量读取列表所需的未用数量, 查看权限即可使用, 不暴露完整码.
     *
     * @param actor 本服游戏内管理员
     * @param definitionIds 当前页正的定义编号
     * @return 定义到未用数量的映射, 缺省键表示零
     */
    @NotNull
    public CompletableFuture<Map<Long, Long>> countUnusedTokens(@NotNull Player actor, @NotNull List<Long> definitionIds) {
        return this.execute(actor, SparrowCdkeyPlugin.VIEW_PERMISSION, () -> {
            List<Long> ids = List.copyOf(definitionIds);
            for (int index = 0, size = ids.size(); index < size; index++) {
                this.validateId(ids.get(index));
            }
            return this.store().countUnusedTokens(ids);
        });
    }

    /**
     * 读取删除确认页的版本及影响数量, 调用者需要查看和删除权限.
     *
     * @param actor 本服游戏内管理员
     * @param id 正的定义编号
     * @return 删除预览或 null, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<@Nullable CdkeyDeletePreview> previewDelete(@NotNull Player actor, long id) {
        return this.execute(actor, DELETE, () -> {
            this.validateId(id);
            return this.store().previewDelete(id);
        });
    }

    /**
     * 根据确认页版本硬删除定义及所有关联业务记录.
     *
     * @param actor 本服游戏内管理员
     * @param id 正的定义编号
     * @param expectedVersion 确认页依据的配置版本
     * @return 实际删除数量或业务拒绝, 校验错误由 Future 异常返回
     */
    @NotNull
    public CompletableFuture<CdkeyDeleteResult> delete(@NotNull Player actor, long id, long expectedVersion) {
        return this.execute(actor, DELETE, () -> {
            this.validateTarget(id, expectedVersion);
            return this.store().delete(id, expectedVersion);
        });
    }

    /**
     * 在当前线程检查权限和输入, 将校验异常与数据库异常归入同一个 Future.
     *
     * @param actor 发起请求的管理员
     * @param permission 本次操作的权限节点
     * @param operation 本次请求的业务操作
     * @param <T> 操作结果类型
     * @return 覆盖校验与存储操作的 Future
     */
    @NotNull
    private <T> CompletableFuture<T> execute(@NotNull Player actor, @NotNull String permission, @NotNull Supplier<CompletableFuture<T>> operation) {
        return this.track(() -> {
            this.requirePermission(actor, SparrowCdkeyPlugin.VIEW_PERMISSION);
            if (!SparrowCdkeyPlugin.VIEW_PERMISSION.equals(permission)) this.requirePermission(actor, permission);
            return operation.get();
        });
    }

    /**
     * 登记请求直至其完整操作结束, 停用只等待收尾通知, 异常仍交给原调用方.
     *
     * @param operation 本次管理或兑换操作
     * @param <T> 操作结果类型
     * @return 与内部操作分离取消状态的 Future
     */
    @NotNull
    private <T> CompletableFuture<T> track(@NotNull Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        synchronized (this) {
            if (!this.accepting) return CompletableFuture.failedFuture(new CancellationException("CDKEY service is disabled"));
            this.active.add(settled);
        }
        CompletableFuture<T> result = CompletableFuture.completedFuture(null).thenCompose(ignored -> operation.get());
        result.whenComplete((value, error) -> {
            synchronized (this) {
                this.active.remove(settled);
                settled.complete(null);
            }
        });
        return result.copy();
    }

    /**
     * 按当前玩家权限拒绝未授权操作, 权限插件的显式否定同样生效.
     *
     * @param actor 发起请求的管理员
     * @param permission 需要的权限节点
     * @throws SecurityException 缺少该权限时抛出
     */
    private void requirePermission(@NotNull Player actor, @NotNull String permission) {
        if (!actor.hasPermission(permission)) throw new SecurityException("Missing permission " + permission);
    }

    /**
     * 在实际操作时获取已初始化的存储, 与插件固定的数据源保持一致.
     *
     * @return 当前插件的 CDKEY 存储
     */
    @NotNull
    private SqliteCdkeyStore store() {
        return SparrowCdkeyPlugin.instance().store();
    }

    /**
     * 固定本次创建或生成的管理员身份和当时的姓名.
     *
     * @param player 发起操作的管理员
     * @return 持久化使用的身份快照
     */
    @NotNull
    private CdkeyActor actor(@NotNull Player player) {
        return new CdkeyActor(player.getUniqueId(), player.getName());
    }

    /**
     * 校验管理操作的编号和表单版本, 二者均为数据库生成的正数.
     *
     * @param id 定义编号
     * @param expectedVersion 表单依据的版本
     * @throws IllegalArgumentException 编号或版本非正时抛出
     */
    private void validateTarget(long id, long expectedVersion) {
        this.validateId(id);
        if (expectedVersion <= 0) throw new IllegalArgumentException("CDKEY version must be positive");
    }

    /**
     * 拒绝无效的管理目标编号.
     *
     * @param id 定义编号
     * @throws IllegalArgumentException 编号非正时抛出
     */
    private void validateId(long id) {
        if (id <= 0) throw new IllegalArgumentException("CDKEY definition ID must be positive");
    }

    /**
     * 校验人工礼包码, 保留大小写, 中文, 符号和内部空格, CDK- 前缀由生成码使用.
     *
     * @param input 原始人工码
     * @return 去除首尾空白后用于展示和精确匹配的人工码
     * @throws IllegalArgumentException 长度, 字符或前缀不合法时抛出
     */
    @NotNull
    private String normalizeCode(@NotNull String input) {
        String code = this.normalizeRedeemCode(input);
        if (code == null) throw new IllegalArgumentException("Gift code must contain 1 to 64 Unicode characters without control characters");
        if (CdkeyGenerator.hasPrefix(code)) throw new IllegalArgumentException(CdkeyGenerator.PREFIX + " is reserved for generated CDKEYs");
        return code;
    }

    /**
     * 按 Unicode 码点校验礼品标识, 保留中文和内部空格以及原有大小写.
     *
     * @param input 原始礼品标识
     * @return 去除首尾空白的标识
     * @throws IllegalArgumentException 长度或字符不合法时抛出
     */
    @NotNull
    private String normalizeGiftId(@NotNull String input) {
        String identifier = input.strip();
        int length = identifier.codePointCount(0, identifier.length());
        if (length < 1 || length > 64) throw new IllegalArgumentException("Gift ID length must be between 1 and 64 code points");
        if (identifier.codePoints().anyMatch(character -> Character.isISOControl(character) || character >= 0xD800 && character <= 0xDFFF)) {
            throw new IllegalArgumentException("Gift ID contains a control character or an unpaired surrogate");
        }
        return identifier;
    }

    /**
     * 校验时间和额度并规范化命令, 固定对应种类的个人或总次数限制.
     *
     * @param kind 定义种类
     * @param rules 管理表单提交的规则
     * @return 可交给存储的规则
     * @throws IllegalArgumentException 时间, 额度, 权限长度或命令输入不合法时抛出
     */
    @NotNull
    private CdkeyRules normalizeRules(@NotNull CdkeyKind kind, @NotNull CdkeyRules rules) {
        if (rules.endsAt() != -1 && rules.endsAt() <= rules.startsAt()) throw new IllegalArgumentException("End time must be later than start time");
        if (rules.totalLimit() < -1 || rules.playerLimit() < -1) throw new IllegalArgumentException("Usage limits must be -1 or nonnegative");
        String permission = rules.permission().strip();
        if (permission.codePointCount(0, permission.length()) > 256) throw new IllegalArgumentException("Permission node exceeds 256 code points");

        // 输入长度包含换行和空白, 每个列表项可以是一段多行文本.
        long length = Math.max(0, rules.commands().size() - 1);
        for (int index = 0, size = rules.commands().size(); index < size; index++) {
            length += rules.commands().get(index).length();
        }
        if (length > COMMAND_LIMIT) throw new IllegalArgumentException("Command input exceeds 8192 UTF-16 units");
        // 按原顺序展开多行命令, 去掉界面输入中可选的斜杠.
        List<String> commands = new ArrayList<>();
        for (int index = 0, size = rules.commands().size(); index < size; index++) {
            String text = rules.commands().get(index).replace("\r\n", "\n").replace('\r', '\n');
            for (String line : text.split("\n")) {
                String command = line.strip();
                if (command.isEmpty()) continue;
                if (command.startsWith("/")) command = command.substring(1).strip();
                if (command.isEmpty()) throw new IllegalArgumentException("Command cannot consist only of a slash");
                commands.add(command);
            }
        }
        if (commands.isEmpty()) throw new IllegalArgumentException("At least one command is required");
        return new CdkeyRules(
                commands,
                rules.startsAt(),
                rules.endsAt(),
                kind == CdkeyKind.GIFT ? -1 : rules.totalLimit(),
                kind == CdkeyKind.CODE ? 1 : rules.playerLimit(),
                permission
        );
    }
}