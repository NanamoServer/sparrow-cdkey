package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.Nullable;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.model.CdkeyKind;
import top.catnies.sparrow.cdkey.model.CdkeyRules;
import top.catnies.sparrow.cdkey.request.CdkeyDefinitionQuery.TimeState;
import top.catnies.sparrow.cdkey.result.CdkeyPage;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;

final class DialogKit {
    static final TextColor AMBER = TextColor.color(0xFFE3A3);
    static final TextColor MINT = TextColor.color(0xA5DFB2);
    static final TextColor ROSE = TextColor.color(0xFFB6BD);
    static final int TEXT_WIDTH = 300;
    static final int BUTTON_WIDTH = 150;
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private DialogKit() {
    }

    static DialogBody text(Component text) {
        return DialogBody.plainMessage(text, TEXT_WIDTH);
    }

    static DialogBody text(String text) {
        return text(Component.text(text));
    }

    static Component field(Component label, Object value) {
        return Messages.FIELD.text(label, value);
    }

    static Component inputLabel(Component label, Component help) {
        return label.hoverEvent(help);
    }

    static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(SparrowCdkeyPlugin.instance().settings().timeZone()));
    }

    static Component unlimited(long value) {
        return value == -1 ? Messages.UNLIMITED.text() : Component.text(value);
    }

    static Component rules(CdkeyRules rules, CdkeyKind kind) {
        Component limitLabel = kind == CdkeyKind.CODE ? Messages.TOTAL_LIMIT.text() : Messages.PLAYER_LIMIT.text();
        long limit = kind == CdkeyKind.CODE ? rules.totalLimit() : rules.playerLimit();
        return Component.join(
                JoinConfiguration.newlines(),
                List.of(
                        field(Messages.START.text(), time(rules.startsAt())),
                        field(Messages.END.text(), rules.endsAt() == -1 ? Messages.UNLIMITED.text() : time(rules.endsAt())),
                        field(Messages.ZONE.text(), SparrowCdkeyPlugin.instance().settings().timeZone().getId()),
                        field(limitLabel, unlimited(limit)),
                        field(Messages.PERMISSION.text(), rules.permission().isEmpty() ? Messages.NONE.text() : rules.permission())
                )
        );
    }

    static DialogBody rows(List<Component> lines) {
        return text(lines.isEmpty() ? Messages.EMPTY.text() : Component.join(JoinConfiguration.newlines(), lines));
    }

    static int last(CdkeyPage<?> page) {
        return (int) Math.max(1, (page.total() + page.pageSize() - 1) / page.pageSize());
    }

    static Component summary(CdkeyPage<?> page) {
        return Messages.PAGE.text(page.page(), last(page), page.total());
    }

    static Component state(CdkeyRules rules, long now) {
        return stateName(now < rules.startsAt() ? TimeState.UPCOMING : (rules.endsAt() != -1 && now >= rules.endsAt() ? TimeState.EXPIRED : TimeState.ACTIVE));
    }

    static Component stateName(@Nullable TimeState state) {
        return switch (state) {
            case null -> Messages.ALL.text();
            case UPCOMING -> Messages.UPCOMING.text();
            case ACTIVE -> Messages.ACTIVE.text();
            case EXPIRED -> Messages.EXPIRED.text();
        };
    }

    static Component copyable(String value) {
        return Component.text(value, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(value))
                .hoverEvent(Component.text(value).append(Component.newline()).append(Messages.COPY_HINT.text()));
    }

    static ActionButton copyButton(Component label, List<String> codes) {
        return ActionButton.create(label, null, BUTTON_WIDTH, DialogAction.staticAction(ClickEvent.copyToClipboard(String.join("\n", codes))));
    }

    static ActionButton button(DialogSession session, Component label, Runnable action) {
        return ActionButton.create(label, null, BUTTON_WIDTH, session.button(response -> action.run()));
    }

    static ActionButton exit(DialogSession session, Component label, Runnable action) {
        return ActionButton.create(label, null, BUTTON_WIDTH, session.exit(response -> action.run()));
    }

    static DialogBase base(Component title, List<DialogBody> body, List<DialogInput> inputs) {
        return DialogBase.builder(title)
                .body(body)
                .inputs(inputs)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .build();
    }

    static Dialog panel(DialogSession session, Component title, List<DialogBody> body, List<ActionButton> actions, @Nullable Runnable back) {
        ActionButton exit = ActionButton.create(
                (back == null ? Messages.CLOSE.text() : Messages.BACK.text()),
                null,
                200,
                session.exit(response -> {
                    if (back == null) {
                        SparrowCdkeyPlugin.instance().dialogs().finish(session);
                    } else {
                        back.run();
                    }
                })
        );
        DialogType type = actions.isEmpty() ? DialogType.notice(exit) : DialogType.multiAction(actions, exit, 2);
        return Dialog.create(builder -> builder.empty().base(base(title, body, List.of())).type(type));
    }
}
