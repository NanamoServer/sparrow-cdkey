package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.model.CdkeyActor;
import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRules;
import top.catnies.sparrow.cdkey.request.CdkeyDefinitionQuery.TimeState;
import top.catnies.sparrow.cdkey.request.CdkeyDefinitionQuery;
import top.catnies.sparrow.cdkey.result.CdkeyPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApiStatus.Internal
public final class CdkeyDialogs {
    static final String MANAGE = "sparrow.command.cdkey.manage";
    static final String COPY = "sparrow.command.cdkey.copy";
    static final String DELETE = "sparrow.command.cdkey.delete";
    private static final Style LABEL = Style.style(NamedTextColor.GRAY);
    private static final HistoryRowLayout ROWS = new HistoryRowLayout(50, 142, 92);

    public void home(@NotNull Player viewer) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        List<ActionButton> actions = new ArrayList<>();
        actions.add(DialogKit.button(session, Messages.CODE_LIST.text(), () -> this.list(viewer, CdkeyKind.CODE, null, 1)));
        actions.add(DialogKit.button(session, Messages.GIFT_LIST.text(), () -> this.list(viewer, CdkeyKind.GIFT, null, 1)));
        if (viewer.hasPermission(MANAGE)) {
            actions.add(DialogKit.button(session, Messages.CREATE_CODE.text(), () -> new CdkeyEditDialog().create(viewer, CdkeyKind.CODE, () -> this.home(viewer))));
            actions.add(DialogKit.button(session, Messages.CREATE_GIFT.text(), () -> new CdkeyEditDialog().create(viewer, CdkeyKind.GIFT, () -> this.home(viewer))));
        }
        actions.add(DialogKit.button(session, Messages.LOOKUP.text(), () -> this.lookup(viewer)));
        this.dialogs().show(session, DialogKit.panel(session, Messages.HOME.text(), List.of(), actions, null));
    }

    private void list(Player viewer, CdkeyKind kind, @Nullable TimeState filter, int number) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        long now = System.currentTimeMillis();
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().definitions(viewer, new CdkeyDefinitionQuery(kind, filter, now), number),
                page -> {
                    int last = DialogKit.last(page);
                    if (number > last) {
                        this.list(viewer, kind, filter, last);
                        return;
                    }
                    if (kind == CdkeyKind.GIFT) {
                        this.dialogs().await(
                                session,
                                SparrowCdkeyPlugin.instance().service().countUnusedTokens(viewer, page.entries().stream().map(CdkeyDefinition::id).toList()),
                                counts -> this.showList(session, kind, filter, page, now, counts)
                        );
                    } else {
                        this.showList(session, kind, filter, page, now, Map.of());
                    }
                }
        );
    }

    private void showList(DialogSession session, CdkeyKind kind, @Nullable TimeState filter, CdkeyPage<CdkeyDefinition> page, long now, Map<Long, Long> unused) {
        Player viewer = session.viewer();
        Component title = kind == CdkeyKind.CODE ? Messages.CODE_LIST.text() : Messages.GIFT_LIST.text();
        List<Component> lines = new ArrayList<>();
        if (!page.entries().isEmpty()) {
            lines.add(ROWS.row()
                    .cell(Messages.STATUS.text(), LABEL)
                    .cell(kind == CdkeyKind.CODE ? Messages.CODE.text() : Messages.GIFT_ID.text(), LABEL)
                    .cell(kind == CdkeyKind.CODE ? Messages.USED_LIMIT.text() : Messages.UNUSED_USED.text(), LABEL)
                    .build());
        }
        for (int index = 0, size = page.entries().size(); index < size; index++) {
            CdkeyDefinition definition = page.entries().get(index);
            Component tip = Component.text(definition.identifier())
                    .append(Component.newline())
                    .append(DialogKit.rules(definition.rules(), definition.kind()))
                    .append(Component.newline())
                    .append(Messages.VIEW_DETAIL.text());
            Runnable open = () -> this.detail(viewer, definition.id(), () -> this.list(viewer, kind, filter, page.page()));
            Style link = Style.style(NamedTextColor.WHITE).hoverEvent(tip).clickEvent(session.text(open));
            Component amount = kind == CdkeyKind.CODE
                    ? Component.text(definition.usedCount() + " / ").append(DialogKit.unlimited(definition.rules().totalLimit()))
                    : Component.text(unused.getOrDefault(definition.id(), 0L) + " / " + definition.usedCount());
            boolean full = kind == CdkeyKind.CODE && definition.rules().totalLimit() != -1 && definition.usedCount() >= definition.rules().totalLimit();
            if (full) amount = amount.append(Component.space()).append(Messages.FULL.text());
            if (kind == CdkeyKind.GIFT && definition.rules().playerLimit() == 0) amount = amount.append(Component.space()).append(Messages.STOPPED.text());
            lines.add(ROWS.row()
                    .cell(DialogKit.state(definition.rules(), now), Style.style(this.stateColor(definition.rules(), now)))
                    .cell(definition.identifier(), link)
                    .cell(amount, Style.style(full ? DialogKit.AMBER : NamedTextColor.GRAY))
                    .build());
        }
        List<ActionButton> actions = new ArrayList<>();
        if (page.page() > 1) actions.add(DialogKit.button(session, Messages.PREVIOUS.text(), () -> this.list(viewer, kind, filter, page.page() - 1)));
        if (page.page() < DialogKit.last(page)) actions.add(DialogKit.button(session, Messages.NEXT.text(), () -> this.list(viewer, kind, filter, page.page() + 1)));
        actions.add(DialogKit.button(session, Messages.FILTER.text(DialogKit.stateName(filter)), () -> this.list(viewer, kind, this.next(filter), 1)));
        actions.add(DialogKit.button(session, Messages.REFRESH.text(), () -> this.list(viewer, kind, filter, page.page())));
        if (viewer.hasPermission(MANAGE)) {
            actions.add(DialogKit.button(
                    session,
                    kind == CdkeyKind.CODE ? Messages.CREATE_CODE.text() : Messages.CREATE_GIFT.text(),
                    () -> new CdkeyEditDialog().create(viewer, kind, () -> this.list(viewer, kind, filter, page.page()))
            ));
        }
        this.dialogs().show(
                session,
                DialogKit.panel(
                        session,
                        title,
                        List.of(DialogKit.text(title.colorIfAbsent(DialogKit.AMBER).append(Component.newline()).append(DialogKit.summary(page))), DialogKit.rows(lines)),
                        actions,
                        () -> this.home(viewer)
                )
        );
    }

    void detail(Player viewer, long id, Runnable back) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().find(viewer, id),
                definition -> {
                    if (definition == null) {
                        this.notice(viewer, Messages.NOT_FOUND.text(), back);
                        return;
                    }
                    if (definition.kind() == CdkeyKind.GIFT) {
                        this.dialogs().await(
                                session,
                                SparrowCdkeyPlugin.instance().service().countUnusedTokens(viewer, List.of(id)),
                                unused -> this.showDetail(session, definition, unused.getOrDefault(id, 0L), back)
                        );
                    } else {
                        this.showDetail(session, definition, 0, back);
                    }
                }
        );
    }

    void saved(Player viewer, CdkeyDefinition definition, Runnable back) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().countUnusedTokens(viewer, List.of(definition.id())),
                unused -> this.showDetail(session, definition, unused.getOrDefault(definition.id(), 0L), back)
        );
    }

    private void showDetail(DialogSession session, CdkeyDefinition definition, long unused, Runnable back) {
        Player viewer = session.viewer();
        Component amount = definition.kind() == CdkeyKind.CODE
                ? Messages.USED.text(definition.usedCount())
                : Messages.UNUSED.text(unused, definition.usedCount());
        Component header = Component.text(definition.identifier(), DialogKit.AMBER)
                .append(Component.newline())
                .append(DialogKit.state(definition.rules(), System.currentTimeMillis()).append(Component.text(" · ")).append(Messages.VERSION.text(definition.version())))
                .append(Component.newline())
                .append(amount);
        List<DialogBody> body = List.of(
                DialogKit.text(header),
                DialogKit.text(DialogKit.rules(definition.rules(), definition.kind())),
                DialogKit.text(Messages.COMMANDS.text().append(Component.newline()).append(Component.text(String.join("\n", definition.rules().commands())))),
                DialogKit.text(Component.join(
                        JoinConfiguration.newlines(),
                        List.of(
                                DialogKit.field(Messages.CREATOR.text(), definition.creator().name()),
                                DialogKit.field(Messages.CREATED.text(), DialogKit.time(definition.createdAt())),
                                DialogKit.field(Messages.UPDATED.text(), DialogKit.time(definition.updatedAt()))
                        )
                ))
        );
        List<ActionButton> actions = new ArrayList<>();
        if (viewer.hasPermission(MANAGE)) {
            actions.add(DialogKit.button(session, Messages.EDIT.text(), () -> new CdkeyEditDialog().edit(viewer, definition, back)));
        }
        if (definition.kind() == CdkeyKind.GIFT && viewer.hasPermission(MANAGE) && viewer.hasPermission(COPY)) {
            actions.add(DialogKit.button(session, Messages.GENERATE.text(), () -> new CdkeyGenerateDialog().open(viewer, definition, back)));
        }
        actions.add(DialogKit.button(session, Messages.HISTORY.text(), () -> new CdkeyHistoryDialog().definition(viewer, definition, back)));
        if (definition.kind() == CdkeyKind.GIFT && viewer.hasPermission(COPY)) {
            actions.add(DialogKit.button(session, Messages.TOKENS.text(), () -> new CdkeyTokenDialog().open(viewer, definition.id(), 1, back)));
        }
        if (viewer.hasPermission(DELETE)) {
            actions.add(DialogKit.button(session, Messages.DELETE.text(), () -> new CdkeyDeleteDialog().open(viewer, definition.id(), back)));
        }
        actions.add(DialogKit.button(session, Messages.REFRESH.text(), () -> this.detail(viewer, definition.id(), back)));
        this.dialogs().show(session, DialogKit.panel(session, Messages.DETAIL.text(), body, actions, back));
    }

    void notice(Player viewer, Component message, Runnable back) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        this.dialogs().show(session, DialogKit.panel(session, Messages.HOME.text(), List.of(DialogKit.text(message)), List.of(), back));
    }

    private void lookup(Player viewer) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        DialogInput input = DialogInput.text("player", Messages.PLAYER_INPUT.text()).width(DialogKit.TEXT_WIDTH).maxLength(36).build();
        ActionButton search = ActionButton.create(
                Messages.SEARCH.text(),
                null,
                DialogKit.BUTTON_WIDTH,
                session.button(response -> this.history(viewer, response == null || response.getText("player") == null ? "" : response.getText("player")))
        );
        ActionButton cancel = DialogKit.exit(session, Messages.CANCEL.text(), () -> this.home(viewer));
        this.dialogs().show(
                session,
                Dialog.create(builder -> builder.empty()
                .base(DialogKit.base(Messages.LOOKUP.text(), List.of(), List.of(input)))
                .type(DialogType.confirmation(search, cancel)))
        );
    }

    public void history(@NotNull Player viewer, @NotNull String input) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().findPlayers(viewer, input),
                players -> {
                    if (players.isEmpty()) {
                        this.notice(viewer, Messages.UNKNOWN_PLAYER.text(), () -> this.lookup(viewer));
                    } else if (players.size() == 1) {
                        new CdkeyHistoryDialog().player(viewer, players.getFirst(), 1, () -> this.lookup(viewer));
                    } else {
                        List<ActionButton> actions = new ArrayList<>();
                        for (int index = 0, size = players.size(); index < size; index++) {
                            CdkeyActor player = players.get(index);
                            actions.add(DialogKit.button(session, Component.text(player.name() + " · " + player.uuid()), () -> new CdkeyHistoryDialog().player(viewer, player, 1, () -> this.lookup(viewer))));
                        }
                        this.dialogs().show(session, DialogKit.panel(session, Messages.LOOKUP.text(), List.of(DialogKit.text(Messages.CHOOSE_PLAYER.text())), actions, () -> this.lookup(viewer)));
                    }
                }
        );
    }

    private TextColor stateColor(CdkeyRules rules, long now) {
        if (now < rules.startsAt()) return DialogKit.AMBER;
        return rules.endsAt() != -1 && now >= rules.endsAt() ? DialogKit.ROSE : DialogKit.MINT;
    }

    @Nullable
    private TimeState next(@Nullable TimeState state) {
        return switch (state) {
            case null -> TimeState.UPCOMING;
            case UPCOMING -> TimeState.ACTIVE;
            case ACTIVE -> TimeState.EXPIRED;
            case EXPIRED -> null;
        };
    }

    @Nullable
    private DialogSession begin(Player viewer) {
        return this.dialogs().begin(viewer, SparrowCdkeyPlugin.VIEW_PERMISSION);
    }

    private DialogService dialogs() {
        return SparrowCdkeyPlugin.instance().dialogs();
    }
}
