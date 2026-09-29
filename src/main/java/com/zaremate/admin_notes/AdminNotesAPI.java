package com.zaremate.admin_notes;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API for interacting with Admin Notes from other server-side mods.
 *
 * <p>A player can have at most one normal note from each author. Adding a
 * new note from an author replaces that author's existing note for the player.
 * System-generated notes are separate and are not affected by this rule.</p>
 *
 * <p>System notes have a {@code null} author UUID and author name.</p>
 *
 * <p>API operations are thread-safe and changes are persisted automatically.</p>
 */
public final class AdminNotesAPI {
    private AdminNotesAPI() {}

    /**
     * Returns an immutable snapshot of all notes belonging to a player.
     */
    public static List<Note> getNotes(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (player == null || player.notes == null || player.notes.isEmpty()) {
                return List.of();
            }

            return List.copyOf(player.notes);
        }
    }

    /**
     * Returns a snapshot of UUIDs for every player with stored Admin Notes.
     *
     * <p>This is intended for integrations that need to inspect their own
     * system-generated notes without accessing Admin Notes internals.</p>
     */
    public static List<UUID> getPlayers() {
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            return AdminNotesEvents.PLAYERS.keySet().stream()
                    .map(AdminNotesAPI::parseUuid)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Returns one note by its unique note ID.
     */
    public static Optional<Note> getNote(UUID playerUuid, UUID noteId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(noteId, "noteId");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (player == null || player.notes == null) {
                return Optional.empty();
            }

            for (Note note : player.notes) {
                if (note.id().equals(noteId)) {
                    return Optional.of(note);
                }
            }

            return Optional.empty();
        }
    }

    /**
     * Adds a normal admin/mod-authored note.
     *
     * <p>Each author can have only one normal note per player. If the author
     * already has a note for the player, that note is replaced with the new
     * text.</p>
     */
    public static Note addNote(
            UUID playerUuid,
            UUID authorUuid,
            String authorName,
            String text
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(authorUuid, "authorUuid");
        Objects.requireNonNull(authorName, "authorName");
        Objects.requireNonNull(text, "text");

        ensureLoaded();

        String trimmedName = authorName.trim();
        String trimmedText = text.trim();

        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Author name cannot be empty.");
        }

        validateText(trimmedText);

        Note note = new Note(
                UUID.randomUUID(),
                authorUuid,
                trimmedName,
                trimmedText,
                System.currentTimeMillis()
        );

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.getOrCreate(playerUuid, resolvePlayerName(playerUuid));

            // One normal note per author/player pair. System notes are not
            // considered because they have a null author UUID.
            for (int i = 0; i < player.notes.size(); i++) {
                Note existing = player.notes.get(i);

                if (authorUuid.equals(existing.authorUuid()) && !existing.isSystem()) {
                    player.notes.set(i, note);
                    AdminNotesEvents.saveData();
                    return note;
                }
            }

            player.notes.add(note);
            AdminNotesEvents.saveData();
        }

        return note;
    }

    /**
     * Adds an automatically generated system note.
     *
     * <p>System notes have no author UUID and no author name.</p>
     */
    public static Note addSystemNote(UUID playerUuid, String text) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(text, "text");

        ensureLoaded();

        String trimmedText = text.trim();
        validateText(trimmedText);

        Note note = new Note(
                UUID.randomUUID(),
                null,
                null,
                trimmedText,
                System.currentTimeMillis()
        );

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.getOrCreate(playerUuid, resolvePlayerName(playerUuid));

            player.notes.add(note);
            AdminNotesEvents.saveData();
        }

        return note;
    }

    /**
     * Edits an existing note by note ID.
     *
     * <p>The original author and creation timestamp are preserved.</p>
     */
    public static Note editNote(UUID playerUuid, UUID noteId, String text) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(noteId, "noteId");
        Objects.requireNonNull(text, "text");

        ensureLoaded();

        String trimmedText = text.trim();
        validateText(trimmedText);

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (player == null || player.notes == null) {
                throw new IllegalStateException("No notes found for this player.");
            }

            for (int i = 0; i < player.notes.size(); i++) {
                Note existing = player.notes.get(i);

                if (!existing.id().equals(noteId)) {
                    continue;
                }

                Note updated = new Note(
                        existing.id(),
                        existing.authorUuid(),
                        existing.author(),
                        trimmedText,
                        existing.createdAt()
                );

                player.notes.set(i, updated);
                AdminNotesEvents.saveData();
                return updated;
            }
        }

        throw new IllegalStateException("Note not found: " + noteId);
    }

    /**
     * Removes one note by its unique note ID.
     *
     * @return true when a note was removed
     */
    public static boolean removeNote(UUID playerUuid, UUID noteId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(noteId, "noteId");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (player == null || player.notes == null) {
                return false;
            }

            boolean removed = player.notes.removeIf(note -> note.id().equals(noteId));

            if (removed) {
                AdminNotesEvents.saveData();
            }

            return removed;
        }
    }

    /**
     * Removes every note belonging to a player.
     *
     * @return the number of removed notes
     */
    public static int clearNotes(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (player == null || player.notes == null || player.notes.isEmpty()) {
                return 0;
            }

            int removed = player.notes.size();
            player.notes.clear();
            AdminNotesEvents.saveData();
            return removed;
        }
    }

    /**
     * Returns the player name currently stored for a UUID, when known.
     */
    public static Optional<String> getPlayerName(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (player == null || player.name == null || player.name.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(player.name);
        }
    }

    private static void validateText(String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Note text cannot be empty.");
        }
    }

    private static void ensureLoaded() {
        if (AdminNotesEvents.dataFile != null) {
            return;
        }

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException(
                    "Admin Notes API is only available while the Minecraft server is running."
            );
        }

        AdminNotesEvents.initializeData(server);
    }

    private static String resolvePlayerName(UUID playerUuid) {
        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes stored =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (stored != null && stored.name != null && !stored.name.isBlank()) {
                return stored.name;
            }
        }

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            var online = server.getPlayerList().getPlayer(playerUuid);
            if (online != null) {
                return online.getGameProfile().getName();
            }
        }

        return playerUuid.toString();
    }

    /**
     * Immutable representation of a stored note.
     *
     * @param id unique note ID
     * @param authorUuid UUID of the author, or {@code null} for a system note
     * @param author author name, or {@code null} for a system note
     * @param text note text
     * @param createdAt creation timestamp in milliseconds since Unix epoch
     */
    public record Note(
            UUID id,
            UUID authorUuid,
            String author,
            String text,
            long createdAt
    ) {
        /**
         * Returns whether this note was generated by the system rather than
         * an individual author.
         */
        public boolean isSystem() {
            return authorUuid == null;
        }
    }
}
