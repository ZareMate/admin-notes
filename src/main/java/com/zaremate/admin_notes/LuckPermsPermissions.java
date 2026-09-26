package com.zaremate.admin_notes;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public final class LuckPermsPermissions {
    private LuckPermsPermissions() {}

    public static boolean hasPermission(CommandSourceStack source, String permission) {
        if (!(source.getEntity() instanceof ServerPlayer player))
            return true;
        return hasPermission(player, permission);
    }

    public static boolean hasPermission(ServerPlayer player, String permission) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            return luckPerms.getPlayerAdapter(ServerPlayer.class)
                    .getPermissionData(player)
                    .checkPermission(permission)
                    .asBoolean();
        } catch (IllegalStateException e) {
            AdminNotes.LOGGER.warn(
                    "LuckPerms is not available; denying permission '{}' for {}.",
                    permission,
                    player.getGameProfile().getName()
            );
            return false;
        }
    }
}
