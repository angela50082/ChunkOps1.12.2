package com.chunkops.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;

import java.util.Collections;
import java.util.Set;

/**
 * Forge 模组列表「配置」入口（2026-09-15）。
 *
 * 背景：部分整合包用 FancyMenu / CustomMainMenu 之类美化主菜单，会隐藏或清掉注入到主菜单的按钮，
 * 导致「区块编辑器」入口消失。模组列表里的「配置」按钮由 Forge 提供、位于模组管理界面内，
 * 不受主菜单美化影响——这是最可靠的备用入口。
 *
 * 注册方式：@Mod(guiFactory = "com.chunkops.gui.ChunkOpsGuiFactory")
 */
public class ChunkOpsGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraft) {
        // 无需初始化
    }

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parent) {
        return new ChunkOpsConfigScreen(parent);
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }
}
