package com.chunkops.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.world.storage.WorldSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 主菜单 MCA 式区块编辑器（阶段 3）。
 *
 * 当前骨架：存档列表（Saves 目录）、视图状态（中心坐标/缩放）、区块网格渲染、
 * 状态栏与控制按钮。2D 取色地图渲染、框选与操作菜单在后续增量中实现。
 */
public class ChunkOpsEditorScreen extends GuiScreen {

    private static final int BTN_PREV = 1;
    private static final int BTN_NEXT = 2;
    private static final int BTN_BACK = 3;

    private final List<WorldSummary> worlds = new ArrayList<WorldSummary>();
    private int selectedWorld = -1;

    // 视图状态（方块坐标）
    private double viewX = 8;
    private double viewZ = 8;
    private double zoom = 2.0; // 像素/方块

    @Override
    public void initGui() {
        worlds.clear();
        try {
            List<WorldSummary> list = Minecraft.getMinecraft().getSaveLoader().getSaveList();
            if (list != null) {
                for (WorldSummary w : list) worlds.add(w);
            }
            worlds.sort(new Comparator<WorldSummary>() {
                public int compare(WorldSummary a, WorldSummary b) {
                    return Long.compare(b.getLastTimePlayed(), a.getLastTimePlayed());
                }
            });
        } catch (Exception e) {
            // 存档列表读取失败（保持空列表）
        }
        if (selectedWorld < 0 && !worlds.isEmpty()) selectedWorld = 0;

        this.buttonList.clear();
        int y = 6;
        this.buttonList.add(new GuiButton(BTN_PREV, 6, y, 24, 20, "<"));
        this.buttonList.add(new GuiButton(BTN_NEXT, 34, y, 24, 20, ">"));
        this.buttonList.add(new GuiButton(BTN_BACK, this.width - 86, y, 80, 20, "返回主菜单"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 背景
        drawRect(0, 0, this.width, this.height, 0xFF14181C);
        drawRect(0, 0, this.width, 30, 0xFF20262C);

        // 地图画布区域（顶部 30px 控制条 + 底部 30px 状态条）
        int mapTop = 30;
        int mapBottom = this.height - 30;
        int mapCenterY = (mapTop + mapBottom) / 2;

        // 区块网格与坐标轴
        double pxPerBlock = zoom;
        int gridMinX = (int) Math.floor(viewX - (double) this.width / 2 / pxPerBlock);
        int gridMaxX = (int) Math.ceil(viewX + (double) this.width / 2 / pxPerBlock);
        int gridMinZ = (int) Math.floor(viewZ - (double) (mapBottom - mapTop) / 2 / pxPerBlock);
        int gridMaxZ = (int) Math.ceil(viewZ + (double) (mapBottom - mapTop) / 2 / pxPerBlock);
        for (int chunkX = (gridMinX >> 4) << 4; chunkX <= gridMaxX; chunkX += 16) {
            int sx = this.width / 2 + (int) Math.round((chunkX - viewX) * pxPerBlock);
            if (sx >= 0 && sx <= this.width) {
                drawVerticalLine(sx, mapTop, mapBottom, 0xFF3A444E);
            }
        }
        for (int chunkZ = (gridMinZ >> 4) << 4; chunkZ <= gridMaxZ; chunkZ += 16) {
            int sy = mapCenterY + (int) Math.round((chunkZ - viewZ) * pxPerBlock);
            if (sy >= mapTop && sy <= mapBottom) {
                drawHorizontalLine(0, this.width, sy, 0xFF3A444E);
            }
        }
        // 坐标轴（过中心的横竖线）
        int centerX = this.width / 2;
        int centerY = mapCenterY;
        drawVerticalLine(centerX, mapTop, mapBottom, 0xFF55616C);
        drawHorizontalLine(0, this.width, centerY, 0xFF55616C);

        // 世界信息
        String name = selectedWorld >= 0 && selectedWorld < worlds.size()
                ? worlds.get(selectedWorld).getDisplayName() : "（无存档）";
        this.fontRenderer.drawString("存档: " + name, 68, 12, 0xFFFFFF);
        this.fontRenderer.drawString(String.format("%d 个存档", worlds.size()), 200, 12, 0xAAAAAA);

        // 状态栏
        this.fontRenderer.drawString(String.format("中心: %.0f, %.0f   缩放: %.1f px/方块",
                viewX, viewZ, pxPerBlock), 6, this.height - 24, 0xAAAAAA);
        this.fontRenderer.drawString("鼠标拖拽移动 · 滚轮缩放 · ESC 返回", this.width / 2 - 90, this.height - 12, 0x888888);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_PREV) {
            if (!worlds.isEmpty()) selectedWorld = (selectedWorld - 1 + worlds.size()) % worlds.size();
        } else if (button.id == BTN_NEXT) {
            if (!worlds.isEmpty()) selectedWorld = (selectedWorld + 1) % worlds.size();
        } else if (button.id == BTN_BACK) {
            ChunkOpsGuiHandler.backToMainMenu();
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (dWheel != 0) {
            double factor = dWheel > 0 ? 1.25 : 0.8;
            zoom = Math.max(0.5, Math.min(32.0, zoom * factor));
        }
        super.handleMouseInput();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 1) { // ESC
            ChunkOpsGuiHandler.backToMainMenu();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
