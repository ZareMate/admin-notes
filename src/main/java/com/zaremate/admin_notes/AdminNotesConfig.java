package com.zaremate.admin_notes;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AdminNotesConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> READ_PERMISSION =
            BUILDER.comment("LuckPerms permission required to read player notes.")
                    .define("read_permission", "admin_notes.read");

    public static final ModConfigSpec.ConfigValue<String> ADD_PERMISSION =
            BUILDER.comment("LuckPerms permission required to add or replace your note for a player.")
                    .define("add_permission", "admin_notes.add");

    public static final ModConfigSpec.ConfigValue<String> REMOVE_PERMISSION =
            BUILDER.comment("LuckPerms permission required to remove your note for a player.")
                    .define("remove_permission", "admin_notes.remove");

    public static final ModConfigSpec.ConfigValue<String> CLEAR_PERMISSION =
            BUILDER.comment("LuckPerms permission required to clear all notes for a player.")
                    .define("clear_permission", "admin_notes.clear");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AdminNotesConfig() {}
}
