package com.zaremate.admin_notes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
                                .suggests(AdminNotesEvents::suggestPlayers)
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> addNote(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "text")
                                        )))))
                .then(Commands.literal("rm")
                        .requires(source -> hasPermission(source, AdminNotesConfig.REMOVE_PERMISSION.get()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(AdminNotesEvents::suggestPlayers)
                                .then(Commands.argument("noteId", UuidArgument.uuid())
                                        .executes(ctx -> removeNote(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                UuidArgument.getUuid(ctx, "noteId")
                                        )))
                                .executes(ctx -> removeLatestOwnNote(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")
                                ))))
                .then(Commands.literal("clear")
                        .requires(source -> hasPermission(source, AdminNotesConfig.CLEAR_PERMISSION.get()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(AdminNotesEvents::suggestPlayers)
                                .executes(ctx -> clearNotes(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.argument("playerOrId", StringArgumentType.word())
                        .suggests(AdminNotesEvents::suggestRootPlayersAndNoteIds)
                        .requires(source -> hasPermission(source, AdminNotesConfig.READ_PERMISSION.get()))
                        .executes(ctx -> showNotesOrSearch(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "playerOrId"))));

        event.getDispatcher().register(root);
    }

    private static CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        Set<String> names = new HashSet<>();

        var server = context.getSource().getServer();
        if (server == null) {
            return builder.buildFuture();
        }

        // Online players are always available for autocomplete.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getGameProfile().getName());
        }

        // Previously known players are suggested too, including offline players.
        synchronized (DATA_LOCK) {
            for (PlayerNotes player : PLAYERS.values()) {
                if (player != null && player.name != null && !player.name.isBlank()) {
                    names.add(player.name);
                }
            }
        }

        names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(remaining))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(builder::suggest);

        // Player UUIDs use the same UUID-prefix autocomplete behavior as note IDs.
        boolean looksLikeUuidPrefix = remaining.matches("[0-9a-f]{1,8}(-[0-9a-f]{0,4})?");
        if (looksLikeUuidPrefix) {
            Set<String> playerUuids = new HashSet<>();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                playerUuids.add(player.getUUID().toString());
            }

            synchronized (DATA_LOCK) {
                playerUuids.addAll(PLAYERS.keySet());
            }

            playerUuids.stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(remaining))
                    .sorted()
                    .forEach(builder::suggest);
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRootPlayersAndNoteIds(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

        // Only offer note UUIDs when the input already looks like the beginning
        // of a UUID. Otherwise autocomplete remains focused on player names.
        boolean looksLikeUuidPrefix = remaining.matches("[0-9a-f]{1,8}(-[0-9a-f]{0,4})?");

        Set<String> names = new HashSet<>();
        var server = context.getSource().getServer();
        if (server == null) {
            return builder.buildFuture();
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getGameProfile().getName());
        }

        synchronized (DATA_LOCK) {
            for (PlayerNotes player : PLAYERS.values()) {
                if (player != null && player.name != null && !player.name.isBlank()) {
                    names.add(player.name);
                }
            }
        }

        // Player names first.
        names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(remaining))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(builder::suggest);

        // Player UUIDs and note UUIDs appear after a UUID-looking prefix
        // has been entered.
        if (looksLikeUuidPrefix) {
            Set<String> playerUuids = new HashSet<>();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                playerUuids.add(player.getUUID().toString());
            }

            synchronized (DATA_LOCK) {
                playerUuids.addAll(PLAYERS.keySet());

                playerUuids.stream()
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(remaining))
                        .sorted()
                        .forEach(builder::suggest);

                PLAYERS.values().stream()
                        .filter(player -> player != null && player.notes != null)
                        .flatMap(player -> player.notes.stream())
                        .filter(note -> note != null && note.id() != null)
                        .map(note -> note.id().toString())
                        .filter(id -> id.startsWith(remaining))
                        .distinct()
                        .sorted()
                        .forEach(builder::suggest);
            }
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPlayersAndNoteIds(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        return suggestPlayers(context, builder);
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

        String noteText = decodeEscapes(text).trim();
        if (noteText.isEmpty()) {
            source.sendFailure(Component.literal("Note cannot be empty."));
            return 0;
        }

        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        AdminNotesAPI.addNote(
                target.uuid(),
                admin.getUUID(),
                admin.getGameProfile().getName(),
                noteText
        );

        source.sendSuccess(() -> Component.literal("Note added for " + target.name()), false);
        return 1;
    }

    private static int removeLatestOwnNote(CommandSourceStack source, String targetName) {
        if (!(source.getEntity() instanceof ServerPlayer admin)) return 0;

        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        synchronized (DATA_LOCK) {
            PlayerNotes notes = PLAYERS.get(target.uuid().toString());

            if (notes == null || notes.notes == null || notes.notes.isEmpty()) {
                source.sendFailure(Component.literal("No notes found for " + target.name() + "."));
                return 0;
            }

            for (int i = notes.notes.size() - 1; i >= 0; i--) {
                AdminNotesAPI.Note note = notes.notes.get(i);

                if (!admin.getUUID().equals(note.authorUuid())) {
                    continue;
                }

                notes.notes.remove(i);
                saveData();

                source.sendSuccess(() -> Component.literal(
                        "Your latest note for " + target.name() + " was removed."
                ), false);
                return 1;
            }
        }

        source.sendFailure(Component.literal("You do not have any notes for " + target.name() + "."));
        return 0;
    }

    private static int removeNote(
            CommandSourceStack source,
            String targetName,
            UUID noteId
    ) {
        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        boolean removed = AdminNotesAPI.removeNote(target.uuid(), noteId);

        if (!removed) {
            source.sendFailure(Component.literal("Note not found: " + noteId));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Note removed from " + target.name()
        ), false);
        return 1;
    }

    private static int clearNotes(CommandSourceStack source, String targetName) {
        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        int removed = AdminNotesAPI.clearNotes(target.uuid());

        source.sendSuccess(() -> Component.literal(
                "Cleared " + removed + " note(s) for " + target.name()
        ), false);
        return 1;
    }

    private static int showNotesOrSearch(CommandSourceStack source, String target) {
        UUID noteId = parseUuid(target);
        if (noteId != null) {
            return showNoteById(source, noteId);
        }
        return showNotes(source, target);
    }

    private static int showNotes(CommandSourceStack source, String targetName) {
        ResolvedPlayer target = resolvePlayer(source, targetName);
        if (target == null) {
            source.sendFailure(Component.literal("Player not found: " + targetName));
            return 0;
        }

        List<AdminNotesAPI.Note> notes = AdminNotesAPI.getNotes(target.uuid());

        source.sendSuccess(() -> Component.literal(
                "───────────────────────────────────"
        ).withColor(0x555555), false);

        source.sendSuccess(() -> Component.literal(
                "PLAYER NOTES"
        ).withStyle(s -> s.withColor(0xFFAA00).withBold(true)), false);

        source.sendSuccess(() -> Component.literal("Player: ")
                .append(Component.literal(target.name()).withColor(0xFFFFFF)), false);

        if (notes.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No notes have been added for this player."
            ), false);
        } else {
            for (AdminNotesAPI.Note note : notes) {
                String author = note.isSystem() ? "SYSTEM" : note.author();

                source.sendSuccess(() -> Component.literal(
                        "[" + author + "]"
                ).withStyle(s -> s
                        .withColor(note.isSystem() ? 0xFF5555 : 0x55FFFF)
                        .withBold(true)), false);

                sendMultiline(source, note.text());

                source.sendSuccess(() -> Component.literal(
                        formatDate(note.createdAt())
                ).withStyle(s -> s.withColor(0x555555)), false);

                source.sendSuccess(() -> clickableNoteId(note.id()), false);

                source.sendSuccess(() -> Component.literal(""), false);
            }
        }

        source.sendSuccess(() -> Component.literal(
                "───────────────────────────────────"
        ).withColor(0x555555), false);

        return 1;
    }

    private static int showNoteById(CommandSourceStack source, UUID noteId) {
        synchronized (DATA_LOCK) {
            for (Map.Entry<String, PlayerNotes> entry : PLAYERS.entrySet()) {
                PlayerNotes player = entry.getValue();
                if (player == null || player.notes == null) continue;

                for (AdminNotesAPI.Note note : player.notes) {
                    if (!note.id().equals(noteId)) continue;

                    String playerName = player.name == null || player.name.isBlank()
                            ? entry.getKey()
                            : player.name;
                    String author = note.isSystem() ? "SYSTEM" : note.author();

                    source.sendSuccess(() -> Component.literal(
                            "───────────────────────────────────"
                    ).withColor(0x555555), false);
                    source.sendSuccess(() -> Component.literal(
                            "NOTE FOUND"
                    ).withStyle(s -> s.withColor(0xFFAA00).withBold(true)), false);
                    source.sendSuccess(() -> Component.literal("Player: ")
                            .append(Component.literal(playerName).withColor(0xFFFFFF)), false);
                    source.sendSuccess(() -> Component.literal("Author: ")
                            .append(Component.literal(author)
                                    .withColor(note.isSystem() ? 0xFF5555 : 0x55FFFF)), false);
                    source.sendSuccess(() -> Component.literal("Text:"), false);
                    sendMultiline(source, note.text());
                    source.sendSuccess(() -> Component.literal("Created: ")
                            .append(Component.literal(formatDate(note.createdAt())).withColor(0x555555)), false);
                    source.sendSuccess(() -> clickableNoteId(note.id()), false);
                    source.sendSuccess(() -> Component.literal(
                            "───────────────────────────────────"
                    ).withColor(0x555555), false);
                    return 1;
                }
            }
        }

        source.sendFailure(Component.literal("Note not found: " + noteId));
        return 0;
    }

    private static void sendMultiline(CommandSourceStack source, String text) {
        if (text == null) {
            source.sendSuccess(() -> Component.literal(""), false);
            return;
        }

        for (String line : decodeEscapes(text).split("\\n", -1)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    /**
     * Converts common escaped sequences into their actual characters.
     *
     * Supported:
     * \\n  newline
     * \\r  carriage return
     * \\t  tab
     * \\b  backspace
     * \\f  form feed
     * \\\\  literal backslash
     * \\uXXXX  Unicode character
     *
     * Unknown escapes keep the backslash, so URLs and other text are not
     * accidentally changed.
     */
    private static String decodeEscapes(String text) {
        StringBuilder result = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c != '\\' || i + 1 >= text.length()) {
                result.append(c);
                continue;
            }

            char next = text.charAt(++i);

            switch (next) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case '\\' -> result.append('\\');
                case 'u' -> {
                    if (i + 4 < text.length()) {
                        String hex = text.substring(i + 1, i + 5);
                        try {
                            result.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            result.append('\\').append('u');
                        }
                    } else {
                        result.append('\\').append('u');
                    }
                }
                default -> result.append('\\').append(next);
            }
        }

        return result.toString();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Component clickableNoteId(UUID noteId) {
        String id = noteId.toString();
        return Component.literal("ID: " + id)
                .withStyle(style -> style
                        .withColor(0x777777)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to copy note ID")
                        )));
    }

    static PlayerNotes getOrCreate(UUID uuid, String name) {
        return PLAYERS.computeIfAbsent(uuid.toString(), ignored -> new PlayerNotes(name));
    }

    private static ResolvedPlayer resolvePlayer(CommandSourceStack source, String name) {
        String target = name == null ? "" : name.trim();
        if (target.isEmpty()) return null;

        var server = source != null ? source.getServer() : null;
        if (server == null) return null;

        // Accept a player UUID anywhere a player name is accepted.
        UUID targetUuid = parseUuid(target);
        if (targetUuid != null) {
            ServerPlayer onlineByUuid = server.getPlayerList().getPlayer(targetUuid);
            if (onlineByUuid != null) {
                return new ResolvedPlayer(
                        onlineByUuid.getUUID(),
                        onlineByUuid.getGameProfile().getName()
                );
            }

            synchronized (DATA_LOCK) {
                PlayerNotes stored = PLAYERS.get(targetUuid.toString());
                if (stored != null) {
                    String storedName = stored.name == null || stored.name.isBlank()
                            ? targetUuid.toString()
                            : stored.name;
                    return new ResolvedPlayer(targetUuid, storedName);
                }
            }

            var profileCache = server.getProfileCache();
            if (profileCache != null) {
                var profile = profileCache.get(targetUuid);
                if (profile.isPresent()) {
                    return new ResolvedPlayer(
                            profile.get().getId(),
                            profile.get().getName()
                    );
                }
            }

            // A valid UUID is enough to identify a player for notes even when
            // the player is offline and their profile is not cached.
            return new ResolvedPlayer(targetUuid, targetUuid.toString());
        }

        ServerPlayer online = server.getPlayerList().getPlayerByName(target);
        if (online != null) {
            return new ResolvedPlayer(online.getUUID(), online.getGameProfile().getName());
        }

        synchronized (DATA_LOCK) {
            for (Map.Entry<String, PlayerNotes> entry : PLAYERS.entrySet()) {
                PlayerNotes player = entry.getValue();

                if (player == null
                        || player.name == null
                        || !player.name.equalsIgnoreCase(target)) {
                    continue;
                }

                try {
                    return new ResolvedPlayer(
                            UUID.fromString(entry.getKey()),
                            player.name
                    );
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn(
                            "Ignoring invalid player UUID '{}' in admin notes.",
                            entry.getKey()
                    );
                }
            }
        }

        var cache = server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(target);

            if (profile.isPresent()) {
                return new ResolvedPlayer(
                        profile.get().getId(),
                        profile.get().getName()
                );
            }
        }

        return null;
    }

    private static void loadData() {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                LOGGER.warn("Cannot load admin notes because the server is not available yet.");
                return;
            }

            dataFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("admin_notes.json");

            if (Files.notExists(dataFile)) {
                PLAYERS.clear();
                saveData();
                return;
            }

            String json = Files.readString(dataFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            PLAYERS.clear();

            JsonElement playersElement = root.get("players");
            if (playersElement == null || !playersElement.isJsonObject()) {
                LOGGER.warn("Admin notes file has no valid players object.");
                return;
            }

            for (Map.Entry<String, JsonElement> entry :
                    playersElement.getAsJsonObject().entrySet()) {

                PlayerNotes player = parsePlayerNotes(entry.getKey(), entry.getValue());
                if (player != null) {
                    PLAYERS.put(entry.getKey(), player);
                }
            }

            saveData();
        } catch (Exception e) {
            LOGGER.error("Failed to load admin notes.", e);
        }
    }

    private static PlayerNotes parsePlayerNotes(String playerUuid, JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();

        String name = "";
        JsonElement nameElement = object.get("name");

        if (nameElement != null && nameElement.isJsonPrimitive()) {
            name = nameElement.getAsString();
        }

        PlayerNotes player = new PlayerNotes(name);
        JsonElement notesElement = object.get("notes");

        if (notesElement == null || notesElement.isJsonNull()) {
            return player;
        }

        if (notesElement.isJsonArray()) {
            for (JsonElement noteElement : notesElement.getAsJsonArray()) {
                try {
                    AdminNotesAPI.Note note =
                            GSON.fromJson(noteElement, AdminNotesAPI.Note.class);

                    if (isValidNote(note)) {
                        player.notes.add(note);
                    }
                } catch (Exception exception) {
                    LOGGER.warn(
                            "Skipping malformed note for player '{}'.",
                            playerUuid,
                            exception
                    );
                }
            }

            return player;
        }

        if (notesElement.isJsonObject()) {
            // Legacy schema:
            // notes = { "<author UUID>": { "author", "text", "createdAt" } }
            for (Map.Entry<String, JsonElement> noteEntry :
                    notesElement.getAsJsonObject().entrySet()) {

                try {
                    UUID authorUuid = UUID.fromString(noteEntry.getKey());
                    LegacyNote legacy =
                            GSON.fromJson(noteEntry.getValue(), LegacyNote.class);

                    if (legacy == null || legacy.text == null || legacy.text.isBlank()) {
                        continue;
                    }

                    player.notes.add(new AdminNotesAPI.Note(
                            UUID.randomUUID(),
                            authorUuid,
                            legacy.author,
                            legacy.text,
                            legacy.createdAt
                    ));
                } catch (Exception exception) {
                    LOGGER.warn(
                            "Skipping malformed legacy note for player '{}'.",
                            playerUuid,
                            exception
                    );
                }
            }
        }

        return player;
    }

    private static boolean isValidNote(AdminNotesAPI.Note note) {
        return note != null
                && note.id() != null
                && note.text() != null
                && !note.text().isBlank();
    }

    static void saveData() {
        if (dataFile == null) {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            dataFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("admin_notes.json");
        }

        try {
            Storage storage = new Storage();
            storage.version = 2;
            storage.players.putAll(PLAYERS);

            Path parent = dataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(
                    dataFile,
                    GSON.toJson(storage),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            LOGGER.error("Failed to save admin notes.", e);
        }
    }

    private static String formatDate(long timestamp) {
        if (timestamp <= 0) return "Unknown";

        try {
            return java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(timestamp));
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private static final class Storage {
        int version = 2;
        Map<String, PlayerNotes> players = new LinkedHashMap<>();
    }

    static final class PlayerNotes {
        String name;
        List<AdminNotesAPI.Note> notes = new ArrayList<>();

        PlayerNotes(String name) {
            this.name = name;
        }
    }

    private record LegacyNote(
            String author,
            String text,
            long createdAt
    ) {}

    private record ResolvedPlayer(UUID uuid, String name) {}

    static void initializeData(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;

        synchronized (DATA_LOCK) {
            dataFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("admin_notes.json");
            loadData();
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        initializeData(event.getServer());
    }
}
