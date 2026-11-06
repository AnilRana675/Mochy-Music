# AGENT.md

Guidelines for AI agents and humans working on this fork.

## Project

- Personal fork of [realidkroo/Mochy-Music](https://github.com/realidkroo/Mochy-Music) (Android, Kotlin, Compose, Hilt, Gradle multi-module).
- Goal: keep every upstream feature, add our own features on top.
- Upstream updates must merge into this fork without our features breaking.
- License: GPL-3.0. Keep LICENSE and upstream credits. Distributing an APK means the source stays open.
- Personal, non-commercial project.

## Core rule: thin overlay

Never rewrite upstream code. Put our code in separate files and modules.
Touch upstream files only for tiny hooks (1 to 3 lines).

## Where to put a new feature (pick the first that works)

| Order | Option | Use when |
|---|---|---|
| 1 | Official module system (look, web module, JS module, zip) | Feature fits songs, lyrics or look. No app code needed. See `modules/README.md` |
| 2 | Our own built-in module `modules/<name>` | Feature needs in-app code. Depends only on `:modules:api` |
| 3 | New files in our own package inside `:app` | UI screens, settings pages, view models |
| 4 | Small hook in an upstream file | Only to register or call our code |

Add a built-in module like this (same as upstream does):

1. Create `modules/<name>`, depend only on `:modules:api`.
2. Add it to `settings.gradle.kts` and as a dependency of `:app`.
3. Register it in `ModuleRegistry.load()`.
4. Add a logo in `ModuleParts.logoFor` if needed.
5. Write tests with a fake host. Run `./gradlew :modules:<name>:test`.

## Hooks (upstream file edits)

- Max 1 to 3 lines per hook. Prefer one call into our own file.
- Mark every hook with a one-line comment: `// yn-hook: <reason>`
- Keep a list of all hooks in `HOOKS.md` (file, line purpose). Update it in the same commit.
- Never move, rename or reformat upstream code around a hook.

## Do not

- Rename packages or mass refactor.
- Reformat or auto-organize imports in upstream files.
- Move or rename upstream files.
- Add long logic in the middle of an upstream function.
- Edit `values-*/` translation folders.
- Commit secrets. Keys go in `local.properties` only.
- Bump `MODULE_API_VERSION` unless the change really needs it (see `modules/README.md` section 11).

## Naming and isolation

| Thing | Rule |
|---|---|
| Our package | `com.yetinova.mochyfork` style, separate from `com.mochyy.mochymusic` (pick one and keep it) |
| Preference keys | prefix `yn_` |
| Resources | our own files: `res/values/yn_strings.xml`, `yn_colors.xml` |
| Database | own Room DB or own tables. Never edit upstream entities or migrations |
| Feature flags | every feature behind a flag. Off by default until stable |

## Code style (follow upstream)

- Code comments: one line, lowercase.
- User-visible text goes in `res/values/*.xml`, English only.
- Everything in the repo stays in English (code, comments, docs, commits).
- Smoothness: read playback position and per-frame state inside `drawBehind` or `graphicsLayer`, never in composition. Pause loops when hidden.
- Keep changes small and focused. One feature per commit.

## Git workflow

| Branch | Purpose |
|---|---|
| `main` | Exact mirror of upstream. No own commits. Fast-forward only |
| `custom` | Our work. All features live here |

Remotes:

```
origin    https://github.com/AnilRana675/Mochy-Music.git
upstream  https://github.com/realidkroo/Mochy-Music.git
```

Sync upstream (do it per release tag, not on every commit, because upstream is beta):

```
git fetch upstream --tags
git checkout main
git merge --ff-only upstream/main     # or a release tag
git push origin main
git checkout custom
git rebase main                       # or: git merge main
git config rerere.enabled true        # one time, remembers conflict fixes
./build.sh --debug                    # must pass before push
./gradlew test                        # must pass before push
```

After a conflict:

1. Keep upstream's version of the file.
2. Re-apply our hook (see `HOOKS.md`).
3. Never resolve by deleting upstream code.

## Before every commit

- Debug build passes: `./build.sh --debug`
- Tests pass for touched modules.
- Hooks list updated if an upstream file was touched.
- Feature flag present and defaults to off.

## Build quick reference

| Command | What it does |
|---|---|
| `./build.sh --debug -i` | Debug build, install, open |
| `./build.sh --debug` | APK only, lands in `apk-generated/` |
| `./build.sh -k` | Stop stuck Gradle builds |

If build fails with weird Hilt or unresolved errors, delete `build/` folders and `.gradle/`, build again.

## For agents

- Read this file and `modules/README.md` before any change.
- Ask which option from the table above fits before writing code.
- If a task needs a big upstream edit, stop and propose a hook or a module instead.
- Keep replies short. Show the diff plan first for anything touching upstream files.
