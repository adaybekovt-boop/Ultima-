# Ultima — vanilla-client-compatible server hosting

As the host, you install Ultima on your dedicated Fabric server, or on the
Fabric client that will open a singleplayer world to LAN. Guests may connect
with ordinary Minecraft 26.2 — no Ultima install, and no Fabric install.

- **You** get client render optimizations (`retained_terrain`, `java_mesher`,
  temporal / FSR contract, and the other client modules) only on machines that
  actually have Ultima.
- **Everyone** on that server gets the simulation optimizations (collisions,
  entity-section lookup, cursor stepping, full-cube move). Those run where the
  world is simulated. A vanilla guest does not need the mod for that.
- **Host-only diagnostics:** `server_metrics` (default on) records tick
  subsystem timers. It does not change gameplay or packets. Operators can run
  `/ultima profile [seconds]`. Fragile Lithium injection points use
  `require = 0` so a rewritten target skips that one counter instead of
  crashing the server.
- **Opt-in hopper sleeping:** `blockentity_sleeping` (default off) is a
  server-side simulation experiment. Auto-off with Lithium / Canary / Radium.
  Unexpected inspector faults pin that hopper to vanilla polling.

## Operator checklist (modules)

Confirm these in `config/ultima.properties` and the boot log `resolve()` lines
before a hardware session:

| Key | Default | Side | Notes |
|---|---|---|---|
| `server_metrics` | ON | server (host) | Diagnostics only. Keep on for hopper-sleeping A/B. |
| `blockentity_sleeping` | OFF | server simulation | Turn on only for the hopper sleeping hardware test. Lithium family auto-off. |
| `mesher_fast_path` | OFF | client render | Host GPU only; vanilla guests unaffected. |
| `settings_ui` | ON | client UI | Title-screen button when Mod Menu is absent. Not a network contract. |
| collision / entity-section / cursor family | ON | server simulation | Vanilla guests benefit automatically. |

Ultima does not add blocks, items, recipes, custom packets, or a required
client counterpart. Fabric therefore has nothing to reject a vanilla guest for.

## What `fabric.mod.json` does *not* mean

| Field | What it does | What it does **not** do |
|---|---|---|
| `"environment": "*"` | Ultima **may** load on a physical client or a dedicated server | Require the guest to have Ultima. This is not Forge `clientRequired`. |
| `"entrypoints".main` | Runs on dedicated server **and** on the host's integrated server | Send a handshake or mod list |
| `"entrypoints".client` | Fabric's client-only slot (`ClientModInitializer`) | Load render code on a dedicated server |
| mixin `"environment": "*"` (`ultima.mixins.json`) | Apply simulation Mixins on dedicated + integrated server + host client physics | Change the network protocol |
| mixin `"environment": "client"` (`ultima.client.mixins.json`) | Apply render / mesher / temporal Mixins only on a physical client | Run those classes on a dedicated server |
| `"depends"` | The **host** process that loads Ultima needs Fabric Loader, Minecraft 26.2, Java 25, and Fabric API | Ask a remote vanilla client to install any of those |

Fabric Loader has no "both sides must have this mod" flag. A remote client is
rejected only if something during login/configuration actually requires it:
custom registries, a required custom payload, or an opt-in mod-protocol API.
Ultima registers none of those.

`"environment": "server"` would be **wrong** for LAN hosting. In Fabric that
value means dedicated server only; the integrated server is a client process.
LAN "Open to LAN" would then load no Ultima at all.

## Network handshake (audit)

Searched production Java under `src/main` and `src/client`:

- No `PayloadTypeRegistry`, `ServerPlayNetworking`, `ClientPlayNetworking`,
  `CustomPayload` / `CustomPacketPayload`, login/configuration networking, or
  Fabric Mod Protocol registration.
- No `Registry.register` / `BuiltInRegistries` content.
- No `src/main/resources/data` pack that Fabric registry-sync would send.

That is the simple, reliable case: Ultima never opens a custom channel, so
there is no required-channel fallback to configure. A vanilla 26.2 client
speaks the same play protocol as the server.

Fabric API's registry sync (present because the **host** depends on
`fabric-api`) only disconnects a client that cannot receive the sync packet
when the server actually has **non-optional extra registry entries**. Ultima
adds none. Other content mods on the same server can still cause a kick;
that is those mods, not Ultima.

Fabric Loader does not hard-reject a missing Ultima in Server Status / Query
or in the login sequence. Status extras that Fabric clients understand are
ignored by vanilla. Protocol version (Minecraft 26.2) is the only vanilla
join check.

## Who should install what

| Role | Install | Result |
|---|---|---|
| Dedicated server | Fabric Loader + Fabric API + Ultima | Simulation optimizations for every connected player |
| LAN / integrated host | Fabric client + Fabric API + Ultima | Same simulation on the integrated server; host also gets render modules |
| Guest | Vanilla Minecraft 26.2 (official launcher) | Joins; vanilla rendering; benefits from server simulation |
| Guest who also wants FPS work | Fabric client + Ultima | Same join, plus local render modules |

Guests on Fabric **without** Ultima are also fine. They are treated like
vanilla for Ultima's purposes: no Ultima Mixins, no Ultima shaders.

## Hardware verification scenario (do not run in CI / this session)

Use this on a real machine with two Minecraft 26.2 clients. Do not run it
from the cloud agent environment.

### Option A — dedicated server + two clients (preferred)

1. Build `build/libs/ultima-0.1.0.jar` (not the `-sources.jar`).
2. Create a Fabric dedicated server for **Minecraft 26.2** (Loader 0.19.3,
   Fabric API `0.156.0+26.2`). Put Ultima in the server `mods/` folder.
   Do **not** put Ultima in the vanilla guest's game directory.
3. Start the server. Confirm the log contains `Ultima initialized` and that
   simulation modules resolve `enabled` (`cursor_step`,
   `entity_section_lookup`, `block_collision_shape`, `collision_shell_skip`,
   `supporting_block_shape_skip`, `full_cube_move`). Client modules must
   report `not_client_environment` on the dedicated server.
4. **Client with Ultima:** Fabric 26.2 profile, same Fabric API, Ultima in
   `mods/`. Connect to `localhost` (or the LAN IP). Play normally. Optional:
   enable `retained_terrain` in `config/ultima.properties` on **this**
   client only if you want to confirm render modules still work for the
   host.
5. **Vanilla client without Ultima:** official Minecraft launcher, release
   **26.2**, no Fabric, no mods folder. Add the server and connect.
6. **Pass:** vanilla guest reaches the world with no "incompatible",
   "missing mods", "failed to register channel", or registry-sync
   disconnect. Both players move, break/place, and take collision as usual.
   Server log has no Ultima-related kick. Vanilla guest log has no unknown
   `ultima` payload.
7. **Fail (Ultima bug):** vanilla guest is rejected while the only mods on
   the server are Fabric API + Ultima. Capture both client disconnect
   screens and the server thread log from the join attempt.

### Option B — Open to LAN (one Fabric host + one vanilla guest)

1. Host: Fabric 26.2 client with Ultima. Open a singleplayer world, then
   Open to LAN (creative or survival; cheats optional).
2. Guest: official vanilla 26.2 launcher, no Ultima. Join the LAN game.
3. Same pass/fail as Option A. The host process is both client and
   integrated server; simulation Mixins apply there, render Mixins apply
   only to the host's frame loop.

### What this session did **not** run

No dedicated server, no second client, and no LAN join were started here.
The contract is enforced by source/metadata checks in
`VanillaClientHostingChecks` plus `bash scripts/check.sh`. Live join is
the hardware step above.
