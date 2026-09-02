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
            // 动态放在现有按钮列最下方（适配整合包自定义主菜单布局，避免重叠——2026-09-02 实测
            // 固定 y=height/4+132 会与整合包菜单按钮/文字列重叠）
            int y = Math.min(height / 4 + 132, height - 40);
            int maxBottom = -1;
            for (net.minecraft.client.gui.GuiButton b : event.getButtonList()) {
                // 只考虑与本按钮同列（x 接近中线）的按钮
                if (Math.abs(b.x - (width / 2 - 100)) < 240 && b.y + b.height > maxBottom) {
                    maxBottom = b.y + b.height;
                }
            }
            if (maxBottom > 0) y = Math.max(y, maxBottom + 6);
            event.getButtonList().add(new GuiButton(BUTTON_ID,
                    width / 2 - 100, y, 200, 20, "区块编辑器"));
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
