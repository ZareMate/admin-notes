# Admin Notes

NeoForge 1.21.1 server-side admin notes with LuckPerms permissions.

## Commands

`/note <player>` — read all notes for a player.  
`/note add <player> <note>` — add or replace your own note.  
`/note rm <player>` — remove your own note.  
`/note clear <player>` — remove all notes.

## Permissions

- `admin_notes.read`
- `admin_notes.add`
- `admin_notes.remove`
- `admin_notes.clear`

Operator level 3+ also has access.

Notes are stored per UUID in `admin_notes.json` inside the world directory.

## Mod API

Other server-side mods can use the public `com.zaremate.admin_notes.AdminNotesAPI` class to read and manage notes directly.

The API uses player UUIDs and stores one note per author for each player. Calling `addNote` for an author who already has a note replaces that note.

### Read notes

```java
UUID playerUuid = ...;

Map<UUID, AdminNotesAPI.Note> notes = AdminNotesAPI.getNotes(playerUuid);

for (Map.Entry<UUID, AdminNotesAPI.Note> entry : notes.entrySet()) {
    UUID authorUuid = entry.getKey();
    AdminNotesAPI.Note note = entry.getValue();

    System.out.println(
            note.author() + ": " + note.text()
    );
}
```

### Add a note

```java
AdminNotesAPI.Note note = AdminNotesAPI.addNote(
        playerUuid,
        authorUuid,
        authorName,
        "Player has been warned about X."
);
```

### Read one author's note

```Optional<AdminNotesAPI.Note> note =
        AdminNotesAPI.getNote(playerUuid, authorUuid);
```

### Edit a note

```AdminNotesAPI.Note updated =
        AdminNotesAPI.editNote(
                playerUuid,
                authorUuid,
                "Updated note text."
        );
```

`editNote` keeps the original creation timestamp.

### Remove a note

```boolean removed =
        AdminNotesAPI.removeNote(playerUuid, authorUuid);
```

### Remove all notes

```int removed =
        AdminNotesAPI.clearNotes(playerUuid);
```

### Get the stored player name

```Optional<String> name =
        AdminNotesAPI.getPlayerName(playerUuid);
```

API calls are thread-safe and automatically persist changes to `admin_notes.json`.

The API does not perform LuckPerms permission checks. Consuming mods are responsible for deciding which of their own actions are authorized.

### Example dependency

A consuming mod should declare Admin Notes as a dependency and use:

```java
import com.zaremate.admin_notes.AdminNotesAPI;
```

The API is intended for server-side use and is available after the Admin Notes mod has initialized its server data.
