# Source Control (jcode.ext.gitscm)

Git source control for [JCode](https://github.com/blamspotdev), embedded in the
left-drawer **SCM** panel — VS Code source-control-sidebar style.

## Features

- **Status** — current branch, staged changes, and working-tree changes; refresh on demand.
- **Stage / unstage / discard** — per file or all at once. Discard reverts tracked files
  and deletes untracked ones.
- **Commit** — message box + commit of staged changes. Inline identity form when git has
  no configured `user.name` / `user.email`.
- **Branches** — list local + remote branches, switch, rename, delete, and create from HEAD.
- **Diff** — a file's staged or working-tree changes, tappable through to the line.
- **Stash** — save, apply, pop, drop, and read a stash's patch.
- **Merge** — a three-way editor for conflicts: both sides, an editable result, staged on save.
- **Clone** — clone a repository, or browse and clone your own from GitHub.
- **Sync** — fetch, pull, push. New branches offer automatic upstream setup on push.
- **Initialize** — offers `git init` when the open project isn't a repository.

## How it works

The extension ships one file, `lib/scm.dex`: its own Kotlin, drawn by **JCode's own Compose
runtime, inside JCode's process**. Compose, material3 and JCode's design system are
`compileOnly` — they resolve from JCode at run time, which is what makes the dex small and
what makes the panel look like the rest of the IDE rather than like a page inside it.

It is one extension with several surfaces: the drawer panel, plus the pages the panel opens
— GitHub sign-in, branches & history, clone, remote repositories, a diff, a stash, and the
three-way merge editor. Each page opens on its own (restored with a session, or reached from
another page), so each asks git its own questions rather than inheriting the panel's answers.

Everything git happens through the **Extension API v1**:

- `workbench.projectInfo` → the guest (`/workspace/...`) path of the open project.
- `exec.run` (as user `jcode`, with `workdir` set to the project) → each `git` command.

It declares the `exec` and `workbench` API capabilities in `extension.yaml`; both are
granted by default and can be revoked per-extension in **Extensions → permissions**.

## Building

`npm run build` compiles `native/` and copies the dex to `lib/scm.dex`. `jext pack` runs it.

## Requirements

- `git` in the runtime. Install it from **Toolchains → Git** if it isn't present.

## License

MIT — see [LICENSE](LICENSE).
