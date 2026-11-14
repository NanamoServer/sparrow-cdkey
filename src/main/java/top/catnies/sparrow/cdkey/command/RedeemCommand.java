package top.catnies.sparrow.cdkey.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import top.catnies.sparrow.cdkey.SparrowCdkeyPlugin;
import top.catnies.sparrow.cdkey.dialog.Messages;
import top.catnies.sparrow.cdkey.dialog.RedeemDialog;

import java.util.Locale;

@ApiStatus.Internal
public final class RedeemCommand {

    @NotNull
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("redeem")
                .requires(source -> source.getSender().hasPermission(SparrowCdkeyPlugin.REDEEM_PERMISSION))
                .executes(context -> {
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        context.getSource().getSender().sendMessage(Messages.PLAYER_ONLY.text());
                        return 0;
                    }
                    new RedeemDialog().open(player);
                    return 1;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(source -> source.getSender().hasPermission(SparrowCdkeyPlugin.REDEEM_OTHER_PERMISSION))
                        .suggests((context, builder) -> {
                            String prefix = builder.getRemainingLowerCase();
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(player.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            Player target = Bukkit.getPlayerExact(StringArgumentType.getString(context, "player"));
                            if (target == null) {
                                context.getSource().getSender().sendMessage(Messages.PLAYER_OFFLINE.text());
                                return 0;
                            }
                            new RedeemDialog().open(target);
                            return 1;
                        }))
                .build();
    }
}
