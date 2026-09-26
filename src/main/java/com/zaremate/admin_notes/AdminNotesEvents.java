package com.zaremate.admin_notes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AdminNotesEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Object DATA_LOCK = new Object();
    static final Map<String, PlayerNotes> PLAYERS = new LinkedHashMap<>();
    static Path dataFile;

    private AdminNotesEvents() {}

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("note")
                .requires(AdminNotesEvents::hasAnyPermission)
                .then(Commands.literal("add")
                        .requires(source -> hasPermission(source, AdminNotesConfig.ADD_PERMISSION.get()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> addNote(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "text")
                                        )))))
                .then(Commands.literal("rm")
                        .requires(source -> hasPermission(source, AdminNotesConfig.REMOVE_PERMISSION.get()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> removeNote(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("clear")
                        .requires(source -> hasPermission(source, AdminNotesConfig.CLEAR_PERMISSION.get()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> clearNotes(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(source -> hasPermission(source, AdminNotesConfig.READ_PERMISSION.get()))
                        .executes(ctx -> showNotes(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"))));

        event.getDispatcher().register(root);
    }

    private static boolean hasPermission(CommandSourceStack source, String permission) {
        return source.hasPermission(3) || LuckPermsPermissions.hasPermission(source, permission);
    }

    private static boolean hasAnyPermission(CommandSourceStack source) {
        return hasPermission(source, AdminNotesConfig.READ_PERMISSION.get())
                || hasPermission(source, AdminNotesConfig.ADD_PERMISSION.get())
                || hasPermission(source, AdminNotesConfig.REMOVE_PERMISSION.get())
                || hasPermission(source, AdminNotesConfig.CLEAR_PERMISSION.get());
    }

    private static int addNote(CommandSourceStack source, String targetName, String text) {
        if (!(source.getEntity() instanceof ServerPlayer admin)) return 0;

        String noteText = text.trim();
        if (noteText.isEmpty()) {
            source.sendFailure(Component.literal("Note cannot be empty."));
            return 0;
        }

        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        synchronized (DATA_LOCK) {
            PlayerNotes player = getOrCreate(target.uuid(), target.name());
            player.name = target.name();
            player.notes.put(admin.getUUID().toString(), new AdminNotesAPI.Note(
                    admin.getGameProfile().getName(),
                    noteText,
                    System.currentTimeMillis()
            ));
            saveData();
        }

        source.sendSuccess(() -> Component.literal("Note saved for " + target.name()), false);
        return 1;
    }

    private static int removeNote(CommandSourceStack source, String targetName) {
        if (!(source.getEntity() instanceof ServerPlayer admin)) return 0;

        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        synchronized (DATA_LOCK) {
            PlayerNotes notes = PLAYERS.get(target.uuid().toString());
            if (notes == null || notes.notes == null) {
                source.sendFailure(Component.literal("No notes found for " + target.name() + "."));
                return 0;
            }

            if (notes.notes.remove(admin.getUUID().toString()) == null) {
                source.sendFailure(Component.literal("You do not have a note for " + notes.name + "."));
                return 0;
            }

            saveData();
        }

        source.sendSuccess(() -> Component.literal("Your note for " + target.name() + " was removed."), false);
        return 1;
    }

    private static int clearNotes(CommandSourceStack source, String targetName) {
        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        synchronized (DATA_LOCK) {
            PlayerNotes notes = PLAYERS.get(target.uuid().toString());
            if (notes == null || notes.notes == null) {
                source.sendFailure(Component.literal("No notes found for " + target.name() + "."));
                return 0;
            }

            int removed = notes.notes.size();
            notes.notes.clear();
            saveData();

            source.sendSuccess(() -> Component.literal(
                    "Cleared " + removed + " note(s) for " + notes.name), false);
        }
        return 1;
    }

    private static int showNotes(CommandSourceStack source, String targetName) {
        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        PlayerNotes notes;
        synchronized (DATA_LOCK) {
            notes = PLAYERS.get(target.uuid().toString());
            if (notes == null) {
                source.sendFailure(Component.literal("No notes found for " + target.name() + "."));
                return 0;
            }
        }

        source.sendSuccess(() -> Component.literal("────────────────────────────────────").withColor(0x555555), false);
        source.sendSuccess(() -> Component.literal("PLAYER NOTES").withStyle(s -> s.withColor(0xFFAA00).withBold(true)), false);
        source.sendSuccess(() -> Component.literal("Player: ").append(Component.literal(notes.name).withColor(0xFFFFFF)), false);

        int count = 0;
        for (AdminNotesAPI.Note note : notes.notes.values()) {
            count++;
            source.sendSuccess(() -> Component.literal("[" + note.author + "]").withStyle(s -> s.withColor(0x55FFFF).withBold(true)), false);
            source.sendSuccess(() -> Component.literal(note.text), false);
            source.sendSuccess(() -> Component.literal(formatDate(note.createdAt)).withStyle(s -> s.withColor(0x555555)), false);
            source.sendSuccess(() -> Component.literal(""), false);
        }

        if (count == 0) {
            source.sendSuccess(() -> Component.literal("No notes have been added for this player."), false);
        }

        source.sendSuccess(() -> Component.literal("────────────────────────────────────").withColor(0x555555), false);
        return 1;
    }

    static PlayerNotes getOrCreate(UUID uuid, String name) {
        return PLAYERS.computeIfAbsent(uuid.toString(), ignored -> new PlayerNotes(name));
    }

    private static ResolvedPlayer resolvePlayer(CommandSourceStack source, String name) {
        String target = name == null ? "" : name.trim();
        if (target.isEmpty()) return null;

        var server = source != null ? source.getServer() : null;
        if (server == null) return null;

        ServerPlayer online = server.getPlayerList().getPlayerByName(target);
        if (online != null) {
            return new ResolvedPlayer(online.getUUID(), online.getGameProfile().getName());
        }

        synchronized (DATA_LOCK) {
            for (Map.Entry<String, PlayerNotes> entry : PLAYERS.entrySet()) {
                PlayerNotes player = entry.getValue();
                if (player == null || player.name == null || !player.name.equalsIgnoreCase(target)) continue;

                try {
                    return new ResolvedPlayer(UUID.fromString(entry.getKey()), player.name);
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("Ignoring invalid player UUID '{}' in admin notes.", entry.getKey());
                }
            }
        }

        var cache = server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(target);
            if (profile.isPresent()) {
                return new ResolvedPlayer(profile.get().getId(), profile.get().getName());
            }
        }

        return null;
    }

    private static void loadData() {
        try {
            Path worldDir = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT);
            dataFile = worldDir.resolve("admin_notes.json");

            if (Files.notExists(dataFile)) {
                saveData();
                return;
            }

            String json = Files.readString(dataFile, StandardCharsets.UTF_8);
            Storage storage = GSON.fromJson(json, Storage.class);

            PLAYERS.clear();
            if (storage != null && storage.players != null) {
                PLAYERS.putAll(storage.players);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load admin notes.", e);
        }
    }

    static void saveData() {
        if (dataFile == null) {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            dataFile = server.getWorldPath(LevelResource.ROOT).resolve("admin_notes.json");
        }

        try {
            Storage storage = new Storage();
            storage.version = 1;
            storage.players.putAll(PLAYERS);

            Path parent = dataFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(dataFile, GSON.toJson(storage), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save admin notes.", e);
        }
    }

    private static String formatDate(long timestamp) {
        if (timestamp <= 0) return "Unknown";

        try {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(timestamp));
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private static final class Storage {
        int version = 1;
        Map<String, PlayerNotes> players = new LinkedHashMap<>();
    }

    static final class PlayerNotes {
        String name;
        Map<String, AdminNotesAPI.Note> notes = new LinkedHashMap<>();

        PlayerNotes(String name) {
            this.name = name;
        }
    }
    private record ResolvedPlayer(UUID uuid, String name) {}

    static void initializeData(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;

        synchronized (DATA_LOCK) {
            dataFile = server.getWorldPath(LevelResource.ROOT).resolve("admin_notes.json");
            loadData();
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        initializeData(event.getServer());
    }
}