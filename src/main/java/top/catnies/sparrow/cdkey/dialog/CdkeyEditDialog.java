package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import top.catnies.sparrow.cdkey.CdkeyService;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.model.CdkeyDefinition;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRules;
import top.catnies.sparrow.cdkey.result.CdkeyWriteResult;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class CdkeyEditDialog {

    void create(Player viewer, CdkeyKind kind, Runnable back) {
        ZoneId zone = SparrowCdkeyPlugin.instance().settings().timeZone();
        String now = DialogKit.time(System.currentTimeMillis());
        Draft draft = new Draft(kind, null, zone, "", "", kind == CdkeyKind.CODE ? "-1" : "1", now, now, "");
        this.form(viewer, draft, null, back);
    }

    void edit(Player viewer, CdkeyDefinition definition, Runnable back) {
        CdkeyRules rules = definition.rules();
        Draft draft = new Draft(
                definition.kind(),
                definition,
                SparrowCdkeyPlugin.instance().settings().timeZone(),
                definition.identifier(),
                String.join("\n", rules.commands()),
                Long.toString(definition.kind() == CdkeyKind.CODE ? rules.totalLimit() : rules.playerLimit()),
                DialogKit.time(rules.startsAt()),
                rules.endsAt() == -1 ? "" : DialogKit.time(rules.endsAt()),
                rules.permission()
        );
        this.form(viewer, draft, null, back);
    }

    private void form(Player viewer, Draft draft, @Nullable Component error, Runnable back) {
        if (!viewer.hasPermission(SparrowCdkeyPlugin.VIEW_PERMISSION)) {
            viewer.sendMessage(Messages.DENIED.text().colorIfAbsent(DialogKit.ROSE));
            return;
        }
        DialogSession session = this.dialogs().begin(viewer, CdkeyDialogs.MANAGE);
        if (session == null) return;
        boolean editing = draft.original() != null;
        Component title = editing ? Messages.EDIT.text() : (draft.kind() == CdkeyKind.CODE ? Messages.CREATE_CODE.text() : Messages.CREATE_GIFT.text());
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogKit.text(error.colorIfAbsent(DialogKit.ROSE)));
        if (editing) body.add(DialogKit.text(Component.text(draft.identifier(), DialogKit.AMBER).hoverEvent(Messages.EDIT_HINT.text())));
        body.add(DialogKit.text(DialogKit.field(Messages.ZONE.text(), draft.zone().getId())));
        List<DialogInput> inputs = new ArrayList<>();
        if (!editing) {
            Component identifierLabel = DialogKit.inputLabel(
                    draft.kind() == CdkeyKind.CODE ? Messages.FORM_CODE_LABEL.text() : Messages.FORM_GIFT_LABEL.text(),
                    draft.kind() == CdkeyKind.CODE ? Messages.CODE_HINT.text() : Messages.GIFT_HINT.text()
            );
            inputs.add(this.input("identifier", identifierLabel, draft.identifier(), 128));
        }
        inputs.add(DialogInput.text("commands", DialogKit.inputLabel(Messages.FORM_COMMANDS_LABEL.text(), Messages.COMMAND_HINT.text()))
                .initial(draft.commands())
                .maxLength(8192)
                .width(DialogKit.TEXT_WIDTH)
                .multiline(TextDialogInput.MultilineOptions.create(null, 64))
                .build());
        Component limitLabel = DialogKit.inputLabel(
                draft.kind() == CdkeyKind.CODE ? Messages.FORM_TOTAL_LABEL.text() : Messages.FORM_PLAYER_LABEL.text(),
                draft.kind() == CdkeyKind.CODE ? Messages.CODE_LIMIT_HINT.text() : Messages.GIFT_LIMIT_HINT.text()
        );
        inputs.add(this.input("limit", limitLabel, draft.limit(), 19));
        Component startLabel = DialogKit.inputLabel(Messages.FORM_START_LABEL.text(), Messages.START_HINT.text(draft.zone().getId()));
        Component endLabel = DialogKit.inputLabel(Messages.FORM_END_LABEL.text(), Messages.END_HINT.text(draft.zone().getId()));
        Component permissionLabel = DialogKit.inputLabel(Messages.FORM_PERMISSION_LABEL.text(), Messages.PERMISSION_HINT.text());
        inputs.add(this.input("starts", startLabel, draft.starts(), 16));
        inputs.add(this.input("ends", endLabel, draft.ends(), 16));
        inputs.add(this.input("permission", permissionLabel, draft.permission(), 512));
        ActionButton save = ActionButton.create(
                (editing ? Messages.SAVE.text() : Messages.CREATE.text()),
                null,
                DialogKit.BUTTON_WIDTH,
                session.button(response -> {
                    if (response == null) {
                        this.form(viewer, draft, Messages.INVALID_FORM.text(), back);
                        return;
                    }
                    this.submit(session, draft.read(response), back);
                })
        );
        ActionButton cancel = DialogKit.exit(session, Messages.CANCEL.text(), () -> {
            if (draft.original() == null) {
                back.run();
            } else {
                new CdkeyDialogs().detail(viewer, draft.original().id(), back);
            }
        });
        this.dialogs().show(session, Dialog.create(builder -> builder.empty().base(DialogKit.base(title, body, inputs)).type(DialogType.confirmation(save, cancel))));
    }

    private void submit(DialogSession session, Draft draft, Runnable back) {
        Player viewer = session.viewer();
        CdkeyRules rules;
        try {
            rules = draft.rules();
        } catch (InputError exception) {
            this.form(viewer, draft, exception.message.text(), back);
            return;
        }
        CdkeyService service = SparrowCdkeyPlugin.instance().service();
        CompletableFuture<CdkeyWriteResult> future = draft.original() == null
                ? (draft.kind() == CdkeyKind.CODE ? service.createCode(viewer, draft.identifier(), rules) : service.createGift(viewer, draft.identifier(), rules))
                : service.edit(viewer, draft.original().id(), draft.original().version(), rules);
        this.dialogs().await(
                session,
                future,
                result -> {
                    switch (result.status()) {
                        case SUCCESS -> new CdkeyDialogs().saved(viewer, result.definition(), back);
                        case DUPLICATE_IDENTIFIER -> this.form(viewer, draft, Messages.DUPLICATE.text(), back);
                        case NOT_FOUND -> new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                        case VERSION_CONFLICT -> this.conflict(viewer, draft, back);
                    }
                },
                error -> {
                    if (error instanceof IllegalArgumentException) {
                        if (this.dialogs().active(session)) this.form(viewer, draft, Messages.INVALID_FORM.text(), back);
                    } else {
                        this.dialogs().failure(session, error);
                    }
                }
        );
    }

    private void conflict(Player viewer, Draft draft, Runnable back) {
        DialogSession session = this.dialogs().begin(viewer, CdkeyDialogs.MANAGE);
        if (session == null) return;
        ActionButton reload = DialogKit.button(session, Messages.REOPEN.text(), () -> this.dialogs().await(
                session,
                SparrowCdkeyPlugin.instance().service().find(viewer, draft.original().id()),
                latest -> {
                    if (latest == null) {
                        new CdkeyDialogs().notice(viewer, Messages.NOT_FOUND.text(), back);
                    } else {
                        this.edit(viewer, latest, back);
                    }
                }
        ));
        ActionButton original = DialogKit.button(session, Messages.DRAFT.text(), () -> this.form(viewer, draft, null, back));
        this.dialogs().show(session, DialogKit.panel(session, Messages.CONFLICT_TITLE.text(), List.of(DialogKit.text(Messages.CONFLICT.text())), List.of(reload, original), back));
    }

    private DialogInput input(String key, Component label, String value, int length) {
        return DialogInput.text(key, label)
                .initial(value)
                .width(DialogKit.TEXT_WIDTH)
                .maxLength(length)
                .build();
    }

    private DialogService dialogs() {
        return SparrowCdkeyPlugin.instance().dialogs();
    }

    private record Draft(
            CdkeyKind kind,
            @Nullable CdkeyDefinition original,
            ZoneId zone,
            String identifier,
            String commands,
            String limit,
            String starts,
            String ends,
            String permission
    ) {

        private Draft read(DialogResponseView response) {
            return new Draft(
                    this.kind,
                    this.original,
                    this.zone,
                    this.original == null ? this.value(response, "identifier") : this.identifier,
                    this.value(response, "commands"),
                    this.value(response, "limit"),
                    this.value(response, "starts"),
                    this.value(response, "ends"),
                    this.value(response, "permission")
            );
        }

        private String value(DialogResponseView response, String key) {
            String value = response.getText(key);
            return value == null ? "" : value;
        }

        private CdkeyRules rules() {
            long count;
            try {
                count = Long.parseLong(this.limit.strip());
                if (count < -1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new InputError(Messages.INVALID_LIMIT);
            }
            long start = this.date(this.starts, this.original == null ? null : this.original.rules().startsAt(), System.currentTimeMillis());
            long end = this.date(this.ends, this.original == null ? null : this.original.rules().endsAt(), -1);
            if (end != -1 && end <= start) throw new InputError(Messages.INVALID_RANGE);
            return new CdkeyRules(List.of(this.commands), start, end, this.kind == CdkeyKind.CODE ? count : -1, this.kind == CdkeyKind.CODE ? 1 : count, this.permission);
        }

        private long date(String value, @Nullable Long original, long empty) {
            String input = value.strip();
            if (input.isEmpty()) return empty;
            // 分钟精度的原文未修改时保留数据库里的毫秒精度.
            if (original != null && original != -1 && DialogKit.TIME.format(Instant.ofEpochMilli(original).atZone(this.zone)).equals(input)) return original;
            try {
                LocalDateTime local = LocalDateTime.parse(input, DialogKit.TIME);
                if (this.zone.getRules().getValidOffsets(local).isEmpty()) throw new InputError(Messages.INVALID_DATE);
                return local.atZone(this.zone).toInstant().toEpochMilli();
            } catch (DateTimeException | ArithmeticException exception) {
                throw new InputError(Messages.INVALID_DATE);
            }
        }
    }

    private static final class InputError extends IllegalArgumentException {
        private final Messages message;

        private InputError(Messages message) {
            this.message = message;
        }
    }
}
