package com.chunkops.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 主菜单按钮注入（阶段 3）：GuiMainMenu 添加「区块编辑器」按钮。
 */
@Mod.EventBusSubscriber
public class ChunkOpsGuiHandler {

    public static final int BUTTON_ID = 0x434F; // "CO"

    @SubscribeEvent
    public static void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.getGui() instanceof GuiMainMenu) {
            int width = event.getGui().width;
            int height = event.getGui().height;
            // 放在主菜单按钮列下方（单人与多人按钮之下）
            event.getButtonList().add(new GuiButton(BUTTON_ID,
                    width / 2 - 100, Math.min(height / 4 + 132, height - 40), 200, 20, "区块编辑器"));
        }
    }

    @SubscribeEvent
    public static void onActionPerformed(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.getButton().id == BUTTON_ID) {
            Minecraft.getMinecraft().displayGuiScreen(new ChunkOpsEditorScreen());
        }
    }

    /** 返回主菜单。 */
    public static void backToMainMenu() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiMainMenu());
    }

    @SuppressWarnings("unused")
    private static void openEditor(GuiScreen current) {
        Minecraft.getMinecraft().displayGuiScreen(new ChunkOpsEditorScreen());
    }
}
