package top.catnies.sparrow.cdkey.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import top.catnies.sparrow.cdkey.result.CdkeyRedeemResult;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

@ApiStatus.Internal
public enum Messages {
    HOME,
    MANAGE_DESCRIPTION,
    REDEEM_DESCRIPTION,
    PLAYER_ONLY,
    PLAYER_OFFLINE,
    DENIED,
    FAILED,
    EXPIRED_SESSION,
    CLOSE,
    BACK,
    CANCEL,
    PREVIOUS,
    NEXT,
    REFRESH,
    EMPTY,
    PAGE("page", "pages", "total"),
    ALL,
    UPCOMING,
    ACTIVE,
    EXPIRED,
    FILTER("state"),
    CODE_LIST,
    GIFT_LIST,
    CREATE_CODE,
    CREATE_GIFT,
    DETAIL,
    VIEW_DETAIL,
    STATUS,
    CODE,
    GIFT,
    GIFT_ID,
    USED_LIMIT,
    UNUSED_USED,
    FULL,
    STOPPED,
    UNLIMITED,
    NONE,
    VERSION("version"),
    USED("used"),
    UNUSED("unused", "used"),
    CREATOR,
    CREATED,
    UPDATED,
    START,
    END,
    ZONE,
    TOTAL_LIMIT,
    PLAYER_LIMIT,
    CODE_LIMIT_HINT,
    GIFT_LIMIT_HINT,
    COMMANDS,
    COMMAND_HINT,
    PERMISSION,
    EDIT,
    SAVE,
    CREATE,
    EDIT_HINT,
    INVALID_DATE,
    INVALID_RANGE,
    INVALID_LIMIT,
    INVALID_FORM,
    DUPLICATE,
    NOT_FOUND,
    CONFLICT_TITLE,
    CONFLICT,
    REOPEN,
    DRAFT,
    REDEEM,
    REDEEM_INPUT,
    REDEEM_HINT,
    SUBMIT,
    LOOKUP,
    PLAYER_INPUT,
    SEARCH,
    UNKNOWN_PLAYER,
    CHOOSE_PLAYER,
    REDEEMED_AT,
    IDENTIFIER,
    KIND,
    GENERATE,
    GENERATE_HINT,
    QUANTITY,
    INVALID_QUANTITY,
    NOT_GIFT,
    GENERATE_CONFLICT,
    BATCH_RESULT,
    GENERATED("count", "batch"),
    ORDINAL,
    BATCH_ORDINAL,
    TOKENS,
    TOKENS_HINT,
    COPY_HINT,
    COPY_PAGE,
    COPY_BATCH,
    HISTORY,
    PLAYER,
    SNAPSHOT,
    VIEW_SNAPSHOT,
    RECORD_ID,
    RECORD_NOT_FOUND,
    RESOLVED_COMMANDS,
    CURRENT_DEFINITION,
    DELETE,
    DELETE_CONFIRM,
    DELETE_HINT,
    DELETE_DEFINITIONS,
    DELETE_BATCHES,
    DELETE_TOKENS,
    DELETE_UNUSED,
    DELETE_USAGES,
    DELETE_CONFLICT,
    PREVIEW_AGAIN,
    DELETED("batches", "tokens", "usages", "redemptions"),
    REDEEM_SUCCESS,
    REDEEM_UNKNOWN,
    REDEEM_NOT_STARTED,
    REDEEM_EXPIRED,
    REDEEM_TOTAL_LIMIT,
    REDEEM_PLAYER_LIMIT,
    REDEEM_ALREADY_USED,
    REDEEM_UNAVAILABLE,
    REDEEM_LEFT,
    FIELD("label", "value"),
    UUID,
    CDKEY,
    FORM_CODE_LABEL,
    FORM_GIFT_LABEL,
    FORM_COMMANDS_LABEL,
    FORM_TOTAL_LABEL,
    FORM_PLAYER_LABEL,
    FORM_START_LABEL,
    FORM_END_LABEL,
    FORM_PERMISSION_LABEL,
    CODE_HINT,
    GIFT_HINT,
    START_HINT("zone"),
    END_HINT("zone"),
    PERMISSION_HINT;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static Map<Messages, String> templates;
    private static Map<Messages, Component> fixed;

    private final String[] parameters;

    Messages(String... parameters) {
        this.parameters = parameters;
    }

    public static void load(@NotNull File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not read lang.yml", exception);
        }
        Map<Messages, String> loaded = new EnumMap<>(Messages.class);
        Map<Messages, Component> rendered = new EnumMap<>(Messages.class);
        for (Messages message : values()) {
            String key = message.name().toLowerCase(Locale.ROOT).replace('_', '-');
            if (!yaml.isString(key)) throw new IllegalStateException("Missing or non-string language key: " + key);
            String template = yaml.getString(key);
            loaded.put(message, template);
            if (message.parameters.length == 0) rendered.put(message, MINI_MESSAGE.deserialize(template));
        }
        templates = Map.copyOf(loaded);
        fixed = Map.copyOf(rendered);
    }

    @NotNull
    public Component text(@NotNull Object... values) {
        if (this.parameters.length == 0) return fixed.get(this);
        // 动态值作为文本或组件插入, 不参与模板解析.
        TagResolver[] resolvers = new TagResolver[this.parameters.length];
        for (int index = 0; index < this.parameters.length; index++) {
            Object value = values[index];
            resolvers[index] = value instanceof Component component
                    ? Placeholder.component(this.parameters[index], component)
                    : Placeholder.unparsed(this.parameters[index], value.toString());
        }
        return MINI_MESSAGE.deserialize(templates.get(this), resolvers);
    }

    @NotNull
    public String plain(@NotNull Object... values) {
        return PlainTextComponentSerializer.plainText().serialize(this.text(values));
    }

    @NotNull
    public static Component redeem(@NotNull CdkeyRedeemResult.Status status) {
        return switch (status) {
            case SUCCESS -> REDEEM_SUCCESS.text();
            case UNKNOWN_CODE -> REDEEM_UNKNOWN.text();
            case NOT_STARTED -> REDEEM_NOT_STARTED.text();
            case EXPIRED -> REDEEM_EXPIRED.text();
            case TOTAL_LIMIT -> REDEEM_TOTAL_LIMIT.text();
            case PLAYER_LIMIT -> REDEEM_PLAYER_LIMIT.text();
            case ALREADY_USED -> REDEEM_ALREADY_USED.text();
            case NO_PERMISSION -> DENIED.text();
            case UNAVAILABLE -> REDEEM_UNAVAILABLE.text();
            case LEFT -> REDEEM_LEFT.text();
        };
    }
}