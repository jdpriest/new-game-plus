package com.newgameplus;

import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Image;
import java.awt.image.BufferedImage;

import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

@Singleton
class NewGamePlusOverlay extends WidgetItemOverlay {
    private final NewGamePlusPlugin plugin;
    private final ItemManager itemManager;
    private final NewGamePlusConfig config;

    @Inject
    private NewGamePlusOverlay(ItemManager itemManager, NewGamePlusPlugin plugin, NewGamePlusConfig config) {
        this.itemManager = itemManager;
        this.plugin = plugin;
        this.config = config;
        // Apply to inventory and bank
        showOnInventory();
        showOnBank();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem item) {
        if (!plugin.isLocked(itemId)) {
            return;
        }

        Rectangle bounds = item.getCanvasBounds();
        if (bounds == null) {
            return;
        }

        // Draw a tinted image that matches the item icon shape
        final int qty = item.getQuantity();
        final Image icon = itemManager.getImage(itemId, qty, false);
        if (icon == null) {
            return;
        }

        // Choose opacity based on context: bank items vs inventory (including side inventory)
        int groupId = 0;
        Widget w = item.getWidget();
        if (w != null) {
            groupId = WidgetInfo.TO_GROUP(w.getId());
        }
        final boolean isBankItems = groupId == WidgetID.BANK_GROUP_ID; // bank main items container
        final int alpha = isBankItems ? config.bankOpacity() : config.inventoryOpacity();
        final Color dimColor = ColorUtil.colorWithAlpha(Color.BLACK, alpha);
        final BufferedImage iconBi = ImageUtil.bufferedImageFromImage(icon);
        final BufferedImage dimmed = ImageUtil.fillImage(iconBi, dimColor);
        graphics.drawImage(dimmed, (int) bounds.getX(), (int) bounds.getY(), null);
    }
}
