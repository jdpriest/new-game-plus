# New Game Plus

Locks items in the bosses, raids, and slayer collection log until you receive them as a drop. The purpose is to
allow you to re-do the PvM progression of the game, without having to train skills or collect supplies again.

## Features

- **Lock lists**: Bosses, Raids, and Slayer item families are locked by default using name-based families defined in
  `src/main/resources/default-locks/`.
- **Interaction blocking for locked items**: Prevents using specific actions on locked items:
    - Disallowed: `Wear`, `Wield`, `Use`, `Check`, `Revert`, and all `Withdraw*` variants.
    - Allowed: `Examine`, `Cancel`, `Deposit`, `Take`, view/select, etc.
- **Locked item visual dimming**: Locked items are tinted in the inventory and bank with configurable opacity.
- **Unlock functionality**: Receiving or crafting an item will unlock it, allowing interactions and adding it to the list of unlocks.
- **Automatic unlocks** on:
    - NPC drops.
    - Chest/raid rewards.
    - Item entering inventory (e.g., ground pickups, crafting/skilling, shops, etc.).
- Each unlock shows a chat message.
- **Unlock popup overlay**: Shows the item icon and name (toggleable).
- **Unlock sound**: Plays a custom sound file with configurable volume (toggleable).
- **Unlocks panel**: A sidebar panel to add/remove unlocks and view everything you’ve unlocked so far.
- **Persistent unlocks**: All unlocks are saved and restored between sessions.

## How it works

- The plugin determines whether an item is locked by ID and by name family.
    - "Name family" supports item variants, such as cosmetic kits (Oathplate helm -> Radiant oathplate helm) or imbues (Berserker ring -> Berserker ring (i))
- Server NPC loot and chest/raid rewards also unlock items immediately.
- The plugin does not delete or move items; it visually dims and blocks certain interactions while locked.

### Default lock lists

Lock families are defined by plain-text lists:

- `src/main/resources/default-locks/bosses.txt`
- `src/main/resources/default-locks/raids.txt`
- `src/main/resources/default-locks/slayer.txt`

Each line represents a name family. The plugin compiles patterns from these names to match variants (token-based,
in-order match with flexible prefixes/suffixes).

## Configuration

Accessible via RuneLite settings under “New Game Plus”.

- **Inventory dim opacity** (`inventoryOpacity`, 0–255, default 165): Dimming alpha for locked items in inventory.
- **Bank dim opacity** (`bankOpacity`, 0–255, default 165): Dimming alpha for locked items in bank.
- **Show unlock popup** (`showUnlockPopup`, default true): Show a center-top popup with item icon and name when
  unlocking.
- **Play unlock sound** (`playUnlockSound`, default true): Play a custom sound when unlocking.
- **Unlock sound volume** (`unlockSoundVolume`, 0–100, default 100): Volume for the unlock sound.

Sound file path (optional):

```
~/.runelite/new-game-plus/new-game-plus-unlock.wav
```

If the file exists and sound is enabled, it will play on unlock.

## Using the panel

- Open the sidebar panel “New Game Plus”.
- **Add**: Opens an in-game chatbox item search restricted to lockable families; pick an item to add to unlocks.
- **Remove Selected**: Remove highlighted entries from unlocks.
- **Delete All Unlocks**: Clear all unlocks (confirmation required).

### Credits
Inspired by Gudi's plugin for Drum's "Pet Peeved" series. Special thank you to Gray Nine for providing resources to pre-compiled lists of boss, raid, and slayer unlocks.
