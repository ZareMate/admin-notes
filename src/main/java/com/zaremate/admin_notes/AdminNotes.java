package com.zaremate.admin_notes;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(AdminNotes.MOD_ID)
public final class AdminNotes {
    public static final String MOD_ID = "admin_notes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AdminNotes(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, AdminNotesConfig.SPEC);
        NeoForge.EVENT_BUS.register(AdminNotesEvents.class);
        LOGGER.info("Admin Notes loaded.");
    }
}
