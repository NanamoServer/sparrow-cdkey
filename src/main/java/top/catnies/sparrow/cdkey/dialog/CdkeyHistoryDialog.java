package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.model.CdkeyActor;
import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRedemption;
import top.catnies.sparrow.cdkey.model.CdkeySnapshot;
import top.catnies.sparrow.cdkey.request.CdkeyRedemptionQuery;
import top.catnies.sparrow.cdkey.result.CdkeyPage;

import java.util.ArrayList;
import java.util.List;

final class CdkeyHistoryDialog {
    private static final Style LABEL = Style.style(NamedTextColor.GRAY);

    void player(@NotNull Player viewer, @NotNull CdkeyActor player, int number, @NotNull Runnable back) {
        Component heading = Component.text(player.name(), DialogKit.AMBER)
                .append(Component.newline())
                .append(DialogKit.field(Messages.UUID.text(), player.uuid().toString()));
        this.list(viewer, new CdkeyRedemptionQuery(null, player.uuid()), heading, number, back);
    }

    void definition(@NotNull Player viewer, @NotNull CdkeyDefinition definition, @NotNull Runnable back) {
        this.list(
                viewer,
                new CdkeyRedemptionQuery(definition.id(), null),
                Component.text(definition.identifier(), DialogKit.AMBER),
                1,
                () -> new CdkeyDialogs().detail(viewer, definition.id(), back)
        );
    }

    private void list(Player viewer, CdkeyRedemptionQuery query, Component heading, int number, Runnable back) {
        DialogSession session = this.dialogs().begin(viewer, SparrowCdkeyPlugin.VIEW_PERMISSION);
        if (session == null) return;
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().redemptions(viewer, query, number),
                page -> {
                    if (number > DialogKit.last(page)) {
                        this.list(viewer, query, heading, DialogKit.last(page), back);
                    } else {
                        this.showList(session, query, heading, page, back);
                    }
                }
        );
    }

    private void showList(DialogSession session, CdkeyRedemptionQuery query, Component heading, CdkeyPage<CdkeyRedemption> page, Runnable back) {
        Player viewer = session.viewer();
        boolean playerColumn = query.playerUuid() == null;
        HistoryRowLayout layout = this.layout(page.entries(), playerColumn);
        List<Component> lines = new ArrayList<>();
        if (!page.entries().isEmpty()) {
            lines.add(layout.row()
                    .centeredCell(Messages.REDEEMED_AT.text(), LABEL)
                    .centeredCell(Messages.KIND.text(), LABEL)
                    .centeredCell(playerColumn ? Messages.PLAYER.text() : Messages.IDENTIFIER.text(), LABEL)
                    .build());
        }
        for (int index = 0, size = page.entries().size(); index < size; index++) {
            CdkeyRedemption record = page.entries().get(index);
            Component tip = Component.join(
                    JoinConfiguration.newlines(),
                    List.of(
                            Component.text(record.playerName()),
                            Component.text(record.playerUuid().toString()),
                            Component.text(record.snapshot().identifier()),
                            DialogKit.field(Messages.REDEEMED_AT.text(), DialogKit.time(record.redeemedAt())),
                            Messages.VERSION.text(record.snapshot().definitionVersion()),
                            Messages.VIEW_SNAPSHOT.text()
                    )
            );
            Runnable returnToList = () -> this.list(viewer, query, heading, page.page(), back);
            Style link = Style.style(NamedTextColor.WHITE)
                    .hoverEvent(tip)
                    .clickEvent(session.text(() -> this.detail(viewer, record.id(), returnToList)));
            boolean code = record.snapshot().kind() == CdkeyKind.CODE;
            lines.add(layout.row()
                    .centeredCell(DialogKit.time(record.redeemedAt()), LABEL)
                    .centeredCell(code ? Messages.CODE.text() : Messages.CDKEY.text(), Style.style(code ? DialogKit.AMBER : DialogKit.MINT))
                    .centeredCell(playerColumn ? record.playerName() : record.snapshot().identifier(), link)
                    .build());
        }
        List<ActionButton> actions = new ArrayList<>();
        if (page.page() > 1) actions.add(DialogKit.button(session, Messages.PREVIOUS.text(), () -> this.list(viewer, query, heading, page.page() - 1, back)));
        if (page.page() < DialogKit.last(page)) actions.add(DialogKit.button(session, Messages.NEXT.text(), () -> this.list(viewer, query, heading, page.page() + 1, back)));
        actions.add(DialogKit.button(session, Messages.REFRESH.text(), () -> this.list(viewer, query, heading, page.page(), back)));
        Component header = heading.append(Component.newline()).append(DialogKit.summary(page));
        this.dialogs().show(
                session,
                DialogKit.panel(
                        session,
                        playerColumn ? Messages.HISTORY.text() : Messages.LOOKUP.text(),
                        List.of(DialogKit.text(header), DialogKit.rows(lines)),
                        actions,
                        back
                )
        );
    }

    private HistoryRowLayout layout(List<CdkeyRedemption> records, boolean playerColumn) {
        int time = HistoryRowLayout.width(Messages.REDEEMED_AT.text());
        int kind = HistoryRowLayout.width(Messages.KIND.text());
        int name = HistoryRowLayout.width(playerColumn ? Messages.PLAYER.text() : Messages.IDENTIFIER.text());
        for (int index = 0, size = records.size(); index < size; index++) {
            CdkeyRedemption record = records.get(index);
            time = Math.max(time, HistoryRowLayout.width(DialogKit.time(record.redeemedAt())));
            kind = Math.max(kind, HistoryRowLayout.width(record.snapshot().kind() == CdkeyKind.CODE ? Messages.CODE.text() : Messages.CDKEY.text()));
            name = Math.max(name, HistoryRowLayout.width(playerColumn ? record.playerName() : record.snapshot().identifier()));
        }
        return new HistoryRowLayout(Math.min(100, time + 8), Math.min(44, kind + 8), Math.min(140, name + 8));
    }

    private void detail(Player viewer, long id, Runnable back) {
        DialogSession session = this.dialogs().begin(viewer, SparrowCdkeyPlugin.VIEW_PERMISSION);
        if (session == null) return;
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().findRedemption(viewer, id),
                record -> {
                    if (record == null) {
                        new CdkeyDialogs().notice(viewer, Messages.RECORD_NOT_FOUND.text(), back);
                    } else {
                        this.showDetail(session, record, back);
                    }
                }
        );
    }

    private void showDetail(DialogSession session, CdkeyRedemption record, Runnable back) {
        Player viewer = session.viewer();
        CdkeySnapshot snapshot = record.snapshot();
        List<Component> fields = new ArrayList<>();
        fields.add(DialogKit.field(Messages.RECORD_ID.text(), Long.toString(record.id())));
        fields.add(DialogKit.field(Messages.PLAYER.text(), record.playerName()));
        fields.add(DialogKit.field(Messages.UUID.text(), record.playerUuid().toString()));
        fields.add(DialogKit.field(Messages.REDEEMED_AT.text(), DialogKit.time(record.redeemedAt())));
        fields.add(Messages.VERSION.text(snapshot.definitionVersion()));
        fields.add(DialogKit.field(Messages.KIND.text(), snapshot.kind() == CdkeyKind.CODE ? Messages.CODE.text() : Messages.CDKEY.text()));
        if (record.tokenCode() != null && viewer.hasPermission(CdkeyDialogs.COPY)) {
            fields.add(DialogKit.field(Messages.CDKEY.text(), DialogKit.copyable(record.tokenCode())));
        }
        // 历史始终显示持久化快照, 两组命令以纯文本展示.
        List<DialogBody> body = List.of(
                DialogKit.text(Component.text(snapshot.identifier(), DialogKit.AMBER)),
                DialogKit.text(Component.join(JoinConfiguration.newlines(), fields)),
                DialogKit.text(DialogKit.rules(snapshot.rules(), snapshot.kind())),
                DialogKit.text(Messages.COMMANDS.text().append(Component.newline()).append(Component.text(String.join("\n", snapshot.rules().commands())))),
                DialogKit.text(Messages.RESOLVED_COMMANDS.text().append(Component.newline()).append(Component.text(String.join("\n", snapshot.resolvedCommands()))))
        );
        Runnable returnToSnapshot = () -> this.detail(viewer, record.id(), back);
        this.dialogs().show(
                session,
                DialogKit.panel(
                        session,
                        Messages.SNAPSHOT.text(),
                        body,
                        List.of(
                                DialogKit.button(session, Messages.LOOKUP.text(), () -> this.player(viewer, new CdkeyActor(record.playerUuid(), record.playerName()), 1, returnToSnapshot)),
                                DialogKit.button(session, Messages.CURRENT_DEFINITION.text(), () -> new CdkeyDialogs().detail(viewer, record.definitionId(), returnToSnapshot))
                        ),
                        back
                )
        );
    }

    private DialogService dialogs() {
        return SparrowCdkeyPlugin.instance().dialogs();
    }
}