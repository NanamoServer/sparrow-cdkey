package top.catnies.sparrow.cdkey.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRules;
import top.catnies.sparrow.cdkey.model.CdkeySnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存控制台命令模板和兑换时的规则快照. 两类记录共用版本号, 读取时按各自的字段还原.
 */
final class CdkeyJson {
    private static final int SCHEMA_VERSION = 1; // 命令封装与兑换快照的 JSON 格式版本
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create(); // 保留模板和派发命令中的尖括号

    private CdkeyJson() {
    }

    /**
     * 将有序命令模板写入带格式版本的 JSON.
     *
     * @param commands 按派发顺序排列的模板
     * @return 数据库 commands_json 的内容
     */
    @NotNull
    static String writeCommands(@NotNull List<String> commands) {
        JsonObject json = envelope();
        json.add("commands", writeStrings(commands));
        return GSON.toJson(json);
    }

    /**
     * 从数据库格式还原有序命令模板.
     *
     * @param value commands_json 的内容
     * @return 不可变的命令模板列表
     * @throws IllegalArgumentException 格式版本不受支持时抛出
     */
    @NotNull
    static List<String> readCommands(@NotNull String value) {
        return readStrings(readEnvelope(value).getAsJsonArray("commands"));
    }

    /**
     * 写入一次兑换采用的规则及解析后的命令.
     *
     * @param snapshot 兑换时固定的规则快照
     * @return 数据库 snapshot_json 的内容
     */
    @NotNull
    static String writeSnapshot(@NotNull CdkeySnapshot snapshot) {
        JsonObject json = envelope();
        json.addProperty("definition_id", snapshot.definitionId());
        json.addProperty("definition_version", snapshot.definitionVersion());
        json.addProperty("kind", snapshot.kind().key());
        json.addProperty("identifier", snapshot.identifier());
        CdkeyRules rules = snapshot.rules();
        json.addProperty("starts_at", rules.startsAt());
        json.addProperty("ends_at", rules.endsAt());
        json.addProperty("total_limit", rules.totalLimit());
        json.addProperty("player_limit", rules.playerLimit());
        json.addProperty("permission", rules.permission());
        json.add("commands", writeStrings(rules.commands()));
        json.add("resolved_commands", writeStrings(snapshot.resolvedCommands()));
        return GSON.toJson(json);
    }

    /**
     * 还原历史快照, 供管理员查看兑换时采用的规则.
     *
     * @param value snapshot_json 的内容
     * @return 与当前定义独立的历史快照
     * @throws IllegalArgumentException 格式版本或定义类型不受支持时抛出
     */
    @NotNull
    static CdkeySnapshot readSnapshot(@NotNull String value) {
        JsonObject json = readEnvelope(value);
        CdkeyRules rules = new CdkeyRules(
                readStrings(json.getAsJsonArray("commands")),
                json.get("starts_at").getAsLong(),
                json.get("ends_at").getAsLong(),
                json.get("total_limit").getAsLong(),
                json.get("player_limit").getAsLong(),
                json.get("permission").getAsString()
        );
        return new CdkeySnapshot(
                json.get("definition_id").getAsLong(),
                json.get("definition_version").getAsLong(),
                CdkeyKind.fromKey(json.get("kind").getAsString()),
                json.get("identifier").getAsString(),
                rules,
                readStrings(json.getAsJsonArray("resolved_commands"))
        );
    }

    /**
     * 建立携带当前格式版本的 JSON 容器.
     *
     * @return 等待填写业务字段的对象
     */
    @NotNull
    private static JsonObject envelope() {
        JsonObject json = new JsonObject();
        json.addProperty("schema_version", SCHEMA_VERSION);
        return json;
    }

    /**
     * 解析数据库中的对象并确认它采用受支持的格式.
     *
     * @param value 持久化 JSON
     * @return 已确认格式版本的对象
     * @throws IllegalArgumentException 格式版本不受支持时抛出
     */
    @NotNull
    private static JsonObject readEnvelope(@NotNull String value) {
        JsonObject json = GSON.fromJson(value, JsonObject.class);
        int version = json.get("schema_version").getAsInt();
        if (version != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported CDKEY JSON version: " + version);
        return json;
    }

    /**
     * 按列表顺序建立 JSON 字符串数组.
     *
     * @param values 命令模板或解析后的命令
     * @return 保持输入顺序的 JSON 数组
     */
    @NotNull
    private static JsonArray writeStrings(@NotNull List<String> values) {
        JsonArray array = new JsonArray(values.size());
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /**
     * 将持久化字符串数组固定为可供规则共享的列表.
     *
     * @param array 持久化命令数组
     * @return 保持顺序的不可变列表
     */
    @NotNull
    private static List<String> readStrings(@NotNull JsonArray array) {
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement value : array) {
            values.add(value.getAsString());
        }
        return List.copyOf(values);
    }
}