package com.zaremate.admin_notes;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API for interacting with Admin Notes from other mods.
 *
 * <p>The API stores one note per author for each player, matching the
 * {@code /note add} command behaviour: adding a note from the same author
 * replaces that author's existing note.</p>
 *
 * <p>Calls are thread-safe. Persistence is handled automatically.</p>
 */
public final class AdminNotesAPI {
    private AdminNotesAPI() {}

    /**
     * Returns all notes for a player.
     *
     * @param playerUuid UUID of the player whose notes should be read
     * @return immutable snapshot of the player's notes; empty when the player has no notes
     */
    public static Map<UUID, Note> getNotes(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player = AdminNotesEvents.PLAYERS.get(playerUuid.toString());
            if (player == null || player.notes == null || player.notes.isEmpty()) {
                return Map.of();
            }

            Map<UUID, Note> result = new LinkedHashMap<>();
            for (Map.Entry<String, Note> entry : player.notes.entrySet()) {
                try {
                    result.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed author UUIDs rather than breaking API reads.
                }
            }

            return Map.copyOf(result);
        }
    }

    /**
     * Returns the note created by a specific author for a player.
     *
     * @param playerUuid target player's UUID
     * @param authorUuid author's UUID
     * @return the note when present
     */
    public static Optional<Note> getNote(UUID playerUuid, UUID authorUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(authorUuid, "authorUuid");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player = AdminNotesEvents.PLAYERS.get(playerUuid.toString());
            if (player == null || player.notes == null) {
                return Optional.empty();
            }

            return Optional.ofNullable(player.notes.get(authorUuid.toString()));
        }
    }

    /**
     * Adds a note for a player.
     *
     * <p>If the same author already has a note for the player, that note is
     * replaced. The timestamp is updated.</p>
     *
     * @param playerUuid target player's UUID
     * @param authorUuid author's UUID
     * @param authorName name stored with the note
     * @param text note text
     * @return the stored note
     */
    public static Note addNote(UUID playerUuid, UUID authorUuid, String authorName, String text) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(authorUuid, "authorUuid");
        Objects.requireNonNull(authorName, "authorName");
        Objects.requireNonNull(text, "text");

        ensureLoaded();

        String noteText = text.trim();
        if (noteText.isEmpty()) {
            throw new IllegalArgumentException("Note text cannot be empty.");
        }

        String name = authorName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Author name cannot be empty.");
        }

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.getOrCreate(playerUuid, resolvePlayerName(playerUuid));

            Note note = new Note(name, noteText, System.currentTimeMillis());
            player.notes.put(authorUuid.toString(), note);
            AdminNotesEvents.saveData();
            return note;
        }
    }

    /**
     * Edits the existing note belonging to an author.
     *
     * @param playerUuid target player's UUID
     * @param authorUuid author's UUID
     * @param text new note text
     * @return the updated note
     * @throws IllegalStateException when the author has no note for the player
     */
    public static Note editNote(UUID playerUuid, UUID authorUuid, String text) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(authorUuid, "authorUuid");
        Objects.requireNonNull(text, "text");

        ensureLoaded();

        String noteText = text.trim();
        if (noteText.isEmpty()) {
            throw new IllegalArgumentException("Note text cannot be empty.");
        }

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player = AdminNotesEvents.PLAYERS.get(playerUuid.toString());
            if (player == null || player.notes == null) {
                throw new IllegalStateException("No note exists for this author and player.");
            }

            String authorKey = authorUuid.toString();
            Note existing = player.notes.get(authorKey);
            if (existing == null) {
                throw new IllegalStateException("No note exists for this author and player.");
            }

            Note updated = new Note(existing.author(), noteText, existing.createdAt());
            player.notes.put(authorKey, updated);
            AdminNotesEvents.saveData();
            return updated;
        }
    }

    /**
     * Removes the note belonging to an author.
     *
     * @return {@code true} when a note was removed
     */
    public static boolean removeNote(UUID playerUuid, UUID authorUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(authorUuid, "authorUuid");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player = AdminNotesEvents.PLAYERS.get(playerUuid.toString());
            if (player == null || player.notes == null) {
                return false;
            }

            Note removed = player.notes.remove(authorUuid.toString());
            if (removed == null) {
                return false;
            }

            AdminNotesEvents.saveData();
            return true;
        }
    }

    /**
     * Removes every note belonging to a player.
     *
     * @return number of removed notes
     */
    public static int clearNotes(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        ensureLoaded();

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player = AdminNotesEvents.PLAYERS.get(playerUuid.toString());
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
            AdminNotesEvents.PlayerNotes player = AdminNotesEvents.PLAYERS.get(playerUuid.toString());
            if (player == null || player.name == null || player.name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(player.name);
        }
    }

    private static void ensureLoaded() {
        if (AdminNotesEvents.dataFile != null) {
            return;
        }

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            AdminNotesEvents.initializeData(server);
        }
    }

    private static String resolvePlayerName(UUID playerUuid) {
        Optional<String> stored = getPlayerName(playerUuid);
        if (stored.isPresent()) {
            return stored.get();
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
     * Immutable note exposed to API consumers.
     *
     * @param author author name stored with the note
     * @param text note text
     * @param createdAt creation timestamp in milliseconds since Unix epoch
     */
    public record Note(String author, String text, long createdAt) {}
}
