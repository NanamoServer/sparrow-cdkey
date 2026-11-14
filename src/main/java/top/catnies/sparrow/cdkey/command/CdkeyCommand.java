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
import top.catnies.sparrow.cdkey.dialog.CdkeyDialogs;
import top.catnies.sparrow.cdkey.dialog.Messages;

import java.util.Locale;

@ApiStatus.Internal
public final class CdkeyCommand {

    @NotNull
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("sparrow-cdkey")
                .requires(source -> source.getSender().hasPermission(SparrowCdkeyPlugin.VIEW_PERMISSION))
                .executes(context -> {
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        context.getSource().getSender().sendMessage(Messages.PLAYER_ONLY.text());
                        return 0;
                    }
                    new CdkeyDialogs().home(player);
                    return 1;
                })
                .then(Commands.literal("history")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    String prefix = builder.getRemainingLowerCase();
                                    for (Player player : Bukkit.getOnlinePlayers()) {
                                        if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(player.getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    if (!(context.getSource().getSender() instanceof Player player)) {
                                        context.getSource().getSender().sendMessage(Messages.PLAYER_ONLY.text());
                                        return 0;
                                    }
                                    new CdkeyDialogs().history(player, StringArgumentType.getString(context, "player"));
                                    return 1;
                                })))
                .build();
    }
}
