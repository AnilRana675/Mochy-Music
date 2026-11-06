# HOOKS

List of every edit we made inside upstream files. Keep this updated.
Re-apply these after an upstream merge conflict.

| File | Line purpose | Tag |
|---|---|---|
| `settings.gradle.kts` | `include(":mf-telegram")` — registers our Telegram module in the build | `mf-hook` |
| `app/src/main/kotlin/com/mochyy/mochymusic/ui/screens/settings/integrations/IntegrationsScreen.kt` | `MfIntegrationRows(navController)` — exposes Channel Library in Settings > Integrations | `mf-hook` |
| `app/src/main/kotlin/com/mochyy/mochymusic/ui/screens/NavigationBuilder.kt` | Channel Library route registration | `mf-hook` |
