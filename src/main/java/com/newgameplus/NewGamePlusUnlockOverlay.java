package com.newgameplus;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

// Overlay that displays a center-top popup with the unlocked item's icon and name
public class NewGamePlusUnlockOverlay extends Overlay {
    private static final int PADDING = 8;
    private static final int SPACING = 8;
    private static final int ARC = 8;
    // Timing: fade-in, hold full opacity ~5s, fade-out
    private static final long FADE_IN_MS = 400;
    private static final long FULL_MS = Duration.ofSeconds(4).toMillis();
    private static final long FADE_OUT_MS = 400;
    private static final long TOTAL_MS = FADE_IN_MS + FULL_MS + FADE_OUT_MS;

    private final ItemManager itemManager;
    private final Deque<Notification> queue = new ArrayDeque<>();
    private Notification current;
    private long currentStart;

    @Inject
    public NewGamePlusUnlockOverlay(ItemManager itemManager) {
        this.itemManager = itemManager;
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
        setPosition(OverlayPosition.TOP_CENTER);
    }

    public void enqueueUnlock(int itemId, String itemName) {
        if (itemId <= 0) {
            return;
        }
        queue.add(new Notification(itemId, itemName));
    }

    public void clear() {
        queue.clear();
        current = null;
        currentStart = 0L;
    }

    @Override
    public Dimension render(Graphics2D g) {
        long now = System.currentTimeMillis();
        if (current == null) {
            current = queue.pollFirst();
            currentStart = now;
        } else if (now - currentStart > TOTAL_MS) {
            current = queue.pollFirst();
            currentStart = now;
        }

        if (current == null) {
            return null;
        }

        // Resolve image lazily
        if (current.image == null) {
            try {
                current.image = itemManager.getImage(current.itemId);
            } catch (Exception ignored) {
            }
        }

        BufferedImage img = current.image;
        int imgW = img != null ? img.getWidth() : 0;
        int imgH = img != null ? img.getHeight() : 0;

        // Rendering setup
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Use RuneLite's standard fonts for crisp overlay text
        Font titleFont = FontManager.getRunescapeBoldFont();
        Font nameFont = FontManager.getRunescapeFont();

        // Strings
        String title = "NG+: New unlock";
        String name = current.itemName != null ? current.itemName : "Unknown item";

        // Measure
        g.setFont(titleFont);
        FontMetrics fmTitle = g.getFontMetrics();
        int titleW = fmTitle.stringWidth(title);
        int titleH = fmTitle.getAscent();

        g.setFont(nameFont);
        FontMetrics fmName = g.getFontMetrics();
        int nameW = fmName.stringWidth(name);
        int nameH = fmName.getAscent();

        int rightColW = Math.max(titleW, nameW);
        int contentW = imgW + (imgW > 0 ? SPACING : 0) + rightColW;
        int contentH = Math.max(imgH, titleH + 4 + nameH);

        int boxW = contentW + PADDING * 2;
        int boxH = contentH + PADDING * 2;

        // Compute alpha for fade-in/out
        long elapsed = now - currentStart;
        float alpha;
        if (elapsed < FADE_IN_MS) {
            alpha = Math.max(0f, Math.min(1f, (float) elapsed / (float) FADE_IN_MS));
        } else if (elapsed < FADE_IN_MS + FULL_MS) {
            alpha = 1f;
        } else {
            long t = elapsed - (FADE_IN_MS + FULL_MS);
            alpha = 1f - Math.max(0f, Math.min(1f, (float) t / (float) FADE_OUT_MS));
        }

        // Use RuneLite color scheme for a clean, native look
        Color bg = ColorScheme.DARKER_GRAY_COLOR;
        Color border = ColorScheme.DARK_GRAY_COLOR;
        Color titleColor = new Color(210, 180, 64); // gold-ish accent similar to OSRS

        g.setComposite(AlphaComposite.SrcOver.derive(alpha));

        // Subtle drop shadow
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(2, 3, boxW, boxH, ARC, ARC);

        // Panel background and border
        g.setColor(bg);
        g.fillRoundRect(0, 0, boxW, boxH, ARC, ARC);
        g.setColor(border);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(0, 0, boxW, boxH, ARC, ARC);

        int x = PADDING;
        int y = PADDING;

        // Draw item image
        if (img != null) {
            int imgY = y + (contentH - imgH) / 2;
            g.drawImage(img, x, imgY, null);
            x += imgW + (imgW > 0 ? SPACING : 0);
        }

        // Draw title and name stacked
        g.setFont(titleFont);
        g.setColor(titleColor);
        int titleY = y + fmTitle.getAscent();
        g.drawString(title, x, titleY);

        g.setFont(nameFont);
        g.setColor(Color.WHITE);
        int nameY = titleY + 4 + fmName.getAscent();
        g.drawString(name, x, nameY);

        return new Dimension(boxW, boxH);
    }

    private static class Notification {
        final int itemId;
        final String itemName;
        BufferedImage image;

        Notification(int itemId, String itemName) {
            this.itemId = itemId;
            this.itemName = itemName;
        }
    }
}
