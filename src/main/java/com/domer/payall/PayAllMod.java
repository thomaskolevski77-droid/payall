package com.domer.payall;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class PayAllMod implements ClientModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern NUM = Pattern.compile("(?:\\$|€|£|¥)?\\s*([0-9][0-9.,]*)");

    public static class Cfg {
        public String target = ".pro_gamerst1206";
        public String balanceCommand = "bal";
        public int joinDelayTicks = 60;
        public int replyTimeoutTicks = 100;
        public boolean enabled = true;
        public boolean dryRun = false;
        public boolean oncePerSession = true;
        public int cooldownSeconds = 600;
        public double minAmount = 1.0;
    }

    private static Cfg config = new Cfg();
    private static Path configPath;

    private enum State { IDLE, WAITING }

    private static State state = State.IDLE;
    private static int ticksSinceJoin = -1;
    private static int waitTicks = 0;
    private static boolean paidThisSession = false;
    private static long lastPayEpochMs = 0L;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("payall.json");
        loadConfig();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ticksSinceJoin = 0;
            state = State.IDLE;
            paidThisSession = false;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ticksSinceJoin = -1;
            state = State.IDLE;
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));

        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            String s = message.getString().toLowerCase(Locale.ROOT);
            if (s.contains("balance") || s.contains("bal:")) onMessage(s);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getNetworkHandler() == null) return;
            if (!config.enabled || ticksSinceJoin < 0) return;

            ticksSinceJoin++;

            if (state == State.IDLE && ticksSinceJoin == config.joinDelayTicks) {
                if (config.oncePerSession && paidThisSession) return;
                if (cooldownActive()) {
                    msg(client, "§7[PayAll] on cooldown, skipping.");
                    return;
                }
                startBalanceCheck(client);
            }

            if (state == State.WAITING) {
                if (++waitTicks > config.replyTimeoutTicks) {
                    state = State.IDLE;
                    msg(client, "§c[PayAll] no balance reply, gave up.");
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            literal("payall")
                .executes(ctx -> { manual(ctx.getSource().getClient(), false); return 1; })
                .then(literal("now").executes(ctx -> { manual(ctx.getSource().getClient(), true); return 1; }))
                .then(literal("dry").executes(ctx -> {
                    config.dryRun = !config.dryRun;
                    saveConfig();
                    msg(ctx.getSource().getClient(), "§e[PayAll] dryRun = " + config.dryRun);
                    return 1;
                }))
                .then(literal("target").then(argument("name", word()).executes(ctx -> {
                    config.target = getString(ctx, "name");
                    saveConfig();
                    msg(ctx.getSource().getClient(), "§e[PayAll] target = " + config.target);
                    return 1;
                })))
                .then(literal("reload").executes(ctx -> {
                    loadConfig();
                    msg(ctx.getSource().getClient(), "§e[PayAll] config reloaded.");
                    return 1;
                }))
        ));
    }

    private static boolean cooldownActive() {
        return System.currentTimeMillis() - lastPayEpochMs < config.cooldownSeconds * 1000L;
    }

    private static void manual(MinecraftClient client, boolean force) {
        if (client.player == null) return;
        if (force) lastPayEpochMs = 0L;
        startBalanceCheck(client);
    }

    private static void startBalanceCheck(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) return;
        state = State.WAITING;
        waitTicks = 0;
        msg(client, "§7[PayAll] reading balance...");
        client.player.networkHandler.sendChatCommand(config.balanceCommand);
    }

    private static void onMessage(String raw) {
        if (state != State.WAITING) return;
        Double amount = parseAmount(raw);
        if (amount == null) return;

        state = State.IDLE;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;

        if (amount < config.minAmount) {
            msg(client, "§7[PayAll] balance " + fmt(amount) + " too low, skipping.");
            return;
        }

        String cmd = "pay " + config.target + " " + fmt(amount);
        if (config.dryRun) {
            msg(client, "§b[PayAll] would run: /" + cmd);
            return;
        }

        client.player.networkHandler.sendChatCommand(cmd);
        paidThisSession = true;
        lastPayEpochMs = System.currentTimeMillis();
        msg(client, "§a[PayAll] sent /" + cmd);
    }

    static Double parseAmount(String raw) {
        String s = raw.replaceAll("§.", "");
        Matcher m = NUM.matcher(s);
        Double best = null;
        while (m.find()) {
            String tok = m.group(1).replaceAll("[.,]+$", "");
            if (tok.isEmpty()) continue;
            String cleaned;
            if (tok.contains(".") && tok.contains(",")) {
                cleaned = tok.replace(",", "");
            } else if (tok.contains(",")) {
                cleaned = tok.matches("[0-9]{1,3}(,[0-9]{3})+") ? tok.replace(",", "") : tok.replace(",", ".");
            } else {
                cleaned = tok;
            }
            try {
                double v = Double.parseDouble(cleaned);
                if (best == null || v > best) best = v;
            } catch (NumberFormatException ignored) {}
        }
        return best;
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void msg(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), false);
    }

    private static void loadConfig() {
        try {
            if (configPath != null && Files.exists(configPath)) {
                Cfg c = GSON.fromJson(Files.readString(configPath), Cfg.class);
                if (c != null) config = c;
            }
        } catch (Exception e) {
            config = new Cfg();
        }
    }

    private static void saveConfig() {
        try {
            if (configPath != null) Files.writeString(configPath, GSON.toJson(config));
        } catch (Exception ignored) {}
    }
}
