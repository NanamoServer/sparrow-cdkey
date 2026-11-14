package top.catnies.sparrow.cdkey.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;

import java.util.List;

@ApiStatus.Internal
public final class RedeemDialog {

    public void open(@NotNull Player player) {
        DialogService dialogs = SparrowCdkeyPlugin.instance().dialogs();
        DialogSession session = dialogs.begin(player, SparrowCdkeyPlugin.REDEEM_PERMISSION);
        if (session == null) return;
        DialogInput input = DialogInput.text("code", Messages.REDEEM_INPUT.text()).width(DialogKit.TEXT_WIDTH).maxLength(128).build();
        ActionButton submit = ActionButton.create(
                Messages.SUBMIT.text(),
                null,
                DialogKit.BUTTON_WIDTH,
                session.button(response -> {
                    dialogs.finish(session);
                    String code = response == null ? null : response.getText("code");
                    dialogs.awaitRedemption(
                            player,
                            SparrowCdkeyPlugin.instance().service().redeem(player, code == null ? "" : code)
                    );
                })
        );
        ActionButton cancel = DialogKit.exit(session, Messages.CANCEL.text(), () -> dialogs.finish(session));
        dialogs.show(
                session,
                Dialog.create(builder -> builder.empty()
                .base(DialogKit.base(Messages.REDEEM.text(), List.of(DialogKit.text(Messages.REDEEM_HINT.text())), List.of(input)))
                .type(DialogType.confirmation(submit, cancel)))
        );
    }
}
