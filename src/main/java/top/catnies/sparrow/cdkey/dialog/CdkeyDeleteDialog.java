package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.result.CdkeyDeletePreview;

import java.util.List;

final class CdkeyDeleteDialog {

    void open(@NotNull Player viewer, long id, @NotNull Runnable back) {
        DialogSession session = this.dialogs().begin(viewer, SparrowCdkeyPlugin.VIEW_PERMISSION, CdkeyDialogs.DELETE);
        if (session == null) return;
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().find(viewer, id),
                definition -> {
                    if (definition == null) {
                        new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                        return;
                    }
                    this.dialogs().await(
                            session,
                            SparrowCdkeyPlugin.instance().service().previewDelete(viewer, id),
                            preview -> {
                                if (preview == null) {
                                    new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                                } else {
                                    this.show(session, definition.identifier(), preview, back);
                                }
                            }
                    );
                }
        );
    }

    private void show(DialogSession session, String identifier, CdkeyDeletePreview preview, Runnable back) {
        Player viewer = session.viewer();
        Component header = Component.text(identifier, DialogKit.AMBER)
                .append(Component.newline())
                .append(Messages.VERSION.text(preview.version()));
        Component counts = Component.join(
                JoinConfiguration.newlines(),
                List.of(
                        DialogKit.field(Messages.DELETE_DEFINITIONS.text(), "1"),
                        DialogKit.field(Messages.DELETE_BATCHES.text(), Long.toString(preview.batches())),
                        DialogKit.field(Messages.DELETE_TOKENS.text(), Long.toString(preview.tokens())),
                        DialogKit.field(Messages.DELETE_UNUSED.text(), Long.toString(preview.unusedTokens())),
                        DialogKit.field(Messages.DELETE_USAGES.text(), Long.toString(preview.playerUsages())),
                        DialogKit.field(Messages.HISTORY.text(), Long.toString(preview.redemptions()))
                )
        );
        ActionButton confirm = ActionButton.create(
                Messages.DELETE_CONFIRM.text().colorIfAbsent(DialogKit.ROSE),
                null,
                DialogKit.BUTTON_WIDTH,
                session.button(response -> this.submit(session, preview, back))
        );
        ActionButton cancel = DialogKit.exit(session, Messages.CANCEL.text(), () -> new CdkeyDialogs().detail(viewer, preview.id(), back));
        this.dialogs().show(
                session,
                Dialog.create(builder -> builder.empty()
                .base(DialogKit.base(Messages.DELETE.text(), List.of(DialogKit.text(header), DialogKit.text(counts), DialogKit.text(Messages.DELETE_HINT.text())), List.of()))
                .type(DialogType.confirmation(confirm, cancel)))
        );
    }

    private void submit(DialogSession session, CdkeyDeletePreview preview, Runnable back) {
        Player viewer = session.viewer();
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().delete(viewer, preview.id(), preview.version()),
                result -> {
                    switch (result.status()) {
                        case SUCCESS -> new CdkeyDialogs().notice(viewer, Messages.DELETED.text(result.batches(), result.tokens(), result.playerUsages(), result.redemptions()), back);
                        case NOT_FOUND -> new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                        case VERSION_CONFLICT -> this.conflict(viewer, preview.id(), back);
                    }
                }
        );
    }

    private void conflict(Player viewer, long id, Runnable back) {
        DialogSession session = this.dialogs().begin(viewer, SparrowCdkeyPlugin.VIEW_PERMISSION, CdkeyDialogs.DELETE);
        if (session == null) return;
        this.dialogs().show(
                session,
                DialogKit.panel(
                        session,
                        Messages.CONFLICT_TITLE.text(),
                        List.of(DialogKit.text(Messages.DELETE_CONFLICT.text())),
                        List.of(DialogKit.button(session, Messages.PREVIEW_AGAIN.text(), () -> this.open(viewer, id, back))),
                        () -> new CdkeyDialogs().detail(viewer, id, back)
                )
        );
    }

    private DialogService dialogs() {
        return SparrowCdkeyPlugin.instance().dialogs();
    }
}
