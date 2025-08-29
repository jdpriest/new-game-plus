package com.newgameplus;

import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.ScriptCallbackEvent;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageCapture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.runelite.client.RuneLite;

@Slf4j
@PluginDescriptor(
        name = "New Game Plus"
)
public class NewGamePlusPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private NewGamePlusConfig config;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private NewGamePlusOverlay overlay;

    @Inject
    private NewGamePlusUnlockOverlay unlockOverlay;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ClientUI clientUI;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ChatboxItemSearch chatboxItemSearch;

    @Inject
    private DrawManager drawManager;

    @Inject
    private ImageCapture imageCapture;

    private NavigationButton navButton;
    private NewGamePlusPanel panel;

    // Background executor for playing custom unlock sounds
    private ExecutorService audioExecutor;

    // Background executor for saving screenshots (scheduled to allow delayed capture)
    private ScheduledExecutorService screenshotExecutor;

    // In-memory set of unlocked item IDs. Initially empty -> everything is locked.
    private final Set<Integer> unlockedItemIds = new HashSet<>();

    // Snapshot of inventory counts used to detect newly added items (treated as picked up -> unlock)
    private final Map<Integer, Integer> inventoryCounts = new HashMap<>();

    // Guard to avoid treating the initial inventory load as "picked up"
    private boolean inventorySnapshotInitialized = false;

    // Compiled patterns representing unlocked name families (variants)
    private final List<Pattern> unlockedNamePatterns = new ArrayList<>();

    // Compiled patterns for default-locked name families loaded from resources
    private final List<Pattern> defaultLockedPatterns = new ArrayList<>();

    @Override
    protected void startUp() throws Exception {
        log.info("New Game Plus started!");
        // Load persisted unlocks
        loadUnlockedFromConfig();
        rebuildUnlockedNames();
        loadDefaultLockedNames();
        inventorySnapshotInitialized = false;
        // Defer inventory snapshot to when we are LOGGED_IN, on the client thread
        clientThread.invoke(() -> {
            if (client.getGameState() == GameState.LOGGED_IN) {
                initializeInventorySnapshot();
            }
        });

        // Register overlay to dim inventory items
        overlayManager.add(overlay);
        // Register unlock popup overlay
        overlayManager.add(unlockOverlay);

        // Prepare audio executor and ensure custom sound directory exists
        audioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ngp-audio");
            t.setDaemon(true);
            return t;
        });
        try {
            Path dir = RuneLite.RUNELITE_DIR.toPath().resolve("new-game-plus");
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }

        // Prepare screenshot executor (scheduled) for delayed capture
        screenshotExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ngp-screenshot");
            t.setDaemon(true);
            return t;
        });

        // Create and add sidebar panel
        panel = new NewGamePlusPanel(this, itemManager, client);
        BufferedImage icon = null;
        try {
            java.net.URL res = NewGamePlusPlugin.class.getResource("/newgameplus-icon.png");
            if (res != null) {
                synchronized (javax.imageio.ImageIO.class) {
                    icon = javax.imageio.ImageIO.read(res);
                }
                if (icon != null && (icon.getWidth() != 16 || icon.getHeight() != 16)) {
                    icon = ImageUtil.resizeImage(icon, 16, 16);
                }
            }
        } catch (Exception e) {
            // ignore and fallback below
        }
        if (icon == null) {
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        }
        navButton = NavigationButton.builder()
                .tooltip("New Game Plus")
                .icon(icon)
                .priority(7)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);
        // Ensure the panel reflects any unlocks just loaded from config
        if (panel != null) {
            panel.refreshFromPlugin();
        }
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("New Game Plus stopped!");
        unlockedItemIds.clear();
        inventoryCounts.clear();
        inventorySnapshotInitialized = false;
        overlayManager.remove(overlay);
        if (unlockOverlay != null) {
            overlayManager.remove(unlockOverlay);
            unlockOverlay.clear();
        }
        if (audioExecutor != null) {
            audioExecutor.shutdownNow();
            audioExecutor = null;
        }
        if (screenshotExecutor != null) {
            screenshotExecutor.shutdownNow();
            screenshotExecutor = null;
        }
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
            panel = null;
        }
    }

    @Subscribe
    public void onServerNpcLoot(final ServerNpcLoot event) {


        final java.util.Collection<net.runelite.client.game.ItemStack> items = event.getItems();
        if (items == null || items.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (net.runelite.client.game.ItemStack is : items) {
            if (is == null) {
                continue;
            }
            int id = is.getId();
            if (id <= 0) {
                continue;
            }
            // Only auto-unlock if the item is currently locked
            if (isLocked(id) && unlockedItemIds.add(id)) {
                changed = true;
                // If the item was locked, unlock it and notify the user with selected configuration notifications
                try {
                    String name = itemManager.getItemComposition(id).getName();
                    postGameMessage(ColorUtil.prependColorTag("NG+: Unlocked " + name + "!", new Color(197, 27, 138)));
                    showUnlockPopup(id);
                    playUnlockSound();
                    takeUnlockScreenshot(name);
                } catch (Exception ignored) {
                }
            }
        }

        if (changed) {
            saveUnlockedToConfig();
            rebuildUnlockedNames();
            if (panel != null) {
                panel.refreshFromPlugin();
            }
        }
    }

    @Subscribe
    public void onLootReceived(final LootReceived event) {


        final LootRecordType type = event.getType();
        // Only handle chest-style/non-NPC rewards; NPC kills handled by onServerNpcLoot
        if (type != LootRecordType.EVENT) {
            return;
        }

        final java.util.Collection<net.runelite.client.game.ItemStack> items = event.getItems();
        if (items == null || items.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (net.runelite.client.game.ItemStack is : items) {
            if (is == null) {
                continue;
            }
            final int id = is.getId();
            if (id <= 0) {
                continue;
            }
            if (isLocked(id) && unlockedItemIds.add(id)) {
                changed = true;
                try {
                    String name = itemManager.getItemComposition(id).getName();
                    postGameMessage(ColorUtil.prependColorTag("NG+: Unlocked " + name + "!", new Color(197, 27, 138)));
                    showUnlockPopup(id);
                    playUnlockSound();
                    takeUnlockScreenshot(name);
                } catch (Exception ignored) {
                }
            }
        }

        if (changed) {
            saveUnlockedToConfig();
            rebuildUnlockedNames();
            if (panel != null) {
                panel.refreshFromPlugin();
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
            // Reset and initialize snapshot on the client thread after login
            inventorySnapshotInitialized = false;
            clientThread.invoke(this::initializeInventorySnapshot);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"newgameplus".equals(event.getGroup())) {
            return;
        }
        if ("unlockedItemIds".equals(event.getKey())) {
            loadUnlockedFromConfig();
            rebuildUnlockedNames();
            if (panel != null) {
                panel.refreshFromPlugin();
            }
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick) {
        // Keep blocked entries visible but faded (deprioritized); leave allowed entries (e.g., Take/Examine/Cancel) as-is
        MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) {
            return;
        }

        boolean mutated = false;
        for (MenuEntry entry : entries) {
            if (!allowMenuEntry(entry)) {
                // Fade out disallowed entries on locked items
                entry.setDeprioritized(true);
                String option = entry.getOption();
                if (option != null && !isColored(option)) {
                    entry.setOption(ColorUtil.prependColorTag(option, Color.GRAY));
                }
                String target = entry.getTarget();
                if (target != null) {
                    entry.setTarget(ColorUtil.prependColorTag(Text.removeTags(target), Color.GRAY));
                }
                mutated = true;
            }
        }

        if (mutated) {
            client.setMenuEntries(entries);
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        final int itemId = event.getItemId();
        final String option = event.getOption();
        if (itemId <= 0 || option == null) {
            return;
        }

        if (allowOption(itemId, option)) {
            return;
        }

        MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) {
            return;
        }
        MenuEntry last = entries[entries.length - 1];
        last.setDeprioritized(true);
        String lastOpt = last.getOption();
        if (lastOpt != null && !isColored(lastOpt)) {
            last.setOption(ColorUtil.prependColorTag(lastOpt, Color.GRAY));
        }
        String target = last.getTarget();
        if (target != null) {
            last.setTarget(ColorUtil.prependColorTag(Text.removeTags(target), Color.GRAY));
        }
        client.setMenuEntries(entries);
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event)
    {
        // Integrate with bank search filtering. When the user searches exactly "unlocked" (or "locked"),
        // filter bank items by our lock state.
        if (!"bankSearchFilter".equals(event.getEventName()))
        {
            return;
        }

        // Stack layout mirrors BankPlugin's usage: top of int stack has itemId,
        // and writing 1 to intStack[intStackSize - 2] signals a match.
        int[] intStack = client.getIntStack();
        Object[] objectStack = client.getObjectStack();
        int intStackSize = client.getIntStackSize();
        int objectStackSize = client.getObjectStackSize();

        if (intStackSize < 2 || objectStackSize < 1)
        {
            return;
        }

        final int itemId = intStack[intStackSize - 1];
        final Object searchObj = objectStack[objectStackSize - 1];
        if (!(searchObj instanceof String))
        {
            return;
        }
        final String searchRaw = (String) searchObj;
        final String search = Text.removeTags(searchRaw).toLowerCase().trim();

        if (search.equals("is:unlocked"))
        {
            // Only include items that were default-locked by the plugin and are now unlocked
            if (wasDefaultLocked(itemId) && !isLocked(itemId))
            {
                intStack[intStackSize - 2] = 1; // match
            }
        }
        else if (search.equals("is:locked"))
        {
            if (isLocked(itemId))
            {
                intStack[intStackSize - 2] = 1; // match
            }
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        MenuEntry entry = event.getMenuEntry();
        if (entry == null) {
            return;
        }

        final String option = entry.getOption();
        final int itemId = entry.getItemId();
        // Only consume item interactions that are not allowed for locked items.
        if (itemId > 0 && !allowOption(itemId, option)) {
            event.consume();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) {
            return;
        }

        ItemContainer container = event.getItemContainer();
        if (container == null) {
            return;
        }

        // On the first inventory container load after login, build the baseline snapshot and do NOT unlock items
        if (!inventorySnapshotInitialized) {
            inventoryCounts.clear();
            for (Item item : container.getItems()) {
                if (item == null) {
                    continue;
                }
                int id = item.getId();
                if (id <= 0) {
                    continue;
                }
                inventoryCounts.merge(id, item.getQuantity(), Integer::sum);
            }
            inventorySnapshotInitialized = true;
            return;
        }

        // Detect newly added items and unlock those item IDs
        Map<Integer, Integer> newCounts = new HashMap<>();
        for (Item item : container.getItems()) {
            if (item == null) {
                continue;
            }
            int id = item.getId();
            if (id <= 0) {
                continue;
            }
            newCounts.merge(id, item.getQuantity(), Integer::sum);
        }

        for (Map.Entry<Integer, Integer> e : newCounts.entrySet()) {
            int id = e.getKey();
            int newQty = e.getValue();
            int oldQty = inventoryCounts.getOrDefault(id, 0);
            if (newQty > oldQty) {
                if (isLocked(id) && unlockedItemIds.add(id)) {
                    log.debug("Unlocked item id {} via inventory increase ({} -> {})", id, oldQty, newQty);
                    try {
                        String name = itemManager.getItemComposition(id).getName();
                        postGameMessage(ColorUtil.prependColorTag("NG+: Unlocked " + name + "!", new Color(197, 27, 138)));
                        showUnlockPopup(id);
                        playUnlockSound();
                        takeUnlockScreenshot(name);
                    } catch (Exception ignored) {
                    }
                    saveUnlockedToConfig();
                    rebuildUnlockedNames();
                    if (panel != null) {
                        panel.refreshFromPlugin();
                    }
                }
            }
        }

        // Update snapshot
        inventoryCounts.clear();
        inventoryCounts.putAll(newCounts);
    }

    private void initializeInventorySnapshot() {
        // Rebuild snapshot fresh each time
        inventoryCounts.clear();
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) {
            return;
        }
        for (Item item : inv.getItems()) {
            if (item == null) {
                continue;
            }
            int id = item.getId();
            if (id <= 0) {
                continue;
            }
            inventoryCounts.merge(id, item.getQuantity(), Integer::sum);
        }
        inventorySnapshotInitialized = true;
    }

    private boolean allowMenuEntry(MenuEntry entry) {
        // Only consider entries tied to an item id
        int itemId = entry.getItemId();
        if (itemId <= 0) {
            return true; // not an item interaction
        }

        return allowOption(itemId, entry.getOption());
    }

    private boolean allowOption(int itemId, String option) {
        // If item is unlocked, allow normal behavior
        if (!isLocked(itemId)) {
            return true;
        }

        // Blacklist approach: disallow specific actions on locked items; allow everything else
        if (option == null) {
            return true; // not an actionable verb we recognize; allow
        }

        // Compare without any color tags, case-insensitive
        String opt = Text.removeTags(option).toLowerCase();

        // Disallowed verbs for locked items
        // - wear, wield, use, check, revert
        // - withdraw and all variants (withdraw-1, withdraw-5, withdraw-all, withdraw-all but one, etc.)
        if (opt.equals("wear")
                || opt.equals("wield")
                || opt.equals("use")
                || opt.equals("check")
                || opt.equals("revert")
                || opt.startsWith("withdraw")) {
            return false;
        }

        // Everything else is allowed (e.g. take, examine, cancel, view/select, deposit, etc.)
        return true;
    }

    // Determine if an item is locked considering user unlocks (by id and by name) and default-locked names
    public boolean isLocked(int itemId) {
        if (itemId <= 0) {
            return false;
        }
        // Explicit user unlocks by id
        if (unlockedItemIds.contains(itemId)) {
            return false;
        }
        // Resolve name for name-based checks
        String normName;
        try {
            String name = itemManager.getItemComposition(itemId).getName();
            normName = normalizeName(name);
        } catch (Exception e) {
            // If we cannot resolve the name, do not over-lock
            return false;
        }

        // User unlocks by name families (variant unlocking via wildcard patterns)
        for (Pattern p : unlockedNamePatterns) {
            if (p.matcher(normName).matches()) {
                return false;
            }
        }

        // Apply default locks (name families via wildcard patterns)
        for (Pattern p : defaultLockedPatterns) {
            if (p.matcher(normName).matches()) {
                return true;
            }
        }

        // Otherwise unlocked by default
        return false;
    }

    // Determine if an item would be considered default-locked based on name families
    private boolean wasDefaultLocked(int itemId)
    {
        if (itemId <= 0)
        {
            return false;
        }
        String normName;
        try
        {
            String name = itemManager.getItemComposition(itemId).getName();
            normName = normalizeName(name);
        }
        catch (Exception e)
        {
            return false;
        }
        for (Pattern p : defaultLockedPatterns)
        {
            if (p.matcher(normName).matches())
            {
                return true;
            }
        }
        return false;
    }

    private boolean isColored(String s) {
        if (s == null) {
            return false;
        }
        String lower = s.toLowerCase();
        return lower.contains("<col=") || lower.contains("</col>");
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return Text.removeTags(name).toLowerCase().trim();
    }

    private void rebuildUnlockedNames() {
        // Ensure we run on the client thread since ItemManager access requires it
        clientThread.invoke(this::rebuildUnlockedNamesUnsafe);
    }

    private void rebuildUnlockedNamesUnsafe() {
        unlockedNamePatterns.clear();
        for (int id : unlockedItemIds) {
            try {
                String nm = itemManager.getItemComposition(id).getName();
                String norm = normalizeName(nm);

                boolean mapped = false;
                // If this unlocked name falls under any default-locked family, unlock that family
                for (Pattern p : defaultLockedPatterns) {
                    if (p.matcher(norm).matches()) {
                        unlockedNamePatterns.add(p);
                        mapped = true;
                    }
                }
                // Otherwise, unlock its own family compiled from the name
                if (!mapped) {
                    unlockedNamePatterns.add(compileNameFamilyPattern(norm));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void postGameMessage(String message) {
        if (message == null) {
            return;
        }
        clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
    }

    private void showUnlockPopup(int itemId) {
        if (!config.showUnlockPopup() || unlockOverlay == null) {
            return;
        }
        clientThread.invoke(() ->
        {
            String nm;
            try {
                nm = client.getItemDefinition(itemId).getName();
            } catch (Exception e) {
                nm = "Unknown item";
            }
            unlockOverlay.enqueueUnlock(itemId, nm);
        });
    }

    private void playUnlockSound() {
        if (audioExecutor == null || !config.playUnlockSound()) {
            return;
        }
        final Path soundPath = RuneLite.RUNELITE_DIR.toPath()
                .resolve("new-game-plus")
                .resolve("new-game-plus-unlock.wav");
        if (!Files.exists(soundPath)) {
            return;
        }

        audioExecutor.submit(() ->
        {
            try {
                int vol = Math.max(0, Math.min(100, config.unlockSoundVolume()));
                // Map 0..100 volume to a reasonable dB range (-80dB = silent, 0dB = no boost)
                float gainDb = -80.0f + (vol / 100.0f) * 80.0f;
                new AudioPlayer().play(soundPath.toFile(), gainDb);
            } catch (Throwable t) {
                log.debug("NG+: unlock sound failed: {}", t.getMessage());
            }
        });
    }

    private void takeUnlockScreenshot(String itemName) {
        if (!config.screenshotOnUnlock()) {
            return;
        }
        if (client.getGameState() == GameState.LOGIN_SCREEN) {
            return;
        }

        final String base = "Unlock (" + (itemName == null ? "item" : itemName) + ")";
        final String fileName = sanitizeFilename(base);

        // Delay to allow the notification/popup to render before capture
        if (screenshotExecutor != null) {
            screenshotExecutor.schedule(() -> {
                // Capture next frame and save asynchronously
                drawManager.requestNextFrameListener(image -> {
                    if (screenshotExecutor == null) {
                        return;
                    }
                    screenshotExecutor.submit(() -> {
                        try {
                            BufferedImage bi = ImageUtil.bufferedImageFromImage(image);
                            imageCapture.saveScreenshot(bi, fileName, "New Game Plus", false, false);
                        } catch (Exception e) {
                            log.debug("NG+: screenshot failed: {}", e.getMessage());
                        }
                    });
                });
            }, 1800, TimeUnit.MILLISECONDS);
        }
    }

    private String sanitizeFilename(String s) {
        if (s == null) {
            return "unlock";
        }
        s = Text.removeTags(s).trim();
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // Load default-locked name families (always enabled)
    private void loadDefaultLockedNames() {
        defaultLockedPatterns.clear();
        readDefaultNamesResource("/default-locks/bosses.txt");
        readDefaultNamesResource("/default-locks/raids.txt");
        readDefaultNamesResource("/default-locks/slayer.txt");
        // Rebuild unlocked name patterns to map unlocks to the current default families
        rebuildUnlockedNames();
    }

    private void readDefaultNamesResource(String resourcePath) {
        try (InputStream is = NewGamePlusPlugin.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.debug("Default lock resource not found: {}", resourcePath);
                return;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String norm = normalizeName(trimmed);
                    defaultLockedPatterns.add(compileNameFamilyPattern(norm));
                }
            }
        } catch (Exception e) {
            log.warn("Failed reading default lock resource {}", resourcePath, e);
        }
    }

    // Build a regex that matches any name containing the tokens (in order), allowing extra words/prefixes/suffixes
    private Pattern compileNameFamilyPattern(String base) {
        if (base == null) {
            base = "";
        }
        String[] tokens = base.toLowerCase().split("[^a-z0-9]+");
        StringBuilder re = new StringBuilder();
        re.append(".*");
        for (String tk : tokens) {
            if (tk.isEmpty()) {
                continue;
            }
            re.append("\\b").append(java.util.regex.Pattern.quote(tk)).append("\\b").append(".*");
        }
        return java.util.regex.Pattern.compile(re.toString());
    }

    private void loadUnlockedFromConfig() {
        String csv = configManager.getConfiguration("newgameplus", "unlockedItemIds");
        unlockedItemIds.clear();
        if (csv == null || csv.isEmpty()) {
            return;
        }
        String[] parts = csv.split(",");
        for (String p : parts) {
            try {
                int id = Integer.parseInt(p.trim());
                if (id > 0) {
                    unlockedItemIds.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void saveUnlockedToConfig() {
        StringBuilder sb = new StringBuilder();
        for (Integer id : unlockedItemIds) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        configManager.setConfiguration("newgameplus", "unlockedItemIds", sb.toString());
    }

    // Read unlocked IDs directly from persisted config
    public java.util.Set<Integer> getUnlockedItemIdsFromConfig() {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        String csv = configManager.getConfiguration("newgameplus", "unlockedItemIds");
        if (csv == null || csv.isEmpty()) {
            return ids;
        }
        String[] parts = csv.split(",");
        for (String p : parts) {
            try {
                int id = Integer.parseInt(p.trim());
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    // Expose helpers for panel
    public java.util.Set<Integer> getUnlockedItemIds() {
        return new java.util.HashSet<>(unlockedItemIds);
    }

    public void addUnlock(int id) {
        if (id <= 0) {
            return;
        }
        if (unlockedItemIds.add(id)) {
            saveUnlockedToConfig();
            rebuildUnlockedNames();
            if (panel != null) {
                panel.refreshFromPlugin();
            }
        }
    }

    public void removeUnlock(int id) {
        if (unlockedItemIds.remove(id)) {
            saveUnlockedToConfig();
            rebuildUnlockedNames();
            if (panel != null) {
                panel.refreshFromPlugin();
            }
        }
    }

    public void clearAllUnlocks() {
        if (unlockedItemIds.isEmpty()) {
            return;
        }
        unlockedItemIds.clear();
        saveUnlockedToConfig();
        rebuildUnlockedNames();
        if (panel != null) {
            panel.refreshFromPlugin();
        }
        postGameMessage("NG+: Cleared all unlocks");
    }

    public String getItemName(int id) {
        try {
            return itemManager.getItemComposition(id).getName();
        } catch (Exception e) {
            return "Unknown item";
        }
    }

    // Build the panel entries on the client thread to satisfy item definition access
    public void refreshPanelAsync(NewGamePlusPanel targetPanel) {
        clientThread.invoke(() ->
        {
            Map<Integer, String> idToName = new HashMap<>();
            for (int id : getUnlockedItemIdsFromConfig()) {
                String name;
                try {
                    name = client.getItemDefinition(id).getName();
                } catch (Exception e) {
                    name = "Unknown item";
                }
                idToName.put(id, name);
            }
            if (targetPanel != null) {
                targetPanel.setEntries(idToName);
            }
        });
    }

    // Open an in-game chatbox search to add an unlocked item by name
    public void openAddItemSearch() {
        clientThread.invoke(() ->
        {
            // Ensure the RuneLite client (and chatbox input) has keyboard focus after clicking the sidebar button
            if (clientUI != null) {
                clientUI.requestFocus();
            }

            chatboxItemSearch
                    .tooltipText("Click a lockable item to add to unlocks")
                    .onItemSelected(id ->
                    {
                        if (id != null && id > 0) {
                            // Only accept items that belong to a default-locked family
                            boolean lockable = false;
                            try {
                                String nm = client.getItemDefinition(id).getName();
                                String norm = normalizeName(nm);
                                for (java.util.regex.Pattern p : defaultLockedPatterns) {
                                    if (p.matcher(norm).matches()) {
                                        lockable = true;
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                            if (!lockable) {
                                postGameMessage("NG+: That item is not in the lockable lists. Pick another.");
                                // Reopen the restricted search to try again
                                openAddItemSearch();
                                return;
                            }

                            addUnlock(id);
                            String name;
                            try {
                                name = client.getItemDefinition(id).getName();
                            } catch (Exception e) {
                                name = "Unknown item";
                            }
                            postGameMessage("NG+: Added " + name + " to unlocks");
                        }
                    })
                    .prompt("Search lockable item to unlock")
                    .build();
        });
    }

    @Provides
    NewGamePlusConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NewGamePlusConfig.class);
    }
}
