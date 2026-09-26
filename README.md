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
