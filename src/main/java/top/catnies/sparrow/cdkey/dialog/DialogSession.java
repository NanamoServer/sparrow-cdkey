package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

final class DialogSession {
    private static final BinaryTagHolder EMPTY = BinaryTagHolder.binaryTagHolder("{}");
    private final Player viewer;
    private final String permission;
    private final List<String> additionalPermissions;
    private final UUID id = UUID.randomUUID();
    private final Key exitKey = Key.key("sparrow_cdkey", "dialog/" + this.id + "/exit");
    private final long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
    private final Map<Key, Consumer<DialogResponseView>> actions = new HashMap<>();
    @Nullable
    private Consumer<DialogResponseView> exitAction;

    DialogSession(Player viewer, String permission, String... additionalPermissions) {
        this.viewer = viewer;
        this.permission = permission;
        this.additionalPermissions = List.of(additionalPermissions);
    }

    Player viewer() {
        return this.viewer;
    }

    boolean canUse() {
        if (!this.viewer.hasPermission(this.permission)) return false;
        for (String required : this.additionalPermissions) {
            if (!this.viewer.hasPermission(required)) return false;
        }
        return true;
    }

    boolean isExpired() {
        return System.nanoTime() - this.deadline >= 0;
    }

    DialogAction button(Consumer<DialogResponseView> action) {
        return DialogAction.customClick(this.register(action), null);
    }

    DialogAction exit(Consumer<DialogResponseView> action) {
        this.exitAction = action;
        return DialogAction.customClick(this.exitKey, null);
    }

    ClickEvent text(Runnable action) {
        return ClickEvent.custom(this.register(response -> action.run()), EMPTY);
    }

    @Nullable
    Consumer<DialogResponseView> claim(Key key) {
        Consumer<DialogResponseView> action = this.actions.get(key);
        if (action != null) this.actions.clear();
        return action;
    }

    @Nullable
    Consumer<DialogResponseView> claimExit(Key key) {
        if (!key.equals(this.exitKey)) return null;
        Consumer<DialogResponseView> action = this.exitAction;
        this.exitAction = null;
        this.actions.clear();
        return action;
    }

    private Key register(Consumer<DialogResponseView> action) {
        Key key = Key.key("sparrow_cdkey", "dialog/" + this.id + "/" + this.actions.size());
        this.actions.put(key, action);
        return key;
    }
}