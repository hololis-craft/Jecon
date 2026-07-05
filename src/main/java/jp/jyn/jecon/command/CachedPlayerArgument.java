package jp.jyn.jecon.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.MessageConfig;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("UnstableApiUsage")
final class CachedPlayerArgument {
    private static final int SUGGESTION_LIMIT = 20;

    private CachedPlayerArgument() {
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> player(Jecon plugin) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> suggest(plugin, builder));
    }

    static Optional<Target> resolve(CommandContext<CommandSourceStack> ctx) {
        Jecon plugin = Jecon.getInstance();
        String input = StringArgumentType.getString(ctx, "player");
        return plugin.getDb().resolveAlias(input)
                .map(uuid -> new Target(uuid, input));
    }

    static int notFound(CommandContext<CommandSourceStack> ctx, MessageConfig message) {
        String input = StringArgumentType.getString(ctx, "player");
        ctx.getSource().getSender().sendMessage(message.playerNotFound.toComponent("name", input));
        return 0;
    }

    private static CompletableFuture<Suggestions> suggest(Jecon plugin, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        plugin.getDb().suggestAliases(remaining, SUGGESTION_LIMIT).forEach(builder::suggest);
        return builder.buildFuture();
    }

    record Target(UUID uuid, String name) {
    }
}
