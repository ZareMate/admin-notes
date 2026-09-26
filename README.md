# Admin Notes

NeoForge 1.21.1 server-side admin notes with LuckPerms permissions.

## Commands

`/note <player>` — read all notes for a player.  
`/note <noteId>` — find a specific note by its UUID (requires `admin_notes.read`).  
`/note add <player> <note>` — add a new note as the executing admin.  
`/note rm <player>` — remove the latest note created by you for the player.  
`/note rm <player> <noteId>` — remove a specific note by its ID.  
`/note clear <player>` — remove all notes.

Each note now has its own UUID, so multiple notes can be created by the same author. Note IDs shown by `/note <player>` are clickable and copy the UUID to the clipboard.

Player arguments have autocomplete for currently online players and previously stored players. Autocomplete does not restrict input, so an offline player can still be entered manually even if their name is not in the suggestions.

## Permissions

- `admin_notes.read`
- `admin_notes.add`
- `admin_notes.remove`
- `admin_notes.clear`

Operator level 3+ also has access.

Notes are stored per player UUID in `admin_notes.json` inside the world directory.


Airport Security System offense commands are implemented by the Airport Security System mod, not Admin Notes. Admin Notes only provides the generic note storage API used by integrations.

## Mod API

Other server-side mods can use the public `com.zaremate.admin_notes.AdminNotesAPI` class to read and manage notes directly.

The API is built around **unique note IDs**. A player can have any number of notes from the same author.

### Note structure

```java
public record Note(
    UUID id,
    UUID authorUuid,
    String author,
    String text,
    long createdAt
) {
    public boolean isSystem() {
        return authorUuid == null;
    }
}
```

For a system-generated note:

```java
note.authorUuid() == null
note.author() == null
note.isSystem() == true
```

### Read all notes

```java
UUID playerUuid = ...;

List<AdminNotesAPI.Note> notes =
        AdminNotesAPI.getNotes(playerUuid);

for (AdminNotesAPI.Note note : notes) {
    if (note.isSystem()) {
        System.out.println("[SYSTEM] " + note.text());
    } else {
        System.out.println(
                "[" + note.author() + "] " + note.text()
        );
    }

    System.out.println("Note ID: " + note.id());
}
```

### Read one note

```java
Optional<AdminNotesAPI.Note> note =
        AdminNotesAPI.getNote(playerUuid, noteId);
```

### Add a normal author note

```java
AdminNotesAPI.Note note = AdminNotesAPI.addNote(
        playerUuid,
        authorUuid,
        authorName,
        "Player has been warned about X."
);
```

The note gets a new unique UUID every time, even when the same author adds multiple notes.

### Add an automatic system note

System notes do not require an author UUID or author name:

```java
AdminNotesAPI.Note note =
        AdminNotesAPI.addSystemNote(
                playerUuid,
                "Player triggered the anti-cheat."
        );
```

This creates a note where:

```java
note.authorUuid() == null
note.author() == null
note.isSystem() == true
```

### Edit a note

Edit by the note's unique ID:

```java
AdminNotesAPI.Note updated =
        AdminNotesAPI.editNote(
                playerUuid,
                noteId,
                "Updated note text."
        );
```

The original note ID, author and creation timestamp are preserved.

### Remove one note

```java
boolean removed =
        AdminNotesAPI.removeNote(
                playerUuid,
                noteId
        );
```

### Remove all notes

```java
int removed =
        AdminNotesAPI.clearNotes(playerUuid);
```

### System categories

Integrations can maintain a named system category that is displayed in the notes list:

```java
AdminNotesAPI.upsertSystemCategoryNote(
        playerUuid,
        "ASS",
        "x-ray detected (last: 26-09-2026)"
);
```

Category notes are system-generated entries. The category name is available to the notes UI as the system author value. The Airport Security System uses the `ASS` category, which the UI renders in gold and without exposing its internal note UUID.

A category can be removed with:

```java
AdminNotesAPI.removeSystemCategoryNotes(
        playerUuid,
        "ASS"
);
```

### Get stored player name

```java
Optional<String> name =
        AdminNotesAPI.getPlayerName(playerUuid);
```

API calls are thread-safe and automatically persist changes to `admin_notes.json`.

The API does not perform LuckPerms permission checks. Consuming mods are responsible for deciding which of their own actions are authorized.

## Data migration

The 2.0 API automatically migrates the previous Admin Notes format.

Existing notes from the old format receive new note UUIDs while keeping their original author, text and creation timestamp. They remain usable after migration.

## Example dependency

A consuming mod should declare Admin Notes as a dependency and use:

```java
import com.zaremate.admin_notes.AdminNotesAPI;
```

The API is intended for server-side use and is available while the Minecraft server is running.
