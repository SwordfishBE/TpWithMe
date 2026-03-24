# TpWithMe – Teleport With Me

Your mount follows you through every teleport — commands, plugins, portals, you name it. Stay seated, arrive together.

---

## ✨ Features

- 🐴 Mount teleports with you on every `/tp`, `/home`, portal, or any other teleport
- 🧑‍🤝‍🧑 Player stays seated on the mount after teleporting
- 🌍 Cross-dimensional travel — Overworld ↔ Nether ↔ End *(configurable)*
- 🔒 Safety check — mount won't teleport into solid blocks *(configurable)*
- 🛡️ Damage resistance applied to mount during transition to prevent death
- 🚫 Entity blacklist — exclude specific mounts from ever teleporting
- 🔁 Post-teleport remount watcher — catches edge-case dismounts and fixes them automatically

---

## 🐴 Supported Mounts

All 14 vanilla rideable entities:

| Mount | Control item | Notes |
|---|---|---|
| Horse | Saddle | |
| Donkey | Saddle | |
| Mule | Saddle | |
| Skeleton Horse | Saddle | |
| Zombie Horse | Saddle | |
| Camel | Saddle | Auto lies down if space is tight |
| Camel Husk | Saddle | Auto lies down if space is tight |
| Pig | Saddle | Also needs carrot-on-a-stick to steer |
| Strider | Saddle | Also needs warped-fungus-on-a-stick |
| Nautilus | Saddle | Tame first with pufferfish |
| Zombie Nautilus | Saddle | Tame first |
| Llama | *(none — lead-controlled)* | No saddle check |
| Trader Llama | *(none — lead-controlled)* | No saddle check |
| Happy Ghast | Harness | Checks for harness in body slot |

---

## 🎮 Commands

| Command | Permission | Description |
|---|---|---|
| `/tpwithme info` | Everyone | Show current config values in chat |
| `/tpwithme reload` | Operator (gamemaster) | Reload `tpwithme.json` from disk |

---

## ⚙️ Configuration

File: `config/tpwithme.json`  
Created automatically on first launch. Use `/tpwithme reload` to apply changes without restarting.

```json
{
  "enabled": true,
  "crossDimensionalTeleport": true,
  "requireSaddle": true,
  "checkSafety": true,
  "applyTeleportProtection": true,
  "protectionDurationTicks": 60,
  "safetySearchRadius": 2,
  "blacklistedEntities": []
}
```

### 🔄 Options

#### `enabled`
Master switch. Set to `false` to disable the mod entirely without removing it.  
Default: `true`

#### `crossDimensionalTeleport`
Allow mounts to follow through dimension changes (Overworld ↔ Nether ↔ End).  
When `false`, only same-dimension teleports carry the mount.  
Default: `true`

#### `requireSaddle`
Only teleport a mount if it has the appropriate control item equipped:
- **Saddle** — Horse, Donkey, Mule, Skeleton Horse, Zombie Horse, Camel, Camel Husk, Pig, Strider, Nautilus, Zombie Nautilus
- **Harness** — Happy Ghast
- **Exempt** — Llama and Trader Llama (lead-controlled, no saddle slot)

Default: `true`

#### `checkSafety`
Before teleporting, check whether there is enough room at the destination for the mount to stand upright and the rider to sit on top.  
If the space is too small, the mount stays behind and a message is sent to the player.

Uses direct block collision shape iteration — reliable even for large mounts like Happy Ghast (4×4×4 blocks).  
Default: `true`

#### `applyTeleportProtection`
Apply **Resistance V** to the mount for `protectionDurationTicks` ticks immediately after teleporting. This prevents death from suffocation or fall damage during the transition.  
Default: `true`

#### `protectionDurationTicks`
Duration of the post-teleport damage immunity in game ticks (20 ticks = 1 second).  
Default: `60` (3 seconds)

#### `safetySearchRadius`
When `checkSafety` is `true` and the exact destination is blocked, the mod searches for a safe nearby position within this radius (in blocks).

Search priority: Y upward first (most natural), then X/Z horizontal offsets, then Y downward.

Set to `0` to disable the search and only check the exact destination.  
Default: `2`

#### `blacklistedEntities`
A list of entity type IDs that must **never** teleport, regardless of other settings.  
Use namespaced IDs, e.g. `"minecraft:horse"` or `"mymod:custom_mount"`.  
Default: `[]` (empty)

---

## 🔨 How It Works

TpWithMe uses a Mixin on `ServerPlayer#teleport(TeleportTransition)`:

1. **HEAD injection** — before the player teleports, the current vehicle is captured and eligibility is checked (saddle, blacklist, cross-dim flag).
2. The player teleports normally (Minecraft handles dismounting and moving the player).
3. **RETURN injection** — after the player arrives, destination chunks are now loaded. The safety check runs against actual block collision shapes. If safe, the vehicle is teleported to the player's new position and `startRiding()` is called.
4. **RemountWatcher** — 3 ticks later, a tick listener verifies the player is still mounted. If Minecraft forced a dismount (rare edge case), the vehicle is moved to the player and remount is attempted again.

---

## 🧑‍🤝‍🧑 Compatibility

| Mod | Status |
|-----|--------|
| [Essential Commands](https://modrinth.com/mod/essential-commands) | ✅ Compatible |
| [Fabric Essentials](https://modrinth.com/mod/melius-essentials) | ✅ Compatible |
| [Leashed Teleport](https://github.com/SwordfishBE/LeashedTeleport) | ✅ Compatible |


### Other mods
If another mod forcibly dismounts the player *before* TpWithMe's HEAD injection, the vehicle cannot be captured. The RemountWatcher may recover this in some cases. Please open an issue if you encounter a conflict.

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in `mods/`.
3. Download `tpwithme-<version>.jar` and place it in `mods/`.
4. Launch Minecraft. The config is created automatically on first run.

Download from [GitHub](https://github.com/SwordfishBE/TpWithMe/releases), [Modrinth](https://modrinth.com/mod/tpwithme) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/tpwithme).

---

## 🧱 Building from Source

```bash
git clone https://github.com/SwordfishBE/TpWithMe.git
cd TpWithMe
chmod +x gradlew
./gradlew build
# Output: build/libs/tpwithme-<version>.jar
```

**Requirements:** JAVA JDK 25, Gradle 9.4

---

## 📄 License

Released under the [AGPL-3.0 License](LICENSE).
