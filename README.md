# Creative-Inventory

A client-side Fabric mod for Minecraft 26.2. Adds a togglable side panel to the
survival inventory screen that lets you pick items like Creative mode, without
being OP.

- **Singleplayer**: reaches directly into the local integrated server and edits
  your inventory directly — no commands, no cheats setting.
- **Real server** (e.g. Aternos): falls back to `/give` and `/item replace`
  chat commands, which require OP (permission level 2). If you're not OP'd,
  they'll silently fail, same as typing them yourself would.

## Building

Requires JDK 25.

```bash
./gradlew build
```

The output jar will be in `build/libs/`.

## Requirements

- Minecraft 26.2
- Fabric Loader ≥0.19.3
- Fabric API
