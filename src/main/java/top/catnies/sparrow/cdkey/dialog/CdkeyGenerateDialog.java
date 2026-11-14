package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.result.CdkeyGenerateResult;

import java.util.ArrayList;
import java.util.List;

final class CdkeyGenerateDialog {
    private static final HistoryRowLayout ROWS = new HistoryRowLayout(54, 230);

    void open(@NotNull Player viewer, @NotNull CdkeyDefinition definition, @NotNull Runnable back) {
        this.form(viewer, definition, "16", null, back);
    }

    private void form(Player viewer, CdkeyDefinition definition, String quantity, @Nullable Component error, Runnable back) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        Component header = Component.text(definition.identifier(), DialogKit.AMBER)
                .append(Component.newline())
                .append(Messages.VERSION.text(definition.version()))
                .append(Component.newline())
                .append(Messages.GENERATE_HINT.text());
        if (error != null) header = header.append(Component.newline()).append(error.colorIfAbsent(DialogKit.ROSE));
        DialogInput input = DialogInput.text("quantity", Messages.QUANTITY.text())
                .initial(quantity)
                .maxLength(3)
                .width(DialogKit.TEXT_WIDTH)
                .build();
        ActionButton generate = ActionButton.create(
                Messages.GENERATE.text(),
                null,
                DialogKit.BUTTON_WIDTH,
                session.button(response -> {
                    String value = response == null ? null : response.getText("quantity");
                    this.submit(session, definition, value == null ? "" : value, back);
                })
        );
        ActionButton cancel = DialogKit.exit(session, Messages.CANCEL.text(), () -> new CdkeyDialogs().detail(viewer, definition.id(), back));
        Component content = header;
        this.dialogs().show(
                session,
                Dialog.create(builder -> builder.empty()
                .base(DialogKit.base(Messages.GENERATE.text(), List.of(DialogKit.text(content)), List.of(input)))
                .type(DialogType.confirmation(generate, cancel)))
        );
    }

    private void submit(DialogSession session, CdkeyDefinition definition, String value, Runnable back) {
        Player viewer = session.viewer();
        int quantity;
        try {
            quantity = Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            this.form(viewer, definition, value, Messages.INVALID_QUANTITY.text(), back);
            return;
        }
        this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().generate(viewer, definition.id(), definition.version(), quantity),
                result -> {
                    switch (result.status()) {
                        case SUCCESS -> this.result(viewer, definition, result, back);
                        case NOT_FOUND -> new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                        case NOT_GIFT -> new CdkeyDialogs().notice(viewer, Messages.NOT_GIFT.text(), back);
                        case VERSION_CONFLICT -> this.conflict(viewer, definition, value, back);
                    }
                },
                error -> {
                    if (error instanceof IllegalArgumentException) {
                        if (this.dialogs().active(session)) this.form(viewer, definition, value, Messages.INVALID_QUANTITY.text(), back);
                    } else {
                        this.dialogs().failure(session, error);
                    }
                }
        );
    }

    private void conflict(Player viewer, CdkeyDefinition definition, String value, Runnable back) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        ActionButton reload = DialogKit.button(
                session,
                Messages.REOPEN.text(),
                () -> this.dialogs().await(
                        session,
                        SparrowCdkeyPlugin.instance().service().find(viewer, definition.id()),
                        latest -> {
                            if (latest == null) {
                                new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                            } else {
                                this.form(viewer, latest, value, null, back);
                            }
                        }
                )
        );
        this.dialogs().show(
                session,
                DialogKit.panel(
                        session,
                        Messages.CONFLICT_TITLE.text(),
                        List.of(DialogKit.text(Messages.GENERATE_CONFLICT.text())),
                        List.of(reload),
                        () -> this.form(viewer, definition, value, null, back)
                )
        );
    }

    private void result(Player viewer, CdkeyDefinition definition, CdkeyGenerateResult result, Runnable back) {
        DialogSession session = this.begin(viewer);
        if (session == null) return;
        List<Component> lines = new ArrayList<>();
        Style label = Style.style(NamedTextColor.GRAY);
        lines.add(ROWS.row().cell(Messages.ORDINAL.text(), label).cell(Messages.CDKEY.text(), label).build());
        for (int index = 0, size = result.codes().size(); index < size; index++) {
            String code = result.codes().get(index);
            // 表格裁剪只影响显示, 悬浮和复制始终使用完整码.
            lines.add(ROWS.row().cell(Integer.toString(index + 1), label).cell(code, DialogKit.copyable(code).style()).build());
        }
        Component header = Component.text(definition.identifier(), DialogKit.AMBER)
                .append(Component.newline())
                .append(Messages.GENERATED.text(result.codes().size(), result.batch().id()))
                .append(Component.newline())
                .append(Messages.COPY_HINT.text());
        this.dialogs().show(
                session,
                DialogKit.panel(
                        session,
                        Messages.BATCH_RESULT.text(),
                        List.of(DialogKit.text(header), DialogKit.rows(lines)),
                        List.of(
                                DialogKit.button(session, Messages.TOKENS.text(), () -> new CdkeyTokenDialog().open(viewer, definition.id(), 1, back)),
                                DialogKit.copyButton(Messages.COPY_BATCH.text(), result.codes())
                        ),
                        () -> new CdkeyDialogs().detail(viewer, definition.id(), back)
                )
        );
    }

    @Nullable
    private DialogSession begin(Player viewer) {
        return this.dialogs().begin(viewer, SparrowCdkeyPlugin.VIEW_PERMISSION, CdkeyDialogs.MANAGE, CdkeyDialogs.COPY);
    }

    private DialogService dialogs() {
        return SparrowCdkeyPlugin.instance().dialogs();
    }
}
