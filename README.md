# Source Control (jcode.ext.gitscm)

Git source control for [JCode](https://github.com/blamspotdev), embedded in the
left-drawer **SCM** panel — VS Code source-control-sidebar style.

## Features

- **Status** — current branch, staged changes, and working-tree changes; refresh on demand.
- **Stage / unstage / discard** — per file or all at once. Discard reverts tracked files
  and deletes untracked ones.
- **Commit** — message box + commit of staged changes. Inline identity form when git has
  no configured `user.name` / `user.email`.
- **Branches** — list local + remote branches, switch, and create new branches from HEAD.
- **Sync** — fetch, pull, push. New branches offer automatic upstream setup on push.
- **Initialize** — offers `git init` when the open project isn't a repository.

## How it works

The extension ships a static web frontend (`www/index.html`) that runs inside JCode's
WebView host and drives git through the **Extension API v1**:

- `workbench.projectInfo` → the guest (`/workspace/...`) path of the open project.
- `exec.run` (as user `jcode`, with `workdir` set to the project) → each `git` command.

It declares the `exec` and `workbench` API capabilities in `extension.yaml`; both are
granted by default and can be revoked per-extension in **Extensions → permissions**.

## Requirements

- `git` in the runtime. Install it from **Toolchains → Git** if it isn't present.

## License

MIT — see [LICENSE](LICENSE).
