package com.chunkops.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/**
 * ChunkOps 入口页（模组列表 → ChunkOps → 配置）。
 *
 * 主菜单按钮可能被 FancyMenu/CustomMainMenu 等美化模组隐藏；此入口位于模组管理界面内，始终可用。
 */
public class ChunkOpsConfigScreen extends GuiScreen {

    private static final int BTN_OPEN = 1;
    private static final int BTN_DONE = 2;

    private final GuiScreen parent;

    public ChunkOpsConfigScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        this.buttonList.add(new GuiButton(BTN_OPEN, cx - 100, this.height / 2 - 24, 200, 20,
                I18n.format("chunkops.button.openEditor")));
        this.buttonList.add(new GuiButton(BTN_DONE, cx - 100, this.height / 2 + 22, 200, 20,
                I18n.format("chunkops.button.done")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_OPEN) {
            Minecraft.getMinecraft().displayGuiScreen(new ChunkOpsEditorScreen());
        } else if (button.id == BTN_DONE) {
            Minecraft.getMinecraft().displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 1) { // ESC → 返回上一级（模组列表），而不是直接关掉
            Minecraft.getMinecraft().displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int cx = this.width / 2;
        this.drawCenteredString(this.fontRenderer, I18n.format("chunkops.config.title"),
                cx, this.height / 2 - 72, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer,
                I18n.format("chunkops.config.hint1"), cx, this.height / 2 - 54, 0xFFAAAAAA);
        this.drawCenteredString(this.fontRenderer,
                I18n.format("chunkops.config.hint2"), cx, this.height / 2 - 42, 0xFFAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
