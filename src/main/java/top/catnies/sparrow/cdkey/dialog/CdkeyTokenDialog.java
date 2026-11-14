package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyToken;
import top.catnies.sparrow.cdkey.result.CdkeyPage;

import java.util.ArrayList;
import java.util.List;

final class CdkeyTokenDialog {
    private static final HistoryRowLayout ROWS = new HistoryRowLayout(54, 230);
    private static final Style LABEL = Style.style(NamedTextColor.GRAY);

    void open(@NotNull Player viewer, long id, int number, @NotNull Runnable back) {
        DialogSession session = this.dialogs().begin(viewer, SparrowCdkeyPlugin.VIEW_PERMISSION, CdkeyDialogs.COPY);
        if (session == null) return;
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().find(viewer, id),
                definition -> {
                    if (definition == null) {
                        new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                        return;
                    }
                    if (definition.kind() != CdkeyKind.GIFT) {
                        new CdkeyDialogs().notice(viewer, Messages.NOT_GIFT.text(), back);
                        return;
                    }
                    this.dialogs().await(
                            session,
                            SparrowCdkeyPlugin.instance().service().tokens(viewer, id, number),
                            page -> {
                                if (number > DialogKit.last(page)) {
                                    this.open(viewer, id, DialogKit.last(page), back);
                                } else {
                                    this.show(session, definition, page, back);
                                }
                            }
                    );
                }
        );
    }

    private void show(DialogSession session, CdkeyDefinition definition, CdkeyPage<CdkeyToken> page, Runnable back) {
        Player viewer = session.viewer();
        List<Component> lines = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        if (!page.entries().isEmpty()) lines.add(ROWS.row().cell(Messages.BATCH_ORDINAL.text(), LABEL).cell(Messages.CDKEY.text(), LABEL).build());
        for (int index = 0, size = page.entries().size(); index < size; index++) {
            CdkeyToken token = page.entries().get(index);
            codes.add(token.code());
            String batch = token.batchId() + "/" + token.ordinal();
            lines.add(ROWS.row()
                    .cell(batch, LABEL.hoverEvent(Component.text(batch)))
                    .cell(token.code(), DialogKit.copyable(token.code()).style())
                    .build());
        }
        Component header = Component.text(definition.identifier(), DialogKit.AMBER)
                .append(Component.newline())
                .append(DialogKit.state(definition.rules(), System.currentTimeMillis()))
                .append(Component.newline())
                .append(DialogKit.summary(page))
                .append(Component.newline())
                .append(Messages.TOKENS_HINT.text());
        List<ActionButton> actions = new ArrayList<>();
        if (page.page() > 1) actions.add(DialogKit.button(session, Messages.PREVIOUS.text(), () -> this.open(viewer, definition.id(), page.page() - 1, back)));
        if (page.page() < DialogKit.last(page)) actions.add(DialogKit.button(session, Messages.NEXT.text(), () -> this.open(viewer, definition.id(), page.page() + 1, back)));
        actions.add(DialogKit.button(session, Messages.REFRESH.text(), () -> this.open(viewer, definition.id(), page.page(), back)));
        if (!codes.isEmpty()) {
            actions.add(DialogKit.copyButton(Messages.COPY_PAGE.text(), codes));
        }
        this.dialogs().show(
                session,
                DialogKit.panel(
                        session,
                        Messages.TOKENS.text(),
                        List.of(DialogKit.text(header), DialogKit.rows(lines)),
                        actions,
                        () -> new CdkeyDialogs().detail(viewer, definition.id(), back)
                )
        );
    }

    private DialogService dialogs() {
        return SparrowCdkeyPlugin.instance().dialogs();
    }
}