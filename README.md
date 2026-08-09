# Loom Stage Demo

JavaFX desktop app for a live conference talk on Java Project Loom / virtual threads.
Two demos: **Thread Bomb** and **Performance Comparison**, each switchable between
"Take me to the past" (platform threads, orange) and "Take me to the future"
(virtual threads, teal).

Build docs and the stage runbook are filled in as part of the build steps below.

## Quick start

```
make run    # dev loop: compile + launch
make dmg    # full build: bundled-runtime .dmg in dist/
```

(Full build steps and stage runbook: TODO — completed in the packaging step.)
