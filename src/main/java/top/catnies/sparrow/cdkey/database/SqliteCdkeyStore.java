package top.catnies.sparrow.cdkey.database;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.sqlite.SQLiteDataSource;
import top.catnies.sparrow.cdkey.CdkeyGenerator;
import top.catnies.sparrow.cdkey.CdkeyRedemptionAction;
import top.catnies.sparrow.cdkey.model.CdkeyActor;
import top.catnies.sparrow.cdkey.model.CdkeyBatch;
import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRedemption;
import top.catnies.sparrow.cdkey.model.CdkeyRules;
import top.catnies.sparrow.cdkey.model.CdkeySnapshot;
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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通过独立读连接查询已提交快照, 写连接串行处理本服的兑换数据.
 * 核销事务覆盖查码, 额度检查和奖励派发, 后续事务看到的是整次兑换提交后的状态.
 */
@ApiStatus.Internal
public final class SqliteCdkeyStore implements AutoCloseable {
    private final Connection database; // 写线程独占
    private final Connection reader; // 读线程独占
    private final ExecutorService worker;
    private final ExecutorService readWorker;

    /**
     * 打开 SQLite 文件并准备表结构, 插件开始受理请求后由后台线程接管连接.
     *
     * @param path 插件数据目录内的数据库路径
     * @throws SQLException 无法打开数据库或初始化表结构时抛出
     */
    public SqliteCdkeyStore(@NotNull Path path) throws SQLException {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
        Connection connection = source.getConnection();
        Connection readConnection = null;
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = FULL");
            }
            CdkeySchema.initialize(connection);
            readConnection = source.getConnection();
            try (Statement statement = readConnection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
                statement.execute("PRAGMA synchronous = FULL");
            }
        } catch (SQLException exception) {
            if (readConnection != null) {
                try {
                    readConnection.close();
                } catch (SQLException closeError) {
                    exception.addSuppressed(closeError);
                }
            }
            try {
                connection.close();
            } catch (SQLException closeError) {
                exception.addSuppressed(closeError);
            }
            throw exception;
        }
        this.database = connection;
        this.reader = readConnection;
        this.worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sparrow-cdkey-write").factory());
        this.readWorker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sparrow-cdkey-read").factory());
    }

    /**
     * 创建带数据库编号的定义, 同类标识的唯一约束用于识别重复创建.
     *
     * @param request 已通过校验的定义, 规则及创建者
     * @return 插入后的定义或标识冲突, 存储错误由 Future 传递
     */
    @NotNull
    public CompletableFuture<CdkeyWriteResult> create(@NotNull CdkeyCreateRequest request) {
        return this.submit(() -> {
            CdkeyRules rules = request.rules();
            CdkeyActor actor = request.actor();
            Long id = this.one(this.database, """
                    INSERT INTO cdkey_definitions (
                        kind, identifier, commands_json, starts_at, ends_at, total_limit, player_limit, permission,
                        creator_uuid, creator_name, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (kind, identifier) DO NOTHING
                    RETURNING id
                    """, row -> row.getLong(1), request.kind().key(), request.identifier(), CdkeyJson.writeCommands(rules.commands()),
                    rules.startsAt(), rules.endsAt(), rules.totalLimit(), rules.playerLimit(), rules.permission(),
                    actor.uuid().toString(), actor.name(), request.now(), request.now());
            if (id == null) return new CdkeyWriteResult(CdkeyWriteResult.Status.DUPLICATE_IDENTIFIER, null);
            CdkeyDefinition definition = new CdkeyDefinition(id, request.kind(), request.identifier(), rules, 0, 1, actor, request.now(), request.now());
            return new CdkeyWriteResult(CdkeyWriteResult.Status.SUCCESS, definition);
        });
    }

    /**
     * 按预期版本更新定义. 当前版本一致时, 新规则用于随后兑换的所有码.
     *
     * @param request 定义编号, 期望版本和规范化规则
     * @return 更新后的定义或业务拒绝, 存储错误由 Future 传递
     */
    @NotNull
    public CompletableFuture<CdkeyWriteResult> edit(@NotNull CdkeyEditRequest request) {
        return this.submit(() -> this.transaction(() -> {
            CdkeyDefinition current = this.readDefinition(this.database, request.id());
            if (current == null) return new CdkeyWriteResult(CdkeyWriteResult.Status.NOT_FOUND, null);
            if (current.version() != request.expectedVersion()) return new CdkeyWriteResult(CdkeyWriteResult.Status.VERSION_CONFLICT, null);
            CdkeyRules rules = request.rules();
            // 已用次数独立累计, 仅编辑操作增加定义版本.
            this.update("""
                    UPDATE cdkey_definitions
                    SET commands_json = ?, starts_at = ?, ends_at = ?, total_limit = ?, player_limit = ?,
                        permission = ?, version = version + 1, updated_at = ?
                    WHERE id = ?
                    """, CdkeyJson.writeCommands(rules.commands()), rules.startsAt(), rules.endsAt(), rules.totalLimit(),
                    rules.playerLimit(), rules.permission(), request.now(), request.id());
            CdkeyDefinition updated = new CdkeyDefinition(
                    current.id(), current.kind(), current.identifier(), rules, current.usedCount(),
                    current.version() + 1, current.creator(), current.createdAt(), request.now()
            );
            return new CdkeyWriteResult(CdkeyWriteResult.Status.SUCCESS, updated);
        }));
    }

    /**
     * 在一个事务内保存批次和该批的所有码, 完整码在提交后才可分发.
     *
     * @param request 已校验数量的礼品生成请求
     * @return 已提交的批次或业务拒绝, 存储错误由 Future 传递
     */
    @NotNull
    public CompletableFuture<CdkeyGenerateResult> generate(@NotNull CdkeyGenerateRequest request) {
        return this.submit(() -> this.transaction(() -> {
            CdkeyDefinition definition = this.readDefinition(this.database, request.id());
            if (definition == null) return new CdkeyGenerateResult(CdkeyGenerateResult.Status.NOT_FOUND, null, List.of());
            if (definition.version() != request.expectedVersion()) return new CdkeyGenerateResult(CdkeyGenerateResult.Status.VERSION_CONFLICT, null, List.of());
            if (definition.kind() != CdkeyKind.GIFT) return new CdkeyGenerateResult(CdkeyGenerateResult.Status.NOT_GIFT, null, List.of());
            // SQLite 分配的批次编号和批内序号共同决定生成码的前缀.
            CdkeyActor actor = request.actor();
            long batchId = this.insertId("""
                    INSERT INTO cdkey_batches (definition_id, quantity, creator_uuid, creator_name, created_at)
                    VALUES (?, ?, ?, ?, ?) RETURNING id
                    """, request.id(), request.quantity(), actor.uuid().toString(), actor.name(), request.now());
            List<String> codes = new ArrayList<>(request.quantity());
            try (PreparedStatement insert = this.database.prepareStatement("""
                    INSERT INTO cdkey_keys (batch_id, ordinal, definition_id, code) VALUES (?, ?, ?, ?)
                    """)) {
                insert.setLong(1, batchId);
                insert.setLong(3, request.id());
                for (int ordinal = 1; ordinal <= request.quantity(); ordinal++) {
                    String code = CdkeyGenerator.generate(batchId, ordinal);
                    insert.setInt(2, ordinal);
                    insert.setString(4, code);
                    insert.addBatch();
                    codes.add(code);
                }
                insert.executeBatch();
            }
            return new CdkeyGenerateResult(
                    CdkeyGenerateResult.Status.SUCCESS,
                    new CdkeyBatch(batchId, request.id(), request.quantity(), actor, request.now()),
                    codes
            );
        }));
    }

    /**
     * 核对兑换码和次数, 在平台动作返回后提交核销.
     *
     * @param request 规范化码和本次兑换的玩家身份
     * @param action 检查实际玩家并派发奖励的动作
     * @return 提交后的历史编号或业务拒绝, 写入失败由 Future 传递
     */
    @NotNull
    public CompletableFuture<CdkeyRedeemResult> redeem(@NotNull CdkeyRedeemRequest request, @NotNull CdkeyRedemptionAction action) {
        return this.submit(() -> this.transaction(() -> this.redeemInTransaction(request, action)));
    }

    /**
     * 在同一次事务内检查码, 定义与次数, 然后记录派发所使用的快照.
     *
     * @param request 玩家身份与已规范化的码
     * @param action 交给主线程完成的奖励动作
     * @return 兑换状态及成功时待提交的历史编号
     * @throws SQLException SQLite 查询或写入失败时抛出
     */
    @NotNull
    private CdkeyRedeemResult redeemInTransaction(@NotNull CdkeyRedeemRequest request, @NotNull CdkeyRedemptionAction action) throws SQLException {
        String code = request.normalizedCode();
        CdkeyToken token = null;
        CdkeyDefinition definition;
        // 自动码按完整码定位单码和礼品. 人工礼包码按定义的原文精确匹配.
        if (CdkeyGenerator.hasPrefix(code)) {
            token = this.one(this.database, "SELECT * FROM cdkey_keys WHERE code = ?", this::mapToken, code);
            if (token == null) return new CdkeyRedeemResult(CdkeyRedeemResult.Status.UNKNOWN_CODE, null);
            if (token.redeemedAt() != null) return new CdkeyRedeemResult(CdkeyRedeemResult.Status.ALREADY_USED, null);
            // 外键经由生成批次保证单码所属的礼品存在.
            definition = this.readDefinition(this.database, token.definitionId());
        } else {
            definition = this.one(this.database, "SELECT * FROM cdkey_definitions WHERE kind = 'code' AND identifier = ?", this::mapDefinition, code);
            if (definition == null) return new CdkeyRedeemResult(CdkeyRedeemResult.Status.UNKNOWN_CODE, null);
        }
        long id = definition.id();
        String player = request.playerUuid().toString();
        CdkeyRules rules = definition.rules();
        if (rules.totalLimit() != -1 && definition.usedCount() >= rules.totalLimit()) {
            return new CdkeyRedeemResult(CdkeyRedeemResult.Status.TOTAL_LIMIT, null);
        }
        Long used = this.one(this.database, "SELECT used_count FROM cdkey_player_usage WHERE definition_id = ? AND player_uuid = ?", row -> row.getLong(1), id, player);
        if (rules.playerLimit() != -1 && (used == null ? 0 : used) >= rules.playerLimit()) {
            return new CdkeyRedeemResult(CdkeyRedeemResult.Status.PLAYER_LIMIT, null);
        }
        // 事务留在存储线程. 主线程只检查玩家及派发命令, 派发前的拒绝无需写入核销数据.
        CdkeyDispatchResult dispatched = action.dispatch(definition).join();
        if (dispatched.status() != CdkeyRedeemResult.Status.SUCCESS) return new CdkeyRedeemResult(dispatched.status(), null);
        // 一起提交单码状态, 两类计数和历史, 快照保留本次实际采用的规则.
        if (token != null) {
            this.update("UPDATE cdkey_keys SET redeemed_at = ? WHERE batch_id = ? AND ordinal = ?", dispatched.redeemedAt(), token.batchId(), token.ordinal());
        }
        this.update("UPDATE cdkey_definitions SET used_count = used_count + 1 WHERE id = ?", id);
        this.update("""
                INSERT INTO cdkey_player_usage (definition_id, player_uuid, used_count) VALUES (?, ?, 1)
                ON CONFLICT (definition_id, player_uuid) DO UPDATE SET used_count = used_count + 1
                """, id, player);
        CdkeySnapshot snapshot = new CdkeySnapshot(id, definition.version(), definition.kind(), definition.identifier(), rules, dispatched.resolvedCommands());
        long recordId = this.insertId("""
                INSERT INTO cdkey_redemptions (definition_id, token_code, player_uuid, player_name, redeemed_at, snapshot_json)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, id, token == null ? null : token.code(), player, request.playerName(), dispatched.redeemedAt(), CdkeyJson.writeSnapshot(snapshot));
        return new CdkeyRedeemResult(CdkeyRedeemResult.Status.SUCCESS, recordId);
    }

    /**
     * 读取某定义的版本和受删除影响的行数.
     *
     * @param id 定义编号
     * @return 删除预览, 定义不存在时为 null
     */
    @NotNull
    public CompletableFuture<@Nullable CdkeyDeletePreview> previewDelete(long id) {
        return this.submitRead(() -> this.one(this.reader, """
                SELECT d.id, d.version,
                    (SELECT COUNT(*) FROM cdkey_batches WHERE definition_id = d.id) AS batch_count,
                    (SELECT COUNT(*) FROM cdkey_keys WHERE definition_id = d.id) AS token_count,
                    (SELECT COUNT(*) FROM cdkey_redemptions WHERE definition_id = d.id) AS redemption_count,
                    (SELECT COUNT(*) FROM cdkey_player_usage WHERE definition_id = d.id) AS usage_count,
                    (SELECT COUNT(*) FROM cdkey_keys WHERE definition_id = d.id AND redeemed_at IS NULL) AS unused_count
                FROM cdkey_definitions d WHERE d.id = ?
                """, row -> new CdkeyDeletePreview(
                row.getLong("id"), row.getLong("version"), row.getLong("batch_count"), row.getLong("token_count"),
                row.getLong("redemption_count"), row.getLong("usage_count"), row.getLong("unused_count")
        ), id));
    }

    /**
     * 按确认时的版本删除定义及全部关联记录.
     *
     * @param id 定义编号
     * @param expectedVersion 删除预览使用的定义版本
     * @return 已提交的删除数量或业务拒绝
     */
    @NotNull
    public CompletableFuture<CdkeyDeleteResult> delete(long id, long expectedVersion) {
        return this.submit(() -> this.transaction(() -> {
            Long version = this.one(this.database, "SELECT version FROM cdkey_definitions WHERE id = ?", row -> row.getLong(1), id);
            if (version == null) return new CdkeyDeleteResult(CdkeyDeleteResult.Status.NOT_FOUND, 0, 0, 0, 0);
            if (version != expectedVersion) return new CdkeyDeleteResult(CdkeyDeleteResult.Status.VERSION_CONFLICT, 0, 0, 0, 0);
            // 历史引用完整码, 完整码引用批次, 删除顺序与外键的引用方向一致.
            long redemptions = this.update("DELETE FROM cdkey_redemptions WHERE definition_id = ?", id);
            long usage = this.update("DELETE FROM cdkey_player_usage WHERE definition_id = ?", id);
            long tokens = this.update("DELETE FROM cdkey_keys WHERE definition_id = ?", id);
            long batches = this.update("DELETE FROM cdkey_batches WHERE definition_id = ?", id);
            this.update("DELETE FROM cdkey_definitions WHERE id = ?", id);
            return new CdkeyDeleteResult(CdkeyDeleteResult.Status.SUCCESS, batches, tokens, usage, redemptions);
        }));
    }

    /**
     * 读取某个定义及其当前规则和累计次数.
     *
     * @param id 定义编号
     * @return 当前定义, 不存在时为 null
     */
    @NotNull
    public CompletableFuture<@Nullable CdkeyDefinition> find(long id) {
        return this.submitRead(() -> this.readDefinition(this.reader, id));
    }

    /**
     * 读取定义并使用指定连接解码规则, 可在已有事务内调用.
     *
     * @param connection 当前线程独占的连接
     * @param id 定义编号
     * @return 当前定义, 不存在时为 null
     * @throws SQLException 无法查询或读取字段时抛出
     */
    @Nullable
    private CdkeyDefinition readDefinition(Connection connection, long id) throws SQLException {
        return this.one(connection, "SELECT * FROM cdkey_definitions WHERE id = ?", this::mapDefinition, id);
    }

    /**
     * 按类型和生效时间查询定义, 计数和分页在同一队列任务中读取.
     *
     * @param query 类型, 时间状态和判定时刻
     * @param page 从 1 开始的页码
     * @param offset 从零开始的偏移
     * @param size 本页最大条数
     * @return 匹配总数和按创建时间, 编号倒序排列的定义
     */
    @NotNull
    public CompletableFuture<CdkeyPage<CdkeyDefinition>> definitions(@NotNull CdkeyDefinitionQuery query, int page, int offset, int size) {
        String where = " FROM cdkey_definitions WHERE 1 = 1";
        List<Object> values = new ArrayList<>();
        if (query.kind() != null) {
            where += " AND kind = ?";
            values.add(query.kind().key());
        }
        switch (query.state()) {
            case null -> { }
            case UPCOMING -> {
                where += " AND starts_at > ?";
                values.add(query.now());
            }
            case ACTIVE -> {
                where += " AND starts_at <= ? AND (ends_at = -1 OR ends_at > ?)";
                values.add(query.now());
                values.add(query.now());
            }
            case EXPIRED -> {
                where += " AND ends_at <> -1 AND ends_at <= ?";
                values.add(query.now());
            }
        }
        return this.page(where, " ORDER BY created_at DESC, id DESC", values, page, offset, size, this::mapDefinition);
    }

    /**
     * 按玩家和定义查询成功兑换记录.
     *
     * @param query 可组合的筛选条件
     * @param page 从 1 开始的页码
     * @param offset 从零开始的偏移
     * @param size 本页最大条数
     * @return 匹配总数和按兑换时间, 编号倒序排列的历史
     */
    @NotNull
    public CompletableFuture<CdkeyPage<CdkeyRedemption>> redemptions(@NotNull CdkeyRedemptionQuery query, int page, int offset, int size) {
        String where = " FROM cdkey_redemptions WHERE 1 = 1";
        List<Object> values = new ArrayList<>();
        if (query.definitionId() != null) {
            where += " AND definition_id = ?";
            values.add(query.definitionId());
        }
        if (query.playerUuid() != null) {
            where += " AND player_uuid = ?";
            values.add(query.playerUuid().toString());
        }
        return this.page(where, " ORDER BY redeemed_at DESC, id DESC", values, page, offset, size, this::mapRedemption);
    }

    @NotNull
    public CompletableFuture<List<CdkeyActor>> findPlayers(@NotNull String name) {
        return this.submitRead(() -> {
            List<CdkeyActor> players = new ArrayList<>();
            String sql = "SELECT player_uuid, MAX(player_name) AS player_name FROM cdkey_redemptions"
                    + " WHERE player_name = ? COLLATE NOCASE GROUP BY player_uuid ORDER BY player_uuid";
            try (
                    PreparedStatement statement = this.prepare(this.reader, sql, name);
                    ResultSet rows = statement.executeQuery()
            ) {
                while (rows.next()) {
                    players.add(new CdkeyActor(UUID.fromString(rows.getString("player_uuid")), rows.getString("player_name")));
                }
            }
            return List.copyOf(players);
        });
    }

    /**
     * 读取一次兑换时的玩家, 规则和命令.
     *
     * @param id 兑换历史编号
     * @return 已保存的快照, 不存在时为 null
     */
    @NotNull
    public CompletableFuture<@Nullable CdkeyRedemption> findRedemption(long id) {
        return this.submitRead(() -> this.one(this.reader, "SELECT * FROM cdkey_redemptions WHERE id = ?", this::mapRedemption, id));
    }

    /**
     * 读取一个礼品的未用码, 相同批次内按生成顺序排列.
     *
     * @param definitionId 礼品编号
     * @param page 从 1 开始的页码
     * @param offset 从零开始的偏移
     * @param size 本页最大条数
     * @return 未用总数及本页完整码, 批次按编号倒序
     */
    @NotNull
    public CompletableFuture<CdkeyPage<CdkeyToken>> tokens(long definitionId, int page, int offset, int size) {
        return this.page(" FROM cdkey_keys WHERE definition_id = ? AND redeemed_at IS NULL",
                " ORDER BY batch_id DESC, ordinal", List.of(definitionId), page, offset, size, this::mapToken);
    }

    /**
     * 批量统计指定定义下尚未核销的码, 供管理列表显示数量.
     *
     * @param definitionIds 定义编号列表
     * @return 定义编号对应的数量, 缺少的键表示零
     */
    @NotNull
    public CompletableFuture<Map<Long, Long>> countUnusedTokens(@NotNull List<Long> definitionIds) {
        if (definitionIds.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        String placeholders = String.join(", ", Collections.nCopies(definitionIds.size(), "?"));
        String sql = "SELECT definition_id, COUNT(*) FROM cdkey_keys WHERE definition_id IN (" + placeholders + ")"
                + " AND redeemed_at IS NULL GROUP BY definition_id";
        return this.submitRead(() -> {
            Map<Long, Long> counts = new HashMap<>();
            try (
                    PreparedStatement statement = this.prepare(this.reader, sql, definitionIds.toArray());
                    ResultSet rows = statement.executeQuery()
            ) {
                while (rows.next()) {
                    counts.put(rows.getLong(1), rows.getLong(2));
                }
            }
            return Map.copyOf(counts);
        });
    }

    /**
     * 在短读事务中读取总数和当前页, 返回后释放快照, 不占用写队列.
     *
     * @param where 从 FROM 开始且包含筛选条件的 SQL
     * @param order 固定的排序子句
     * @param values 筛选参数
     * @param page 从 1 开始的页码
     * @param offset 从零开始的偏移
     * @param size 本页最大条数
     * @param mapper 将数据库行转成一个结果
     * @param <T> 分页条目的类型
     * @return 总数及当前页
     */
    @NotNull
    private <T> CompletableFuture<CdkeyPage<T>> page(
            @NotNull String where,
            @NotNull String order,
            @NotNull List<?> values,
            int page,
            int offset,
            int size,
            @NotNull RowMapper<T> mapper
    ) {
        return this.submitRead(() -> this.transaction(this.reader, "BEGIN", () -> {
            long total = this.one(this.reader, "SELECT COUNT(*)" + where, row -> row.getLong(1), values.toArray());
            List<Object> parameters = new ArrayList<>(values);
            parameters.add(size);
            parameters.add(offset);
            List<T> entries = new ArrayList<>(size);
            try (
                    PreparedStatement statement = this.prepare(this.reader, "SELECT *" + where + order + " LIMIT ? OFFSET ?", parameters.toArray());
                    ResultSet rows = statement.executeQuery()
            ) {
                while (rows.next()) {
                    entries.add(mapper.read(rows));
                }
            }
            return new CdkeyPage<>(page, size, total, entries);
        }));
    }

    /**
     * 还原定义当前的规则, 次数, 版本和创建者.
     *
     * @param row 查询结果中的定义行
     * @return 可供管理或兑换使用的定义
     * @throws SQLException 无法读取字段时抛出
     */
    @NotNull
    private CdkeyDefinition mapDefinition(@NotNull ResultSet row) throws SQLException {
        CdkeyRules rules = new CdkeyRules(
                CdkeyJson.readCommands(row.getString("commands_json")), row.getLong("starts_at"), row.getLong("ends_at"),
                row.getLong("total_limit"), row.getLong("player_limit"), row.getString("permission")
        );
        CdkeyActor creator = new CdkeyActor(UUID.fromString(row.getString("creator_uuid")), row.getString("creator_name"));
        return new CdkeyDefinition(
                row.getLong("id"), CdkeyKind.fromKey(row.getString("kind")), row.getString("identifier"), rules,
                row.getLong("used_count"), row.getLong("version"), creator, row.getLong("created_at"), row.getLong("updated_at")
        );
    }

    /**
     * 读取完整码和核销时间, 供兑换与未用码列表使用.
     *
     * @param row 查询结果中的单码行
     * @return 单码和可空的核销时刻
     * @throws SQLException 无法读取字段时抛出
     */
    @NotNull
    private CdkeyToken mapToken(@NotNull ResultSet row) throws SQLException {
        long time = row.getLong("redeemed_at");
        Long redeemedAt = row.wasNull() ? null : time;
        return new CdkeyToken(row.getLong("batch_id"), row.getInt("ordinal"), row.getLong("definition_id"), row.getString("code"), redeemedAt);
    }

    /**
     * 读取兑换时独立保存的规则和实际派发命令.
     *
     * @param row 查询结果中的历史行
     * @return 与定义当前版本无关的兑换快照
     * @throws SQLException 无法读取字段时抛出
     */
    @NotNull
    private CdkeyRedemption mapRedemption(@NotNull ResultSet row) throws SQLException {
        return new CdkeyRedemption(
                row.getLong("id"), row.getLong("definition_id"), row.getString("token_code"),
                UUID.fromString(row.getString("player_uuid")), row.getString("player_name"), row.getLong("redeemed_at"),
                CdkeyJson.readSnapshot(row.getString("snapshot_json"))
        );
    }

    /**
     * 在现有连接中执行一个完整的写事务, 写入权在首次读取前取得.
     *
     * @param task 在存储线程运行的事务操作
     * @param <T> 操作结果类型
     * @return 提交后的操作结果
     * @throws SQLException SQLite 事务或读写失败时抛出
     */
    private <T> T transaction(@NotNull SqlTask<T> task) throws SQLException {
        return this.transaction(this.database, "BEGIN IMMEDIATE", task);
    }

    private <T> T transaction(Connection connection, String begin, SqlTask<T> task) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(begin);
            try {
                T value = task.run();
                statement.execute("COMMIT");
                return value;
            } catch (SQLException | RuntimeException | Error exception) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackError) {
                    exception.addSuppressed(rollbackError);
                }
                throw exception;
            }
        }
    }

    /**
     * 按位置绑定参数, 供查询及更新共用.
     *
     * @param connection 当前线程独占的连接
     * @param sql 使用问号标记参数的语句
     * @param values 按 SQL 中的顺序提供的参数, 允许 SQL NULL
     * @return 由调用方关闭的 JDBC 语句
     * @throws SQLException 无法准备语句或绑定参数时抛出
     */
    @NotNull
    private PreparedStatement prepare(@NotNull Connection connection, @NotNull String sql, @NotNull Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        } catch (SQLException exception) {
            try {
                statement.close();
            } catch (SQLException closeError) {
                exception.addSuppressed(closeError);
            }
            throw exception;
        }
    }

    /**
     * 读取最多一行, 在返回前关闭游标和语句.
     *
     * @param connection 当前线程独占的连接
     * @param sql 至多返回一行的查询或带 RETURNING 的语句
     * @param mapper 行映射函数
     * @param values 按 SQL 中的顺序提供的参数
     * @param <T> 结果类型
     * @return 查询结果, 无行时为 null
     * @throws SQLException 无法执行或读取语句时抛出
     */
    @Nullable
    private <T> T one(@NotNull Connection connection, @NotNull String sql, @NotNull RowMapper<T> mapper, @NotNull Object... values) throws SQLException {
        try (
                PreparedStatement statement = this.prepare(connection, sql, values);
                ResultSet rows = statement.executeQuery()
        ) {
            return rows.next() ? mapper.read(rows) : null;
        }
    }

    /**
     * 写入一条由 SQLite 分配编号的行.
     *
     * @param sql 以 RETURNING id 返回编号的 INSERT
     * @param values 按 SQL 中的顺序提供的参数
     * @return 新行编号
     * @throws SQLException SQLite 写入失败时抛出
     */
    private long insertId(@NotNull String sql, @NotNull Object... values) throws SQLException {
        return this.one(this.database, sql, row -> row.getLong(1), values);
    }

    /**
     * 执行一次写入并报告受影响的行数.
     *
     * @param sql 写入语句
     * @param values 按 SQL 中的顺序提供的参数
     * @return 受影响的行数
     * @throws SQLException SQLite 写入失败时抛出
     */
    private long update(@NotNull String sql, @NotNull Object... values) throws SQLException {
        try (PreparedStatement statement = this.prepare(this.database, sql, values)) {
            return statement.executeLargeUpdate();
        }
    }

    /**
     * 排入存储队列, 将 JDBC 异常交给异步调用方.
     *
     * @param task 读取或写入操作
     * @param <T> 操作结果类型
     * @return 后台任务结果
     */
    @NotNull
    private <T> CompletableFuture<T> submit(@NotNull SqlTask<T> task) {
        return this.submit(this.worker, task);
    }

    private <T> CompletableFuture<T> submitRead(SqlTask<T> task) {
        return this.submit(this.readWorker, task);
    }

    private <T> CompletableFuture<T> submit(ExecutorService executor, SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public void close() {
        // 调用方已停止受理, 关闭动作排在已有读写之后.
        CompletableFuture<Void> closing = this.submit(() -> {
            this.database.close();
            return null;
        });
        CompletableFuture<Void> readClosing = this.submitRead(() -> {
            this.reader.close();
            return null;
        });
        this.worker.shutdown();
        this.readWorker.shutdown();
        CompletableFuture.allOf(closing, readClosing).join();
    }

    /**
     * 存储线程上可以传播 JDBC 异常的一次操作.
     *
     * @param <T> 操作结果类型
     */
    @FunctionalInterface
    private interface SqlTask<T> {

        /**
         * 执行当前存储任务.
         *
         * @return 操作结果
         * @throws SQLException JDBC 操作失败时抛出
         */
        T run() throws SQLException;
    }

    /**
     * 将已定位的 SQLite 结果行还原为业务对象.
     *
     * @param <T> 业务对象类型
     */
    @FunctionalInterface
    private interface RowMapper<T> {

        /**
         * 读取游标所在行, 游标位置由调用方维护.
         *
         * @param row 已定位的 JDBC 结果行
         * @return 转换后的业务对象
         * @throws SQLException 无法读取字段时抛出
         */
        T read(@NotNull ResultSet row) throws SQLException;
    }
}