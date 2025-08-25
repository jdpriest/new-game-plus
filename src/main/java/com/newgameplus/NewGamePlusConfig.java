package com.newgameplus;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("newgameplus")
public interface NewGamePlusConfig extends Config {

    @ConfigItem(
            keyName = "inventoryOpacity",
            name = "Inventory dim opacity",
            description = "Opacity (0-255) used to dim locked items in the inventory"
    )
    @Range(min = 0, max = 255)
    default int inventoryOpacity() {
        return 165; // ~50%
    }

    @ConfigItem(
            keyName = "bankOpacity",
            name = "Bank dim opacity",
            description = "Opacity (0-255) used to dim locked items in the bank"
    )
    @Range(min = 0, max = 255)
    default int bankOpacity() {
        return 165; // ~50%
    }


    @ConfigItem(
            keyName = "showUnlockPopup",
            name = "Show unlock popup",
            description = "Show a popup with the item icon and name when an item is unlocked"
    )
    default boolean showUnlockPopup() {
        return true;
    }

    @ConfigItem(
            keyName = "playUnlockSound",
            name = "Play unlock sound",
            description = "Play a custom sound located at /.runelite/new-game-plus/new-game-plus-unlock.wav when an item is unlocked."
    )
    default boolean playUnlockSound() {
        return true;
    }

    @ConfigItem(
            keyName = "unlockSoundVolume",
            name = "Unlock sound volume",
            description = "Volume for the unlock sound (0-100)"
    )
    @Range(min = 0, max = 100)
    default int unlockSoundVolume() {
        return 100;
    }
}
