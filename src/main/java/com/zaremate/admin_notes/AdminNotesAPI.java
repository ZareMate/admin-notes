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
 * <p>Each note has its own UUID, so a player can have any number of notes
 * from the same author or from the system.</p>
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
     * <p>Unlike the old API, adding another note from the same author does not
     * replace an existing note.</p>
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
     * Creates or updates a system note for a named category.
     *
     * <p>The category is displayed as the note author/category label while the
     * note remains a system-generated entry. The returned note still has an
     * internal UUID for storage, but category notes can be rendered without
     * exposing that ID.</p>
     */
    public static Note upsertSystemCategoryNote(
            UUID playerUuid,
            String category,
            String text
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(text, "text");

        ensureLoaded();

        String trimmedCategory = category.trim();
        String trimmedText = text.trim();

        if (trimmedCategory.isEmpty()) {
            throw new IllegalArgumentException("Category cannot be empty.");
        }

        validateText(trimmedText);

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.getOrCreate(playerUuid, resolvePlayerName(playerUuid));

            for (int i = 0; i < player.notes.size(); i++) {
                Note existing = player.notes.get(i);

                if (!existing.isSystem()
                        || existing.author() == null
                        || !existing.author().equalsIgnoreCase(trimmedCategory)) {
                    continue;
                }

                Note updated = new Note(
                        existing.id(),
                        null,
                        trimmedCategory,
                        trimmedText,
                        existing.createdAt()
                );

                player.notes.set(i, updated);
                AdminNotesEvents.saveData();

                // Keep exactly one category note for this category/player.
                player.notes.removeIf(note ->
                        note != updated
                                && note.isSystem()
                                && note.author() != null
                                && note.author().equalsIgnoreCase(trimmedCategory)
                );
                AdminNotesEvents.saveData();

                return updated;
            }

            Note note = new Note(
                    UUID.randomUUID(),
                    null,
                    trimmedCategory,
                    trimmedText,
                    System.currentTimeMillis()
            );

            player.notes.add(note);
            AdminNotesEvents.saveData();
            return note;
        }
    }

    /**
     * Removes every system note belonging to a category.
     *
     * @return the number of removed category notes
     */
    public static int removeSystemCategoryNotes(UUID playerUuid, String category) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(category, "category");
        ensureLoaded();

        String trimmedCategory = category.trim();
        if (trimmedCategory.isEmpty()) {
            return 0;
        }

        synchronized (AdminNotesEvents.DATA_LOCK) {
            AdminNotesEvents.PlayerNotes player =
                    AdminNotesEvents.PLAYERS.get(playerUuid.toString());

            if (player == null || player.notes == null) {
                return 0;
            }

            int before = player.notes.size();
            player.notes.removeIf(note ->
                    note.isSystem()
                            && note.author() != null
                            && note.author().equalsIgnoreCase(trimmedCategory)
            );

            int removed = before - player.notes.size();
            if (removed > 0) {
                AdminNotesEvents.saveData();
            }
            return removed;
        }
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
