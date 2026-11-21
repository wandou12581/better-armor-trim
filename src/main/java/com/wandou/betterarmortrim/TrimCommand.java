package com.wandou.betterarmortrim;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class TrimCommand {
    private TrimCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("triminfo").executes(context -> {
            var player = context.getSource().getPlayerOrException();
            for (Component line : BetterArmorTrim.engine().describe(player)) {
                context.getSource().sendSuccess(() -> line, false);
            }
            return 1;
        }));

        dispatcher.register(Commands.literal("batrim")
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            BetterArmorTrim.reload();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("更好的盔甲纹饰：配置已重载"), true);
                            return 1;
                        })));
    }
}
