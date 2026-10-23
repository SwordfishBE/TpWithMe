# TpWithMe – Teleport With Me

[![GitHub Release](https://img.shields.io/github/v/release/SwordfishBE/TpWithMe?style=for-the-badge&logo=github)](https://github.com/SwordfishBE/TpWithMe/releases)
[![GitHub Downloads](https://img.shields.io/github/downloads/SwordfishBE/TpWithMe/total?style=for-the-badge&logo=github)](https://github.com/SwordfishBE/TpWithMe/releases)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/wc6Vjaxn?style=for-the-badge&logo=modrinth&label=modrinth%20downloads)](https://modrinth.com/mod/tpwithme)
[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1492770?style=for-the-badge&logo=curseforge&label=curseforge%20downloads)](https://www.curseforge.com/minecraft/mc-mods/tpwithme)

Your mount follows you through every teleport — commands, plugins, portals, you name it. Stay seated, arrive together.

---

## ✨ Features

- 🐴 Mount teleports with you on every `/tp`, `/home`, portal, ender pearl, chorus fruit, or any other teleport
- 🧑‍🤝‍🧑 Player stays seated on the mount after teleporting
- 🌍 Cross-dimensional travel — Overworld ↔ Nether ↔ End *(configurable)*
- 🔒 Safety check — mount won't teleport into solid blocks *(configurable)*
- 🛡️ Damage resistance applied to mount during transition to prevent death
- 🚫 Entity blacklist — exclude specific mounts from ever teleporting
- 🔁 Post-teleport remount watcher — catches edge-case dismounts and fixes them automatically
- 🧩 Optional Open Parties and Claims support — respects claim access, chorus fruit rules, and ender pearl barrier groups
- ⚙️ Optional Mod Menu + Cloth Config support for an in-game client config screen
- 🔄 Supports Moonrise, C2ME and Async

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
| `/tpwithme info` | Operator (gamemaster) | Show current config values in chat |
| `/tpwithme reload` | Operator (gamemaster) | Reload `tpwithme.json` from disk |

---

## ⚙️ Configuration

File: `config/tpwithme.json`  
Created automatically on first launch. Use `/tpwithme reload` to apply changes without restarting.

If [Mod Menu](https://modrinth.com/mod/modmenu) is installed on the client, TpWithMe exposes a config screen there.
If [Cloth Config](https://modrinth.com/mod/cloth-config) is also installed, that screen is fully editable in-game.
Both dependencies are optional and are not required on dedicated servers.

```json
{
  "enabled": true,
  "useLuckPerms": false,
  "crossDimensionalTeleport": true,
  "enderPearlTeleport": true,
  "chorusFruitTeleport": true,
  "respectOpenPartiesAndClaims": true,
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

#### `useLuckPerms`
Enable LuckPerms permission checks when the `luckperms` mod is installed.

If `true` and LuckPerms is present, TpWithMe checks:
- `tpwithme.use`
- `tpwithme.crossdimensionalteleport`
- `tpwithme.enderpearlteleport`
- `tpwithme.chorusfruitteleport`

When LuckPerms is active, players must be explicitly granted these permissions.  
If LuckPerms is not installed, TpWithMe automatically falls back to allowing everyone to use the mod.  
Default: `false`

#### `crossDimensionalTeleport`
Allow mounts to follow through dimension changes (Overworld ↔ Nether ↔ End).  
When `false`, only same-dimension teleports carry the mount.  
Default: `true`

#### `enderPearlTeleport`
Allow ender pearls to take your mount along.  
When `false`, mounted ender pearls use vanilla behaviour and only teleport the player.  
Default: `true`

#### `chorusFruitTeleport`
Allow chorus fruit to take your mount along.  
Grounding uses actual collision surfaces, including slabs, and respects Minecraft's `consumable_does_not_teleport_to` block tag (including datapack additions).
When `false`, mounted chorus fruit uses vanilla behaviour and only teleports the player.  
A successful vanilla chorus teleport cancels older mount-transfer waits and remount recovery; a failed attempt leaves them intact.
Default: `true`

#### `respectOpenPartiesAndClaims`
Respect [Open Parties and Claims](https://modrinth.com/mod/open-parties-and-claims) claim protection when it is installed.

TpWithMe checks claim access before moving the mount into a claim. For mounted chorus fruit it respects OPAC's chorus fruit exception option. For mounted ender pearls it respects OPAC's configured Ender Pearls entity barrier group when present.

Every player riding the mount must have the required TpWithMe permissions and destination access. If any rider is denied, the mount and its remaining passengers stay behind. This applies to general teleports, pearls, chorus fruit and transfers that wait for terrain.

Disable this only if you deliberately want TpWithMe to ignore OPAC claim protection.
If OPAC is installed but its API fails, mount carry is blocked until the server restarts or this setting is explicitly disabled. The mod does not silently allow protected destinations.
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

Uses actual collision-shape intersections and checks the entire mount footprint against the world border. Nearby searching does not load new chunks. Flying mounts do not require ground; nautilus mounts can land in water and striders on lava surfaces.  
For general teleports (such as `/home`), missing destination terrain is not treated as blocked space. The mount can follow once the terrain becomes available, for up to 100 server ticks (5 seconds at 20 TPS). A new teleport can carry a still-valid waiting mount to the latest destination, including a quick B-A-B return. Safety, permissions and OPAC are checked again. Moving away, a moved mount, changed passengers, an expired wait or disconnect cancels the pending transfer.

An already loaded safe alternative can be used without waiting for the entire search area. While terrain is incomplete, the bounded search retries every five ticks. Existing co-riders are tracked by UUID; a passenger change cancels the waiting transfer. Waterlogged support blocks are allowed when their solid surface keeps the mount above the water; submerged destinations remain restricted.

A loaded mount in a temporarily hidden entity section can still follow. If the source mount cannot currently be found, the bounded wait remains active instead of immediately reporting that it moved. Each successful follow-up general teleport starts its own 100-tick destination wait, so returning to A does not inherit B's nearly expired timeout. Ordinary retries do not reset the timer. Source chunks are never loaded by this lookup.

An eligible mount's already loaded source chunk is briefly retained with a radius-zero, nonpersistent ticket before the player moves. The ticket expires automatically after 120 ticks (6 seconds at 20 TPS), preventing rapid returns from losing their source entity without leaving chunks permanently loaded. Unloaded source chunks do not receive this ticket; safety searches still do not generate destination terrain.
Default: `true`

#### `applyTeleportProtection`
Apply **Resistance V** to the mount for `protectionDurationTicks` ticks immediately after teleporting. This prevents death from suffocation or fall damage during the transition.  
Default: `true`

#### `protectionDurationTicks`
Duration of the post-teleport damage immunity in game ticks (20 ticks = 1 second).  
Default: `60` (3 seconds)

#### `safetySearchRadius`
When `checkSafety` is `true` and the exact destination is blocked, the mod searches for a safe nearby position within this radius (in blocks).

Searches horizontally, upward and downward, choosing the nearest safe candidate by distance. Actual collision surfaces (including slabs) are considered, so fractional pearl impact heights can be adjusted to the ground. The radius limits displacement on each axis. The selected destination still undergoes world-border and OPAC checks; searching does not load new chunks.

Set to `0` to disable the search and only check the exact destination.  
Allowed range: `0` to `4`. Larger values are clamped to prevent excessive server tick work.  
Default: `2`

#### `blacklistedEntities`
A list of entity type IDs that must **never** teleport, regardless of other settings.  
Use namespaced IDs, e.g. `"minecraft:horse"` or `"mymod:custom_mount"`.  
Default: `[]` (empty)

---

## 🔐 LuckPerms

TpWithMe only uses LuckPerms when both conditions are true:

1. `useLuckPerms` is set to `true` in `config/tpwithme.json`
2. The `luckperms` mod is actually installed on the server

If either condition is false, everyone can use TpWithMe and no permission plugin is required.
If LuckPerms is active, missing permission nodes default to `false`.

### Permission nodes

| Permission | Description |
|---|---|
| `tpwithme.use` | Allow a player to take their mount along during teleports |
| `tpwithme.crossdimensionalteleport` | Allow a player to take their mount across dimensions |
| `tpwithme.enderpearlteleport` | Allow a player to take their mount along with an ender pearl |
| `tpwithme.chorusfruitteleport` | Allow a player to take their mount along when eating chorus fruit |

### Example groups

```yaml
group.default:
  permissions:
    - tpwithme.use

group.vip:
  permissions:
    - tpwithme.use
    - tpwithme.crossdimensionalteleport
    - tpwithme.enderpearlteleport
    - tpwithme.chorusfruitteleport
```

### Example commands

```bash
/lp group default permission set tpwithme.use true
/lp group default permission set tpwithme.crossdimensionalteleport false
/lp group default permission set tpwithme.enderpearlteleport false
/lp group default permission set tpwithme.chorusfruitteleport false

/lp group vip permission set tpwithme.use true
/lp group vip permission set tpwithme.crossdimensionalteleport true
/lp group vip permission set tpwithme.enderpearlteleport true
/lp group vip permission set tpwithme.chorusfruitteleport true
```

### `/tpwithme info`

`/tpwithme info` now also shows whether LuckPerms is:
- `disabled`
- `configured, but mod not installed`
- `active`

---

## 🧑‍🤝‍🧑 Compatibility

This mod works with Moonrise, C2ME and Async
Some of these mods cannot be loaded together.

| Mod | Status |
|-----|--------|
| [Essential Commands](https://modrinth.com/mod/essential-commands) | ✅ Compatible |
| [Fabric Essentials](https://modrinth.com/mod/melius-essentials) | ✅ Compatible |
| [Moonrise](https://modrinth.com/mod/moonrise-opt) | ✅ Compatible |
| [C2ME](https://modrinth.com/mod/c2me-fabric) | ✅ Compatible |
| [Async](https://modrinth.com/mod/async) | ✅ Compatible |
| [Leashed Teleport](https://github.com/SwordfishBE/LeashedTeleport) | ✅ Compatible |

### Other mods
If another mod forcibly dismounts the player before TpWithMe captures the vehicle, the mount cannot be recovered. Please open an issue if you encounter a conflict.

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in `mods/`.
3. Download `tpwithme-<version>.jar` from one of the platforms below and place it in `mods/`.
4. Optional: install [LuckPerms](https://modrinth.com/mod/luckperms) if you want permission-based access control.
5. Optional: install [Mod Menu](https://modrinth.com/mod/modmenu) to access TpWithMe from the mods list.
6. Optional: install [Cloth Config](https://modrinth.com/mod/cloth-config) if you want the in-game config GUI.
7. Launch Minecraft. The config is created automatically on first run.

### Downloads

- [GitHub Releases](https://github.com/SwordfishBE/TpWithMe/releases)
- [Modrinth](https://modrinth.com/mod/tpwithme)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/tpwithme)

---

## 🧱 Building from Source

```bash
git clone https://github.com/SwordfishBE/TpWithMe.git
cd TpWithMe
chmod +x gradlew
./gradlew build
# Output: build/libs/tpwithme-<version>.jar
```

---

## 📄 License

Released under the [AGPL-3.0 License](LICENSE).