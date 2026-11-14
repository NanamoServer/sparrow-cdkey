package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.result.CdkeyRedeemResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;

@ApiStatus.Internal
public final class DialogService implements Listener {
    // 页面导航, 回调认领和查询结果发布都在主线程维护顺序.
    private final Map<UUID, DialogSession> sessions = new HashMap<>();
    private final Map<UUID, DialogSession> displayed = new HashMap<>();
    private boolean closed;

    @Nullable
    DialogSession begin(Player player, String permission, String... additionalPermissions) {
        if (this.closed || !player.isConnected()) return null;
        DialogSession session = new DialogSession(player, permission, additionalPermissions);
        if (!session.canUse()) {
            player.sendMessage(Messages.DENIED.text().colorIfAbsent(DialogKit.ROSE));
            return null;
        }
        this.sessions.put(player.getUniqueId(), session);
        return session;
    }

    void show(DialogSession session, Dialog dialog) {
        if (!this.active(session)) return;
        this.displayed.put(session.viewer().getUniqueId(), session);
        session.viewer().showDialog(dialog);
    }

    <T> void await(DialogSession session, CompletableFuture<T> future, Consumer<T> result) {
        this.await(session, future, result, error -> this.failure(session, error));
    }

    <T> void await(DialogSession session, CompletableFuture<T> future, Consumer<T> result, Consumer<Throwable> failure) {
        this.await(
                future,
                value -> {
                    if (this.active(session)) {
                        result.accept(value);
                    }
                },
                failure
        );
    }

    void awaitRedemption(Player player, CompletableFuture<CdkeyRedeemResult> future) {
        this.await(
                future,
                result -> {
                    if (this.closed || !player.isConnected() || result.status() == CdkeyRedeemResult.Status.LEFT) return;
                    player.sendMessage(Messages.redeem(result.status()).colorIfAbsent(result.status() == CdkeyRedeemResult.Status.SUCCESS ? DialogKit.MINT : DialogKit.ROSE));
                },
                error -> {
                    this.logFailure(error);
                    if (this.closed || !player.isConnected()) return;
                    player.sendMessage((error instanceof SecurityException ? Messages.DENIED.text() : Messages.FAILED.text()).colorIfAbsent(DialogKit.ROSE));
                }
        );
    }

    private <T> void await(CompletableFuture<T> future, Consumer<T> result, Consumer<Throwable> failure) {
        future.whenComplete((value, error) -> this.onMain(() -> {
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                failure.accept(cause);
                return;
            }
            result.accept(value);
        }));
    }

    void failure(DialogSession session, Throwable error) {
        this.logFailure(error);
        if (this.active(session)) {
            this.finish(session);
            session.viewer().sendMessage((error instanceof SecurityException ? Messages.DENIED.text() : Messages.FAILED.text()).colorIfAbsent(DialogKit.ROSE));
        }
    }

    private void logFailure(Throwable error) {
        if (!(error instanceof SecurityException)) {
            SparrowCdkeyPlugin.instance().getLogger().log(Level.SEVERE, "CDKEY dialog operation failed", error);
        }
    }

    void finish(DialogSession session) {
        UUID uuid = session.viewer().getUniqueId();
        if (this.sessions.get(uuid) != session && this.displayed.get(uuid) != session) return;
        this.sessions.remove(uuid);
        this.displayed.remove(uuid);
        session.viewer().closeDialog();
    }

    boolean active(DialogSession session) {
        return this.canUse(session) && this.sessions.get(session.viewer().getUniqueId()) == session;
    }

    private boolean canUse(DialogSession session) {
        return !this.closed && session.viewer().isConnected() && !session.isExpired()
                && session.canUse();
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        Key key = event.getIdentifier();
        if (!key.namespace().equals("sparrow_cdkey") || !key.value().startsWith("dialog/")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player viewer = connection.getPlayer();
        DialogResponseView response = event.getDialogResponseView();
        this.onMain(() -> {
            DialogSession session = this.displayed.get(viewer.getUniqueId());
            if (session == null || session.viewer() != viewer) return;
            // 查询等待期间保留可见页面的退出操作, 普通操作仍只能认领一次.
            Consumer<DialogResponseView> action = session.claimExit(key);
            boolean exiting = action != null;
            if (!exiting) {
                if (this.sessions.get(viewer.getUniqueId()) != session) return;
                action = session.claim(key);
            }
            if (action == null) return;
            if (!this.canUse(session)) {
                this.finish(session);
                viewer.sendMessage((session.isExpired() ? Messages.EXPIRED_SESSION.text() : Messages.DENIED.text()).colorIfAbsent(DialogKit.ROSE));
                return;
            }
            if (exiting) {
                this.finish(session);
            }
            action.accept(response);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.sessions.remove(event.getPlayer().getUniqueId());
        this.displayed.remove(event.getPlayer().getUniqueId());
    }

    public void close() {
        this.closed = true;
        for (DialogSession session : this.displayed.values()) {
            session.viewer().closeDialog();
        }
        this.sessions.clear();
        this.displayed.clear();
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            MinecraftServer.getServer().scheduleOnMain(action);
        }
    }
}
