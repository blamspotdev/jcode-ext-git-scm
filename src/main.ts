// Source Control — JCode extension UI (TypeScript, bundled to www/main.js by esbuild).
// Drives git in the Linux runtime through the JCode Extension API v1.

interface ApiResult { ok: boolean; data?: any; error?: string }
interface ExecResult { stdout: string; stderr: string; exitCode: number; error?: string }
interface FileEntry { code: string; path: string; display: string; untracked: boolean }
interface RepoInfo { root: string; name: string }
interface Action { icon: string; title: string; fn: () => void }
type ViewMode = 'list' | 'tree';

// ---- Extension API v1 bridge ----
const pending: Record<string, (r: ApiResult) => void> = {};
let seq = 0;
function api(type: string, payload?: unknown): Promise<ApiResult> {
  return new Promise((resolve) => {
    const id = 'q' + (seq++);
    pending[id] = resolve;
    try {
      (window as any).JCodeNative.request(id, JSON.stringify({ type, payload: payload ?? {} }));
    } catch (e) {
      delete pending[id];
      resolve({ ok: false, error: 'bridge unavailable: ' + e });
    }
  });
}
(window as any).JCode = {
  request: api,
  _onResult(id: string, jsonString: string) {
    const cb = pending[id];
    if (!cb) return;
    delete pending[id];
    let r: ApiResult;
    try { r = JSON.parse(jsonString); } catch { r = { ok: false, error: jsonString }; }
    cb(r);
  },
  _onEvent(name: string, json: string) {
    // Only the sidebar surface reloads config; the #github/#manage/#diff editor pages replaced
    // document.body and no longer have the sidebar DOM (#viewToggle, lists), so loadConfig would throw.
    if (name === 'config' && VIEW !== 'github' && VIEW !== 'manage' && VIEW !== 'clone' && VIEW !== 'remoteRepo' && VIEW.indexOf('diff:') !== 0 && VIEW.indexOf('merge:') !== 0) {
      void loadConfig().then(renderLists);
    }
    // The sidebar surface (also booted headless by the app as the decorations host) reacts to disk
    // changes and explorer context-menu taps; other surfaces are pages without repo state.
    if (VIEW !== '') return;
    if (name === 'filesChanged') scheduleDecorationRefresh();
    if (name === 'explorerAction') {
      let d: any = {};
      try { d = JSON.parse(json); } catch { /* malformed event */ }
      if (d && d.actionId === 'addToGitignore' && typeof d.path === 'string') {
        // Queue behind boot so a tap in the first seconds isn't rejected with "not a repository"
        // just because repo detection hasn't finished yet.
        void bootP.then(() => addToGitignore(d.path, !!d.isDirectory));
      }
    }
  },
};

// ---- helpers ----
function $<T extends HTMLElement = HTMLElement>(id: string): T { return document.getElementById(id) as T; }
const sh = (v: string) => "'" + String(v).replace(/'/g, "'\\''") + "'";
const out = (r: ExecResult) => ((r.stdout || '') + (r.stderr || '')).replace(/\s+$/, '');
const baseName = (p: string) => { const i = p.replace(/\/$/, '').lastIndexOf('/'); return i >= 0 ? p.slice(i + 1) : p; };

let projectPath: string | null = null;
let repos: RepoInfo[] = [];
let repo: string | null = null;
// Latest boot()'s completion — explorer actions await it so early taps see detected repos.
let bootP: Promise<void> = Promise.resolve();
let busy = false;
let viewMode: ViewMode = 'list';
let lastStaged: FileEntry[] = [];
let lastUnstaged: FileEntry[] = [];
let lastConflicts: FileEntry[] = [];
const collapsedFolders = new Set<string>();

async function exec(cmd: string, opts: { workdir?: string | null; timeoutMs?: number; env?: Record<string, string> } = {}): Promise<ExecResult> {
  const p: any = { command: cmd, timeoutMs: opts.timeoutMs || 60000 };
  const wd = 'workdir' in opts ? opts.workdir : repo;
  if (wd) p.workdir = wd;
  if (opts.env) p.env = opts.env;
  const r = await api('exec.run', p);
  if (!r || !r.ok) return { stdout: '', stderr: (r && r.error) || 'request failed', exitCode: -1 };
  return r.data as ExecResult;
}
// Every git runs with safe.directory='*' (root may not own the files) and quotePath off (verbatim paths).
// core.createObject=rename: git finalizes objects/packs with link()+unlink(), which proot's
// --link2symlink emulates as a symlink onto an `.l2s.*` backing file — the clone pipeline's cleanup
// then destroyed the object store (fatal: bad object HEAD on every pack-transferred clone). rename()
// produces plain files, so no git write (clone, commit, gc) ever leaves l2s artifacts.
const GITP = "git -c safe.directory='*' -c core.quotePath=false -c core.createObject=rename ";
const rawGit = (workdir: string, args: string, t?: number) => exec(GITP + args, { workdir, timeoutMs: t });
const git = (args: string, t?: number) => exec(GITP + args, { workdir: repo, timeoutMs: t });

// Git command output / status messages surface in a dismissible dialog rather than an inline panel
// log, so multi-line output (init, push/pull, errors) doesn't shove the SCM content around.
let logDlg: HTMLElement | null = null;
function logHide() { if (logDlg) { logDlg.remove(); logDlg = null; } }
function logShow(t: string) {
  const text = (t || '').replace(/\s+$/, '');
  if (!text) return;
  logHide();
  const back = document.createElement('div'); back.className = 'modal-scrim log-scrim';
  const dlg = document.createElement('div'); dlg.className = 'modal';
  dlg.innerHTML = '<div class="modal-title">Git</div><pre class="modal-log"></pre>' +
    '<div class="modal-actions"><button class="btn primary" id="__logOk">OK</button></div>';
  (dlg.querySelector('.modal-log') as HTMLElement).textContent = text;
  back.appendChild(dlg); document.body.appendChild(back);
  logDlg = back;
  (document.getElementById('__logOk') as HTMLButtonElement).onclick = logHide;
  back.onclick = (e) => { if (e.target === back) logHide(); };
}
function setBranch(t: string) { $('branchName').textContent = t; }
// The branch switcher, fetch/pull/push and the tree/list toggle only make sense once a repository
// exists. They stay hidden in the no-project / no-git / no-repo states so the toolbar can't drive git
// against a non-repo folder — e.g. opening the branch menu to "create" a branch on a plain folder.
function setRepoActions(enabled: boolean) {
  ['branchBtn', 'fetch', 'pull', 'push', 'viewToggle'].forEach((id) => {
    document.getElementById(id)?.classList.toggle('hide', !enabled);
  });
}
function notice(html: string) { const n = $('notice'); n.innerHTML = html; n.classList.remove('hide'); $('main').classList.add('hide'); }
function clearNotice() { $('notice').classList.add('hide'); }
function showMain() { $('main').classList.remove('hide'); }
// Compact centered empty state for the no-project / no-git / no-repo cases. The whole SCM panel is
// owned by this extension, so it renders its own placeholder here; the toolbar chrome (branch, sync,
// GitHub, refresh) is hidden behind it since none of it applies without a repo.
function showEmpty(title: string, msg: string, actionsHtml = '') {
  $('main').classList.add('hide');
  $('notice').classList.add('hide');
  $('repoBar').classList.add('hide');
  document.querySelector('.hd')?.classList.add('hide');
  const e = $('empty');
  e.innerHTML = '<div class="eic">' + IC_SCM + '</div><div class="et">' + title + '</div>' +
    '<div class="em">' + msg + '</div>' + actionsHtml;
  e.classList.remove('hide');
}
function hideEmpty() { $('empty').classList.add('hide'); document.querySelector('.hd')?.classList.remove('hide'); }
function setBusy(v: boolean) { busy = v; document.querySelectorAll<HTMLButtonElement>('button').forEach((b) => { if (!b.dataset.keep) b.disabled = v; }); if (!v) refreshCommitState(); }
// A commit needs a message: keep the primary Commit button disabled until the message box is non-empty.
// Re-applied after every setBusy(false), which otherwise re-enables all non-keep buttons regardless.
function refreshCommitState() {
  const c = document.getElementById('commit') as HTMLButtonElement | null;
  const m = document.getElementById('msg') as HTMLTextAreaElement | null;
  if (c && m) c.disabled = !m.value.trim();
}
function closePops() { ['branchMenu', 'repoMenu', 'commitMenu', 'scrim'].forEach((id) => $(id).classList.add('hide')); }
// Float a pop-over anchored just below its trigger button, over a dismiss scrim.
function openPop(pop: HTMLElement, anchor: HTMLElement) {
  const r = anchor.getBoundingClientRect();
  // Un-hide first (.hide is display:none) so we can measure width and keep the menu on-screen when the
  // trigger sits near the right edge (e.g. the Commit split-button caret). Positioning is set in the
  // same synchronous block, so the browser only paints the final placement.
  pop.classList.remove('hide');
  const w = pop.offsetWidth || 210;
  // Document-relative coords + a px max-height from innerHeight (CSS vh is broken in this WebView).
  pop.style.top = (r.bottom + window.scrollY + 4) + 'px';
  pop.style.left = (Math.max(8, Math.min(r.left, window.innerWidth - w - 8)) + window.scrollX) + 'px';
  pop.style.maxHeight = Math.max(140, window.innerHeight - r.bottom - 16) + 'px';
  $('scrim').classList.remove('hide');
}

// ---- icons ----
const IC_SCM = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M11.75 2.5a.75.75 0 100 1.5.75.75 0 000-1.5zm-2.25.75a2.25 2.25 0 113 2.122V6A2.5 2.5 0 0110 8.5H6a1 1 0 00-1 1v1.128a2.251 2.251 0 11-1.5 0V5.372a2.25 2.25 0 111.5 0v1.836A2.492 2.492 0 016 7h4a1 1 0 001-1v-.628A2.25 2.25 0 019.5 3.25zM4.25 12a.75.75 0 100 1.5.75.75 0 000-1.5zM3.5 3.25a.75.75 0 111.5 0 .75.75 0 01-1.5 0z"/></svg>';
const IC_STAGE = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M7.25 3.25a.75.75 0 011.5 0V7h3.75a.75.75 0 010 1.5H8.75v3.75a.75.75 0 01-1.5 0V8.5H3.5a.75.75 0 010-1.5h3.75V3.25z"/></svg>';
const IC_UNSTAGE = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M3.5 7.25a.75.75 0 000 1.5h9a.75.75 0 000-1.5h-9z"/></svg>';
const IC_DISCARD = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M4.28 3.22a.75.75 0 00-1.06 1.06L6.94 8l-3.72 3.72a.75.75 0 101.06 1.06L8 9.06l3.72 3.72a.75.75 0 101.06-1.06L9.06 8l3.72-3.72a.75.75 0 00-1.06-1.06L8 6.94 4.28 3.22z"/></svg>';
const IC_FOLDER = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M1.75 3.5A.75.75 0 012.5 2.75h3.19c.2 0 .39.08.53.22l1.06 1.06h6.47a.75.75 0 01.75.75v7.72a.75.75 0 01-.75.75H2.5a.75.75 0 01-.75-.75V3.5z"/></svg>';
const IC_LIST = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M2 3.5h12V5H2zM2 7.25h12v1.5H2zM2 11h12v1.5H2z"/></svg>';
const IC_TREE = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M2 2.75h1.5v10.5H2zM3.5 4h3v1.5h-3zM6 7.25h6v1.5H6zM6 10.5h6V12H6z"/></svg>';
const IC_CHEV = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M6 4l4 4-4 4z"/></svg>';
const IC_OURS = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M10.53 3.47a.75.75 0 010 1.06L7.06 8l3.47 3.47a.75.75 0 11-1.06 1.06l-4-4a.75.75 0 010-1.06l4-4a.75.75 0 011.06 0z"/></svg>';
const IC_THEIRS = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M5.47 3.47a.75.75 0 011.06 0l4 4a.75.75 0 010 1.06l-4 4a.75.75 0 01-1.06-1.06L8.94 8 5.47 4.53a.75.75 0 010-1.06z"/></svg>';
const IC_RESOLVED = '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M13.78 4.22a.75.75 0 010 1.06l-6.5 6.5a.75.75 0 01-1.06 0l-3-3a.75.75 0 111.06-1.06L6.75 10.19l5.97-5.97a.75.75 0 011.06 0z"/></svg>';

// ---- boot / repo detection ----
async function boot() {
  closePops();
  setBranch('…');
  setRepoActions(false);
  const info = await api('workbench.projectInfo');
  projectPath = info.ok && info.data && info.data.path ? info.data.path : null;
  if (!projectPath) { setBranch('no project'); showEmpty('Source Control', 'Open a folder to start managing changes with Git.'); return; }

  const g = await exec('command -v git >/dev/null 2>&1 && echo OK || echo NO', { workdir: projectPath });
  if (out(g).indexOf('OK') < 0) {
    setBranch('no git');
    showEmpty('Git isn’t installed', 'Install Git from <b>Toolchains → Git</b>, then refresh.',
      '<button id="egRefresh" class="btn ghost sm">Refresh</button>');
    const rb = $<HTMLButtonElement>('egRefresh'); rb.dataset.keep = '1'; rb.onclick = () => void boot();
    return;
  }

  repos = await detectRepos();
  if (!repos.length) {
    repo = projectPath;
    setBranch('no repo');
    showEmpty('Not a repository', 'This folder isn’t a Git repository yet.',
      '<button id="doInit" class="btn primary">Initialize repository</button>');
    const b = $<HTMLButtonElement>('doInit'); b.dataset.keep = '1'; b.onclick = doInit;
    return;
  }
  const remembered = localStorage.getItem('scm.activeRepo');
  repo = repos.find((r) => r.root === remembered)?.root ?? repos[0].root;
  await loadConfig();
  hideEmpty();
  renderRepoBar();
  clearNotice();
  setRepoActions(true);
  showMain();
  refreshCommitState();
  await refreshAll();
  // An explorer context-menu tap stashed while this surface was still booting — handle it now.
  const pend = await api('workbench.pendingContextAction');
  const act = pend.ok && pend.data ? pend.data.action : null;
  if (act && act.actionId === 'addToGitignore' && typeof act.path === 'string') {
    void addToGitignore(act.path, !!act.isDirectory);
  }
}

// Detect every git repo in the workspace: try workbench.workspaceFolders (multi-repo workspaces),
// falling back to the single open project. rev-parse --show-toplevel walks UP, so a folder inside a
// repo resolves to the real repo root.
async function detectRepos(): Promise<RepoInfo[]> {
  let folders: { name: string; path: string }[] = [];
  const wf = await api('workbench.workspaceFolders');
  if (wf.ok && wf.data && Array.isArray(wf.data.folders) && wf.data.folders.length) {
    folders = wf.data.folders.filter((f: any) => f && f.path);
  }
  if (!folders.length && projectPath) folders = [{ name: baseName(projectPath), path: projectPath }];

  const found: RepoInfo[] = [];
  const seen = new Set<string>();
  for (const f of folders) {
    const top = await rawGit(f.path, 'rev-parse --show-toplevel 2>/dev/null');
    const root = out(top).split('\n').filter(Boolean).pop() || '';
    if (top.exitCode === 0 && root && !seen.has(root)) {
      seen.add(root);
      found.push({ root, name: baseName(root) });
      void injectIgnored(root);
      void pushExplorerDecorations(root);
    }
  }
  return found;
}

// Read a repo's root .gitignore and inject its patterns as the Explorer's "by-injected" root hide
// list (the app merges them per the user's hide mode). Blank lines, comments and negations skipped.
// Runs on every boot() — i.e. on project switch while the SCM panel is visible.
async function injectIgnored(root: string) {
  const r = await exec('cat .gitignore 2>/dev/null', { workdir: root });
  const patterns = (r.stdout || '')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '' && l[0] !== '#' && l[0] !== '!');
  void api('workbench.setHiddenInjected', { path: root, patterns });
}

// ---- explorer decorations (per-file VCS badges + submodule dirs in the app's file tree) ----

// One status letter per path for the explorer badge: staged letter first ("AM" reads as a new file),
// worktree/untracked letters fill the gaps, conflicts override everything as 'U'. Conflicts sort
// first so an oversized push truncated by the app never drops them.
function decorationsFrom(s: ReturnType<typeof parseStatus>): { path: string; status: string }[] {
  const m = new Map<string, string>();
  for (const f of s.staged) if (!m.has(f.path)) m.set(f.path, f.code);
  for (const f of s.unstaged) if (!m.has(f.path)) m.set(f.path, f.untracked ? '?' : f.code);
  for (const f of s.conflicts) m.set(f.path, 'U');
  const all = Array.from(m.entries(), ([path, status]) => ({ path, status }));
  return all.filter((d) => d.status === 'U').concat(all.filter((d) => d.status !== 'U'));
}

// Push one repo's working-tree status + submodule roots into the app's Explorer. Reading .gitmodules
// via `git config -f` lists submodules even before `git submodule init`; missing file → empty list.
// [parsed] skips the status run when the caller (refreshStatus) already has one.
async function pushExplorerDecorations(root: string, parsed?: ReturnType<typeof parseStatus>) {
  let s = parsed;
  if (!s) {
    const st = await rawGit(root, 'status --porcelain=v1 -uall');
    if (st.exitCode !== 0) return;
    s = parseStatus(st.stdout || '');
  }
  const decorations = decorationsFrom(s).slice(0, 4000);
  const sm = await rawGit(root, "config -f .gitmodules --get-regexp '^submodule\\..*\\.path$' 2>/dev/null");
  // Lines are "submodule.<name>.path <value>" — split at the LAST '.path ' so submodule names
  // containing spaces (or dots) don't truncate the value.
  const submodules = (sm.exitCode === 0 ? sm.stdout || '' : '')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => { const i = l.lastIndexOf('.path '); return i < 0 ? '' : l.slice(i + 6).trim(); })
    .filter(Boolean);
  void api('workbench.setExplorerDecorations', { path: root, decorations, submodules });
}

// Disk-change hints (saves, explorer file ops) arrive in bursts — recompute once, shortly after the
// last one, for every known repo, and keep the visible sidebar lists in sync for the active one.
let decoTimer: number | undefined;
function scheduleDecorationRefresh() {
  clearTimeout(decoTimer);
  decoTimer = window.setTimeout(() => {
    void (async () => {
      // The FIRST repo may have appeared since boot (git init / clone in the terminal) — re-boot,
      // which re-detects repos and re-renders the sidebar out of its "no repo" notice.
      if (!repos.length) {
        if (!busy) bootP = boot();
        return;
      }
      // Re-detect rather than iterate the cached list: another workspace folder may have become a
      // repo since boot (detectRepos pushes decorations + ignore patterns for every repo it finds),
      // and a deleted repo's stale badges must be cleared.
      const prevRoots = repos.map((r) => r.root);
      repos = await detectRepos();
      for (const gone of prevRoots.filter((p) => !repos.some((r) => r.root === p))) {
        void api('workbench.setExplorerDecorations', { path: gone, decorations: [], submodules: [] });
      }
      if (repos.length && !repos.some((r) => r.root === repo)) repo = repos[0].root;
      if (repo && !busy) void refreshStatus();
    })();
  }, 400);
}

// "Add to .gitignore" from the explorer's file/folder context menu: append a root-anchored pattern
// to the containing repo's .gitignore (unless an equivalent line already exists), then re-sync the
// injected hide list, the decorations, and the sidebar.
async function addToGitignore(guestPath: string, isDirectory: boolean) {
  // Longest matching root wins, so a file inside a nested repo/submodule lands in ITS .gitignore.
  const target = repos
    .filter((r) => guestPath === r.root || guestPath.indexOf(r.root + '/') === 0)
    .sort((a, b) => b.root.length - a.root.length)[0]?.root;
  if (!target) { void api('workbench.notify', { message: 'Not inside a git repository.' }); return; }
  if (guestPath === target) { void api('workbench.notify', { message: 'Cannot ignore the repository root.' }); return; }
  const rel = guestPath.slice(target.length + 1);
  if (/[\n\r]/.test(rel)) { void api('workbench.notify', { message: 'Unsupported file name for .gitignore.' }); return; }
  // Escape gitignore glob metacharacters so "foo[1].txt" ignores that file, not "foo1.txt".
  const relEsc = rel.replace(/([[\]*?\\])/g, '\\$1');
  const pattern = '/' + relEsc + (isDirectory ? '/' : '');
  const cur = await exec('cat .gitignore 2>/dev/null', { workdir: target });
  const lines = (cur.stdout || '').split('\n').map((l) => l.trim());
  const variants = isDirectory ? [pattern, '/' + relEsc, relEsc, relEsc + '/'] : [pattern, '/' + relEsc, relEsc];
  if (variants.some((v) => lines.indexOf(v) >= 0)) {
    void api('workbench.notify', { message: "'" + rel + "' is already in .gitignore." });
    return;
  }
  const r = await exec(
    '{ [ -s .gitignore ] && [ "$(tail -c 1 .gitignore)" != "" ] && echo; } >> .gitignore 2>/dev/null; ' +
      "printf '%s\\n' " + sh(pattern) + ' >> .gitignore',
    { workdir: target },
  );
  if (r.exitCode !== 0) { logShow(out(r) || 'Failed to update .gitignore'); return; }
  void api('workbench.notify', { message: "Added '" + rel + "' to .gitignore." });
  void injectIgnored(target);
  void pushExplorerDecorations(target);
  if (repo === target && !busy) void refreshStatus();
}

async function doInit() { setBusy(true); const r = await git('init', 30000); if (out(r)) logShow(out(r)); setBusy(false); bootP = boot(); }
async function refreshAll() { await Promise.all([refreshStatus(), refreshBranches(), refreshGh()]); }

// ---- config (Phase: generic extension settings; graceful fallback to localStorage) ----
async function loadConfig() {
  // App config is authoritative (so the Settings screen drives this live via the 'config' event);
  // localStorage is only an offline fallback when the config API is unavailable.
  const c = await api('config.all');
  const data = (c.ok && c.data ? c.data : {}) as Record<string, unknown>;
  let dv = data['scm.defaultView'];
  if (dv !== 'tree' && dv !== 'list') {
    const local = localStorage.getItem('scm.view');
    dv = local === 'tree' || local === 'list' ? local : 'list';
  }
  viewMode = dv as ViewMode;
  updateViewToggle();
  // The AI "generate commit message" button is opt-in — hidden until scm.commitMsg.enabled is turned on.
  const gen = document.getElementById('genMsg');
  if (gen) gen.classList.toggle('hide', data['scm.commitMsg.enabled'] !== true && data['scm.commitMsg.enabled'] !== 'true');
}
function updateViewToggle() {
  const b = $('viewToggle');
  b.innerHTML = viewMode === 'tree' ? IC_TREE : IC_LIST;
  b.title = viewMode === 'tree' ? 'Viewing as tree — switch to list' : 'Viewing as list — switch to tree';
}
function toggleView() {
  viewMode = viewMode === 'tree' ? 'list' : 'tree';
  localStorage.setItem('scm.view', viewMode);
  void api('config.set', { key: 'scm.defaultView', value: viewMode });
  updateViewToggle();
  renderLists();
}

// ---- multi-repo bar ----
function renderRepoBar() {
  const bar = $('repoBar');
  if (repos.length <= 1) { bar.classList.add('hide'); return; }
  const active = repos.find((r) => r.root === repo) ?? repos[0];
  bar.classList.remove('hide');
  bar.innerHTML = '<span class="rl">Repo</span>' +
    '<button id="repoPick"><span class="rn">' + escapeHtml(active.name) + '</span>' + IC_CHEV + '</button>';
  $('repoPick').onclick = toggleRepoMenu;
}
function toggleRepoMenu() {
  const m = $('repoMenu');
  if (!m.classList.contains('hide')) { closePops(); return; }
  closePops();
  let html = '<div class="row hdrow"><span class="k">Repositories</span></div>';
  repos.forEach((r) => {
    const cur = r.root === repo;
    html += '<div class="row tap" data-r="' + escapeAttr(r.root) + '">' +
      '<span class="k' + (cur ? ' cur' : '') + '">' + escapeHtml(r.name) + '</span>' +
      (cur ? '<span class="tag">active</span>' : '') + '</div>';
  });
  m.innerHTML = html;
  openPop(m, $('repoPick'));
  m.querySelectorAll<HTMLElement>('.row.tap').forEach((row) => {
    row.onclick = () => {
      const root = row.getAttribute('data-r')!;
      if (root === repo) return;
      repo = root;
      localStorage.setItem('scm.activeRepo', root);
      closePops();
      renderRepoBar();
      void refreshAll();
    };
  });
}

// ---- status ----
// Even with core.quotePath=false, git C-quotes paths containing double quotes, backslashes, or
// control characters. JSON.parse covers the common escapes; octal falls back to bare unquoting.
function unquoteGitPath(p: string): string {
  if (p.length < 2 || p[0] !== '"' || p[p.length - 1] !== '"') return p;
  try { return JSON.parse(p) as string; } catch { return p.slice(1, -1); }
}

function parseStatus(text: string) {
  const s = { branch: '', ahead: 0, behind: 0, staged: [] as FileEntry[], unstaged: [] as FileEntry[], conflicts: [] as FileEntry[] };
  for (const line of text.split('\n')) {
    if (!line) continue;
    if (line.slice(0, 3) === '## ') {
      let b = line.slice(3);
      if (b.indexOf('No commits yet on ') === 0) { s.branch = b.slice(18).trim().split(/\s/)[0]; continue; }
      const d = b.indexOf('...');
      if (d >= 0) {
        s.branch = b.slice(0, d);
        const rest = b.slice(d + 3);
        const a = rest.match(/ahead (\d+)/); if (a) s.ahead = +a[1];
        const bh = rest.match(/behind (\d+)/); if (bh) s.behind = +bh[1];
      } else s.branch = b.trim().split(/\s/)[0];
      continue;
    }
    const x = line[0], y = line[1];
    let path = line.slice(3), display = path;
    const arrow = path.indexOf(' -> ');
    if (arrow >= 0) {
      const p = path.split(' -> ').map(unquoteGitPath);
      path = p[1];
      display = p[0] + ' → ' + p[1];
    } else {
      path = unquoteGitPath(path);
      display = path;
    }
    if (x === '?' && y === '?') { s.unstaged.push({ code: '?', path, display, untracked: true }); continue; }
    // Unmerged (merge/rebase conflict): DD AU UD UA DU AA UU. Surface these separately so they can be
    // resolved, instead of double-listing the file under both Staged and Changes.
    if (x === 'U' || y === 'U' || (x === 'D' && y === 'D') || (x === 'A' && y === 'A')) {
      s.conflicts.push({ code: '!', path, display, untracked: false }); continue;
    }
    if (x !== ' ' && x !== '?') s.staged.push({ code: x, path, display, untracked: false });
    if (y !== ' ' && y !== '?') s.unstaged.push({ code: y, path, display, untracked: false });
  }
  return s;
}
const stClass = (c: string) => 'MADRU'.indexOf(c) >= 0 ? c : 'U';

async function refreshStatus() {
  // -uall lists individual untracked files (respecting .gitignore) instead of collapsing whole
  // untracked directories, so the tree view and per-file staging work file-by-file.
  const r = await git('status --porcelain=v1 -b -uall');
  // A failing status (e.g. a corrupt / unborn HEAD → "fatal: bad object HEAD") exits non-zero and
  // writes to stderr. NEVER feed that into the porcelain parser — the error text would be read as
  // fake file rows (each stderr char pair becomes a status code + path). Surface it and let the user
  // retry instead. Also parse STDOUT only, so a stray git warning never lands in the file lists.
  if (r.exitCode !== 0) {
    setBranch('HEAD');
    setRepoActions(false);
    lastStaged = [];
    lastUnstaged = [];
    notice('Couldn’t read this repository.' +
      '<div class="modal-log" style="margin-top:8px;max-height:120px">' +
      escapeHtml(out(r).trim() || 'git status failed.') + '</div>' +
      '<div style="margin-top:10px"><button id="scmRetry" class="btn primary">Refresh</button></div>');
    const b = $<HTMLButtonElement>('scmRetry'); b.dataset.keep = '1'; b.onclick = () => void boot();
    return;
  }
  const s = parseStatus(r.stdout);
  setBranch(s.branch || 'HEAD');
  const ab = $('ab');
  if (s.ahead || s.behind) { ab.classList.remove('hide'); ab.innerHTML = '↓<b>' + s.behind + '</b> ↑<b>' + s.ahead + '</b>'; }
  else ab.classList.add('hide');
  lastStaged = s.staged;
  lastUnstaged = s.unstaged;
  lastConflicts = s.conflicts;
  renderLists();
  // Keep the explorer's badges in step with every sidebar-driven git mutation (stage/commit/etc.).
  if (repo) void pushExplorerDecorations(repo, s);
}

function renderLists() {
  $('conflictSec').classList.toggle('hide', lastConflicts.length === 0);
  renderSection('conflictList', 'conflictCount', [], lastConflicts, false, (f) => [
    { icon: IC_OURS, title: 'Accept current (ours)', fn: () => resolveConflict(f.path, 'ours') },
    { icon: IC_THEIRS, title: 'Accept incoming (theirs)', fn: () => resolveConflict(f.path, 'theirs') },
    { icon: IC_RESOLVED, title: 'Mark resolved', fn: () => markResolved(f.path) },
  ]);
  renderSection('stagedList', 'stagedCount', 'unstageAll', lastStaged, true, (f) => [{ icon: IC_UNSTAGE, title: 'Unstage', fn: () => unstage(f.path) }]);
  renderSection('changeList', 'changeCount', ['stageAll', 'discardAll'], lastUnstaged, false, (f) => [
    { icon: IC_DISCARD, title: 'Discard', fn: () => discard(f) },
    { icon: IC_STAGE, title: 'Stage', fn: () => stage(f.path) },
  ]);
}

function renderSection(listId: string, countId: string, bulkIds: string | string[], files: FileEntry[], staged: boolean, actionsFor: (f: FileEntry) => Action[]) {
  const el = $(listId);
  $(countId).textContent = String(files.length);
  (Array.isArray(bulkIds) ? bulkIds : [bulkIds]).forEach((id) => $(id).classList.toggle('hide', files.length === 0));
  el.innerHTML = '';
  if (!files.length) { el.innerHTML = '<div class="empty">No ' + (listId === 'stagedList' ? 'staged changes' : 'changes') + '.</div>'; return; }
  if (viewMode === 'tree') renderTree(el, files, staged, actionsFor);
  else files.forEach((f) => el.appendChild(fileRow(f, actionsFor(f), -1, staged)));
}

function fileRow(f: FileEntry, actions: Action[], depth: number, staged: boolean): HTMLElement {
  const row = document.createElement('div');
  row.className = 'file';
  if (depth >= 0) row.style.paddingLeft = 8 + depth * 14 + 'px';
  const st = document.createElement('span');
  st.className = 'st ' + stClass(f.code);
  st.textContent = f.untracked ? 'U' : f.code;
  const nm = document.createElement('div');
  nm.className = 'nm';
  const base = document.createElement('span');
  base.className = 'base';
  base.textContent = depth >= 0 ? baseName(f.path) : splitPath(f.display).base;
  nm.appendChild(base);
  if (depth < 0) {
    const parts = splitPath(f.display);
    if (parts.dir) { const dir = document.createElement('span'); dir.className = 'dir'; dir.textContent = parts.dir; nm.appendChild(dir); }
  }
  nm.title = f.display;
  nm.classList.add('tapdiff');
  nm.onclick = () => openDiff(f, staged);
  const fa = document.createElement('div');
  fa.className = 'fa';
  for (const a of actions) {
    const btn = document.createElement('button');
    btn.className = 'ic'; btn.title = a.title; btn.innerHTML = a.icon; btn.onclick = a.fn;
    fa.appendChild(btn);
  }
  row.appendChild(st); row.appendChild(nm); row.appendChild(fa);
  return row;
}
function splitPath(p: string): { base: string; dir: string } {
  if (p.indexOf(' → ') >= 0) return { base: p, dir: '' };
  const i = p.lastIndexOf('/');
  return i >= 0 ? { base: p.slice(i + 1), dir: p.slice(0, i + 1) } : { base: p, dir: '' };
}

// ---- tree view ----
interface TreeFolder { name: string; path: string; folders: Map<string, TreeFolder>; files: FileEntry[] }
function buildTree(files: FileEntry[]): TreeFolder {
  const root: TreeFolder = { name: '', path: '', folders: new Map(), files: [] };
  for (const f of files) {
    const parts = f.path.split('/');
    parts.pop();
    let node = root, acc = '';
    for (const seg of parts) {
      acc = acc ? acc + '/' + seg : seg;
      let child = node.folders.get(seg);
      if (!child) { child = { name: seg, path: acc, folders: new Map(), files: [] }; node.folders.set(seg, child); }
      node = child;
    }
    node.files.push(f);
  }
  return root;
}
function renderTree(container: HTMLElement, files: FileEntry[], staged: boolean, actionsFor: (f: FileEntry) => Action[]) {
  const root = buildTree(files);
  const walk = (folder: TreeFolder, depth: number) => {
    [...folder.folders.values()].sort((a, b) => a.name.localeCompare(b.name)).forEach((sub) => {
      // Compress single-child folder chains (VS Code style): a/b/c shows as one "a/b/c" row.
      let node = sub, label = sub.name, key = sub.path;
      while (node.files.length === 0 && node.folders.size === 1) {
        const only = [...node.folders.values()][0];
        label += '/' + only.name; node = only; key = only.path;
      }
      const closed = collapsedFolders.has(key);
      container.appendChild(folderRow(label, key, depth, closed));
      if (!closed) walk(node, depth + 1);
    });
    folder.files.slice().sort((a, b) => baseName(a.path).localeCompare(baseName(b.path)))
      .forEach((f) => container.appendChild(fileRow(f, actionsFor(f), depth, staged)));
  };
  walk(root, 0);
}
function folderRow(label: string, key: string, depth: number, closed: boolean): HTMLElement {
  const row = document.createElement('div');
  row.className = 'folder' + (closed ? ' closed' : '');
  row.style.paddingLeft = 8 + depth * 14 + 'px';
  const chev = document.createElement('span'); chev.className = 'fchev'; chev.textContent = '▾';
  const ic = document.createElement('span'); ic.innerHTML = IC_FOLDER;
  const nm = document.createElement('span'); nm.className = 'fname'; nm.textContent = label;
  row.appendChild(chev); row.appendChild(ic); row.appendChild(nm);
  row.onclick = () => { if (closed) collapsedFolders.delete(key); else collapsedFolders.add(key); renderLists(); };
  return row;
}

// ---- actions ----
async function run(cmd: () => Promise<ExecResult>, done?: (r: ExecResult, text: string) => void | Promise<void>) {
  setBusy(true); logHide();
  const r = await cmd();
  const text = out(r);
  if (r.exitCode !== 0 && text) logShow(text);
  setBusy(false);
  if (done) await done(r, text);
}
const stage = (p: string) => run(() => git('add -- ' + sh(p)), refreshStatus);
const unstage = (p: string) => run(() => git('restore --staged -- ' + sh(p)), refreshStatus);
const stageAll = () => run(() => git('add -A'), refreshStatus);
const unstageAll = () => run(() => git('reset -q'), refreshStatus);
const discard = (f: FileEntry) => run(() => git(f.untracked ? 'clean -fdq -- ' + sh(f.path) : 'restore -- ' + sh(f.path)), refreshStatus);
const discardAllRun = () => run(() => git("restore -- . 2>/dev/null; " + GITP + "clean -fdq"), refreshStatus);
// Destructive: reverts every working-tree modification and deletes untracked files — confirm first.
const discardAll = () => {
  const n = lastUnstaged.length;
  showModal({
    title: 'Discard all changes',
    body: 'Discard all ' + (n ? n + ' ' : '') + 'change' + (n === 1 ? '' : 's') +
      ' in the working tree? Modifications are reverted and untracked files deleted — this can’t be undone.',
    confirmLabel: 'Discard All', danger: true,
    onConfirm: discardAllRun,
  });
};

// Commit staged changes; returns true on success so the split-button variants can chain push/sync.
// amend re-commits into HEAD (keeps the old message when the box is empty, else rewrites it).
async function doCommit(amend = false): Promise<boolean> {
  const msg = $<HTMLTextAreaElement>('msg').value.trim();
  if (!amend && !msg) { logShow('Enter a commit message.'); return false; }
  const args = amend
    ? (msg ? 'commit --amend -m ' + sh(msg) : 'commit --amend --no-edit')
    : 'commit -m ' + sh(msg);
  let ok = false;
  await run(() => git(args), async (r, text) => {
    if (r.exitCode === 0) {
      ok = true;
      $<HTMLTextAreaElement>('msg').value = ''; $('identity').classList.add('hide'); logHide(); refreshCommitState();
      await refreshStatus(); await refreshBranches();
    } else if (/who you are|user\.email|empty ident|Author identity/i.test(text)) {
      $('identity').classList.remove('hide'); logShow('Set your git identity, then commit again.');
    }
  });
  return ok;
}
async function commit() { await doCommit(); }
async function commitAmend() { await doCommit(true); }
async function commitAndPush() { if (await doCommit()) await push(); }
async function commitAndSync() { if (await doCommit()) { await pull(); await push(); } }

// ---- generate a commit message via an agent CLI (tool/model/detail configured in Settings → Source Control) ----
async function generateCommitMessage() {
  if (busy) return;
  if (!lastStaged.length && !lastUnstaged.length) { logShow('No changes to describe — make or stage some changes first.'); return; }
  const c = await api('config.all');
  const cfg = (c.ok && c.data ? c.data : {}) as Record<string, string>;
  const tool = cfg['scm.commitMsg.tool'] || 'claude';
  const model = (cfg['scm.commitMsg.model'] || '').trim();
  const detail = cfg['scm.commitMsg.detail'] || 'summary';
  const custom = (cfg['scm.commitMsg.customCommand'] || '').trim();
  if (tool === 'custom' && !custom) {
    logShow('Set a command in Settings → Source Control → “Generate commit message · custom command”.');
    return;
  }

  const instruction = (detail === 'detailed'
    ? 'Write a git commit message: a concise imperative subject line of about 50 characters, then a blank line, then a short body of bullet points saying what changed and why.'
    : 'Write a single-line git commit message: one concise imperative subject of about 50 characters, no body.')
    + ' Base it only on the diff piped to your stdin. Output ONLY the commit message text — no code fences, no quotes, no preamble.';

  // Prefer the staged diff (what a commit would include); else all working-tree changes, with untracked
  // files added as /dev/null diffs so brand-new files are described by their content, not just their name.
  const collect =
    `S="$(${GITP} diff --cached)"; ` +
    `if [ -n "$S" ]; then printf '%s\\n' "$S"; ` +
    `else ${GITP} diff; ${GITP} ls-files --others --exclude-standard -z | ` +
    `xargs -0 -r -I {} ${GITP} diff --no-index --no-color -- /dev/null {} 2>/dev/null; fi`;

  const modelArg = model ? ' --model ' + sh(model) : '';
  const toolCmd = tool === 'custom' ? custom
    : tool === 'opencode' ? 'opencode run' + modelArg + ' ' + sh(instruction)
      : 'claude -p' + modelArg + ' ' + sh(instruction);

  const gen = $('genMsg');
  gen.classList.add('busy'); setBusy(true); logHide();
  const r = await exec('{ ' + collect + ' ; } | ' + toolCmd, { workdir: repo, timeoutMs: 180000 });
  setBusy(false); gen.classList.remove('busy');

  const raw = out(r);
  if (r.exitCode !== 0 || !raw.trim()) {
    logShow(raw || ('Could not run “' + tool + '”. Is it installed and signed in inside the runtime?'));
    return;
  }
  const msg = raw.replace(/^\s*```[a-z]*\s*\n?/i, '').replace(/\n?```\s*$/i, '').trim();
  if (!msg) { logShow('The agent returned an empty message.'); return; }
  const ta = $<HTMLTextAreaElement>('msg');
  ta.value = msg;
  ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 120) + 'px';
  refreshCommitState();
}
function toggleCommitMenu() {
  const m = $('commitMenu');
  if (!m.classList.contains('hide')) { closePops(); return; }
  closePops();
  // Commit / Commit & Push / Commit & Sync all need a message; only Amend can run without one.
  const noMsg = !$<HTMLTextAreaElement>('msg').value.trim();
  const item = (a: string, label: string, off = false) =>
    '<div class="row tap' + (off ? ' off' : '') + '" data-a="' + a + '"><span class="k">' + label + '</span></div>';
  m.innerHTML = item('commit', 'Commit', noMsg) + item('amend', 'Commit (Amend)') +
    item('push', 'Commit &amp; Push', noMsg) + item('sync', 'Commit &amp; Sync', noMsg);
  openPop(m, $('commitMore'));
  m.querySelectorAll<HTMLElement>('.row.tap').forEach((row) => {
    row.onclick = () => {
      if (row.classList.contains('off')) return;
      const a = row.getAttribute('data-a');
      closePops();
      if (a === 'commit') void commit();
      else if (a === 'amend') void commitAmend();
      else if (a === 'push') void commitAndPush();
      else if (a === 'sync') void commitAndSync();
    };
  });
}
async function saveIdentity() {
  const n = $<HTMLInputElement>('idName').value.trim(), e = $<HTMLInputElement>('idEmail').value.trim();
  if (!n || !e) { logShow('Enter both a name and an email.'); return; }
  await run(() => exec('git config --global user.name ' + sh(n) + ' && git config --global user.email ' + sh(e)),
    (r) => { if (r.exitCode === 0) { $('identity').classList.add('hide'); logShow('Identity saved — commit again.'); } });
}

// ---- branches ----
let branchLines: string[] = [];
async function refreshBranches() {
  const r = await git("branch -a --format='%(refname:short)%09%(HEAD)'");
  branchLines = out(r).split('\n').map((s) => s.trim()).filter(Boolean);
}
function toggleBranchMenu() {
  const m = $('branchMenu');
  if (!m.classList.contains('hide')) { closePops(); return; }
  closePops();
  const seen: Record<string, boolean> = {};
  let html = '<div class="row hdrow"><span class="k">Branches</span><span class="sp"></span><button id="mgmtBtn" class="poplink">Manage</button></div>';
  branchLines.forEach((line) => {
    const parts = line.split('\t');
    const name = parts[0]; const cur = parts[1] === '*';
    const remote = name.indexOf('origin/') === 0;
    const short = remote ? name.slice(name.indexOf('/') + 1) : name;
    if (short === 'HEAD') return;
    if (seen[short] && !cur) return; seen[short] = true;
    html += '<div class="row tap" data-b="' + escapeAttr(short) + '">' +
      '<span class="k' + (cur ? ' cur' : '') + '">' + escapeHtml(short) + '</span>' +
      (remote ? '<span class="tag">remote</span>' : '') + (cur ? '<span class="tag">current</span>' : '') + '</div>';
  });
  html += '<div class="create"><input id="newBranch" placeholder="new-branch-name"><button id="createBranch" class="btn ghost sm">Create</button></div>';
  m.innerHTML = html;
  openPop(m, $('branchBtn'));
  m.querySelectorAll<HTMLElement>('.row.tap').forEach((row) => {
    row.onclick = () => { const b = row.getAttribute('data-b')!; if (!row.querySelector('.cur')) checkout(b); };
  });
  $('createBranch').onclick = createBranch;
  $('mgmtBtn').onclick = () => { closePops(); void api('workbench.openView', { view: 'manage' }); };
}
const checkout = (name: string) => run(() => git('checkout ' + sh(name)), async () => { closePops(); await refreshStatus(); await refreshBranches(); });
function createBranch() {
  const el = document.getElementById('newBranch') as HTMLInputElement | null;
  const n = (el?.value || '').trim();
  if (!n) { logShow('Enter a branch name.'); return; }
  void run(() => git('checkout -b ' + sh(n)), async () => { closePops(); await refreshStatus(); await refreshBranches(); });
}

// ---- remote ----
// ---- sync ----
// Fetch is a passive sync — succeed silently (any incoming commits surface via the ahead/behind badge);
// run() already surfaces errors.
const fetch_ = () => run(() => git('fetch --all --prune', 120000), async () => { await refreshStatus(); await refreshBranches(); });
// A plain `git pull` aborts when uncommitted local changes would be overwritten by the incoming
// merge/rebase (leaving the user stuck on the raw git error). Detect that and offer to stash-pull-reapply
// via `--autostash`, so incoming changes land without losing local work; if the re-apply conflicts the
// files surface in "Merge Changes" like any other conflict.
const PULL_DIRTY_RE = /would be overwritten|commit your changes or stash them|cannot pull with rebase|you have unstaged changes|not uptodate/i;
const pullAutostash = () => run(() => git('pull --autostash', 180000), async (_r, t) => { logShow(t || 'Up to date.'); await refreshStatus(); await refreshBranches(); });
const pull = () => run(() => git('pull', 180000), async (r, t) => {
  if (r.exitCode !== 0 && PULL_DIRTY_RE.test(t)) {
    showModal({
      title: 'Uncommitted changes',
      body: 'Local changes would be overwritten by the incoming update. Stash them, pull, then re-apply your changes on top?',
      confirmLabel: 'Stash &amp; Pull',
      onConfirm: () => { void pullAutostash(); },
    });
    return;
  }
  logShow(t || 'Up to date.'); await refreshStatus(); await refreshBranches();
});
async function push() {
  await run(() => git('push', 180000), async (r, text) => {
    if (r.exitCode === 0) { logShow(text || 'Pushed.'); await refreshStatus(); return; }
    if (/has no upstream branch|set-upstream/i.test(text)) {
      const b = out(await git('rev-parse --abbrev-ref HEAD')).trim();
      const r2 = await git('push -u origin ' + sh(b), 180000);
      logShow(out(r2) || 'Pushed.'); await refreshStatus(); await refreshBranches();
    }
  });
}

// ---- GitHub state indicator (dot on the drawer header; sign-in + identity live on the auth page) ----
async function refreshGh() {
  const user = out(await exec('git config --global --get github.user 2>/dev/null', { workdir: projectPath })).trim();
  const dot = document.getElementById('ghDot'); if (dot) dot.classList.toggle('hide', !user);
}

// ---- GitHub + git-identity auth page (opened in the editor area via workbench.openView) ----
const OCTOCAT = '<svg viewBox="0 0 16 16" width="24" height="24"><path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>';

async function renderAuthPage() {
  const info = await api('workbench.projectInfo');
  projectPath = info.ok && info.data && info.data.path ? info.data.path : null;
  document.body.className = 'authpage';
  document.body.innerHTML =
    '<div class="page">' +
    '<div class="page-hd">' + OCTOCAT + '<div><h1>Source Control</h1><div class="sub">Sign in to GitHub and set your commit identity</div></div></div>' +
    '<div class="card" id="ghCard"></div>' +
    '<div class="card"><h2>Git identity</h2>' +
    '<div class="muted">The author name and email on your commits. Also editable in <b>Settings → Source Control</b>.</div>' +
    '<div class="frow"><label>Name</label><input id="idName" placeholder="Your name"></div>' +
    '<div class="frow"><label>Email</label><input id="idEmail" placeholder="you@example.com" autocapitalize="none" spellcheck="false"></div>' +
    '<div class="brow"><button id="idSave" class="btn primary">Save identity</button><span class="msg" id="idMsg"></span></div></div>' +
    '</div>';
  $('idSave').onclick = authSaveIdentity;
  await loadAuthState();
}

async function loadAuthState() {
  const user = out(await exec('git config --global --get github.user 2>/dev/null')).trim();
  const name = out(await exec('git config --global --get user.name 2>/dev/null')).trim();
  const email = out(await exec('git config --global --get user.email 2>/dev/null')).trim();
  const nEl = document.getElementById('idName') as HTMLInputElement | null; if (nEl) nEl.value = name;
  const eEl = document.getElementById('idEmail') as HTMLInputElement | null; if (eEl) eEl.value = email;
  const card = $('ghCard');
  if (user) {
    card.innerHTML = '<h2>GitHub</h2>' +
      '<div class="gh-user"><span class="av lg">' + escapeHtml(user.slice(0, 1).toUpperCase()) + '</span>' +
      '<div><div class="uname">' + escapeHtml(user) + '</div><div class="muted" style="margin:0">Signed in · credentials saved for push / pull</div></div></div>' +
      '<div class="brow"><button id="ghOut" class="btn ghost">Sign out</button><span class="msg" id="ghMsg"></span></div>';
    $('ghOut').onclick = authSignOut;
  } else {
    card.innerHTML = '<h2>Sign in to GitHub</h2>' +
      '<div class="muted">Your username and a <b>Personal Access Token</b> (<b>repo</b> scope). Stored via git’s credential helper so push / pull just work.</div>' +
      '<div class="frow"><label>Username</label><input id="ghUsr" placeholder="GitHub username" autocapitalize="none" autocorrect="off" spellcheck="false"></div>' +
      '<div class="frow"><label>Token</label><input id="ghTok" type="password" placeholder="ghp_… or github_pat_…"></div>' +
      '<div class="brow"><button id="ghIn" class="btn primary">Sign in</button><span class="msg" id="ghMsg"></span></div>' +
      '<div class="muted" style="margin-top:10px">Create a token at <a id="ghLink">github.com/settings/tokens</a></div>';
    $('ghIn').onclick = authSignIn;
    $('ghLink').onclick = () => api('workbench.openUrl', { url: 'https://github.com/settings/tokens/new?scopes=repo&description=JCode' });
  }
}
function setMsg(id: string, t: string, err?: boolean) { const m = document.getElementById(id); if (!m) return; m.textContent = t ? ' ' + t : ''; m.classList.toggle('err', !!err); }

async function authSignIn() {
  const user = ($<HTMLInputElement>('ghUsr').value || '').trim();
  const token = ($<HTMLInputElement>('ghTok').value || '').trim();
  if (!user || !token) { setMsg('ghMsg', 'Enter your username and token.', true); return; }
  $<HTMLButtonElement>('ghIn').disabled = true; setMsg('ghMsg', 'Saving…');
  // Without git in the runtime the config/credential writes silently no-op — and because the command
  // ends in a plain `printf` that still exits 0, sign-in would look like it "did nothing" (no banner,
  // no error). Check up front and point the user at the install path. (A fresh environment set up with
  // bootstrap skipped has no git until it's installed from Tools -> Toolchains.)
  const hasGit = out(await exec('command -v git >/dev/null 2>&1 && echo yes')).trim() === 'yes';
  if (!hasGit) {
    $<HTMLButtonElement>('ghIn').disabled = false;
    setMsg('ghMsg', "Git isn’t installed in this environment yet — install it from Tools → Toolchains (git), then sign in.", true);
    return;
  }
  const email = user + '@users.noreply.github.com';
  const cmd = 'git config --global credential.helper store; ' +
    'git config --global github.user ' + sh(user) + '; ' +
    'if [ -z "$(git config --global --get user.name)" ]; then git config --global user.name ' + sh(user) + '; fi; ' +
    'if [ -z "$(git config --global --get user.email)" ]; then git config --global user.email ' + sh(email) + '; fi; ' +
    "umask 077; touch ~/.git-credentials; sed -i '/@github\\.com$/d' ~/.git-credentials 2>/dev/null; " +
    "printf 'https://%s:%s@github.com\\n' \"$GH_USER\" \"$GH_TOKEN\" >> ~/.git-credentials";
  const r = await exec(cmd, { env: { GH_USER: user, GH_TOKEN: token } });
  $<HTMLButtonElement>('ghIn').disabled = false;
  if (r.exitCode !== 0) { setMsg('ghMsg', out(r) || 'Failed to save credentials.', true); return; }
  // The multi-statement command's exit code is the final printf's, so it can't detect a mid-command
  // git failure — confirm the username actually landed before claiming success.
  const saved = out(await exec('git config --global --get github.user 2>/dev/null')).trim();
  if (saved !== user) { setMsg('ghMsg', 'Could not save credentials — is git working in this environment?', true); return; }
  await loadAuthState();
}
async function authSignOut() {
  await exec("git config --global --unset github.user 2>/dev/null; sed -i '/@github\\.com$/d' ~/.git-credentials 2>/dev/null; true");
  await loadAuthState();
}
async function authSaveIdentity() {
  const n = ($<HTMLInputElement>('idName').value || '').trim(), e = ($<HTMLInputElement>('idEmail').value || '').trim();
  if (!n || !e) { setMsg('idMsg', 'Enter both a name and an email.', true); return; }
  const r = await exec('git config --global user.name ' + sh(n) + ' && git config --global user.email ' + sh(e));
  setMsg('idMsg', r.exitCode === 0 ? 'Saved.' : (out(r) || 'Failed.'), r.exitCode !== 0);
}

// ---- Git manage page (branches + history), opened in the editor via workbench.openView #manage ----
const BRANCH_ICON = '<svg viewBox="0 0 16 16" width="24" height="24"><path fill="currentColor" d="M11.75 2.5a.75.75 0 100 1.5.75.75 0 000-1.5zm-2.25.75a2.25 2.25 0 113 2.122V6A2.5 2.5 0 0110 8.5H6a1 1 0 00-1 1v1.128a2.251 2.251 0 11-1.5 0V5.372a2.25 2.25 0 111.5 0v1.836A2.492 2.492 0 016 7h4a1 1 0 001-1v-.628A2.25 2.25 0 019.5 3.25zM4.25 12a.75.75 0 100 1.5.75.75 0 000-1.5zM3.5 3.25a.75.75 0 111.5 0 .75.75 0 01-1.5 0z"></path></svg>';
let manageTab: 'local' | 'remote' = 'local';

async function renderManagePage() {
  const info = await api('workbench.projectInfo');
  projectPath = info.ok && info.data && info.data.path ? info.data.path : null;
  repo = null;
  if (projectPath) {
    const top = await rawGit(projectPath, 'rev-parse --show-toplevel 2>/dev/null');
    const root = out(top).split('\n').filter(Boolean).pop() || '';
    if (top.exitCode === 0 && root) repo = root;
  }
  document.body.className = 'authpage';
  document.body.innerHTML =
    '<div class="page">' +
    '<div class="page-hd">' + BRANCH_ICON +
    '<div style="flex:1"><h1>Source Control</h1><div class="sub">Manage branches &amp; view history</div></div>' +
    '<button id="mFetch" class="btn ghost">Fetch</button></div>' +
    '<div id="mNotice"></div>' +
    '<div class="card" id="mBranchCard">' +
      '<div class="mhd"><h2 style="margin:0">Branches</h2><span style="flex:1"></span>' +
        '<div class="seg"><button id="segLocal" class="on">Local</button><button id="segRemote">Remote</button></div></div>' +
      '<div class="mcreate"><input id="mNewBranch" placeholder="new-branch-name" autocapitalize="none" spellcheck="false"><button id="mCreate" class="btn primary">Create</button></div>' +
      '<div class="msg" id="mMsg"></div>' +
      '<div id="mBranchList" class="mlist"></div></div>' +
    '<div class="card"><h2>Commits · <span id="mBranch" class="mono"></span></h2>' +
      '<div id="mCommitList" class="mlist"></div></div>' +
    '</div>';
  if (!repo) {
    document.getElementById('mNotice')!.innerHTML = '<div class="card"><div class="muted">This project isn’t a git repository.</div></div>';
    ($('mBranchCard')).style.display = 'none';
    return;
  }
  $('mFetch').onclick = () => manageRun(() => git('fetch --all --prune', 120000), 'Fetched.');
  $('segLocal').onclick = () => { manageTab = 'local'; setSeg(); void renderBranchList(); };
  $('segRemote').onclick = () => { manageTab = 'remote'; setSeg(); void renderBranchList(); };
  $('mCreate').onclick = manageCreate;
  await refreshManage();
}
function setSeg() { $('segLocal').classList.toggle('on', manageTab === 'local'); $('segRemote').classList.toggle('on', manageTab === 'remote'); }
function mMsg(t: string, err?: boolean) { const m = document.getElementById('mMsg'); if (!m) return; m.textContent = t || ''; m.classList.toggle('err', !!err); }

async function refreshManage() {
  const cur = out(await git('rev-parse --abbrev-ref HEAD')).trim();
  const bEl = document.getElementById('mBranch'); if (bEl) bEl.textContent = cur;
  await renderBranchList(cur);
  await renderCommits();
}
async function renderBranchList(cur?: string) {
  const current = cur ?? out(await git('rev-parse --abbrev-ref HEAD')).trim();
  const list = $('mBranchList'); list.innerHTML = '';
  if (manageTab === 'local') {
    const r = await git("branch --format='%(refname:short)%09%(HEAD)%09%(upstream:short)'");
    const lines = out(r).split('\n').map((s) => s.trim()).filter(Boolean);
    if (!lines.length) { list.innerHTML = '<div class="empty">No local branches.</div>'; return; }
    lines.forEach((line) => { const p = line.split('\t'); list.appendChild(localBranchRow(p[0], p[1] === '*', p[2] || '')); });
  } else {
    const r = await git("branch -r --format='%(refname:short)'");
    const names = out(r).split('\n').map((s) => s.trim()).filter(Boolean).filter((n) => n.indexOf('/HEAD') < 0);
    if (!names.length) { list.innerHTML = '<div class="empty">No remote branches — Fetch to update.</div>'; return; }
    names.forEach((n) => list.appendChild(remoteBranchRow(n, current)));
  }
}
function mkBtn(label: string, cls: string, fn: () => void): HTMLButtonElement {
  const b = document.createElement('button'); b.className = cls; b.textContent = label; b.onclick = fn; return b;
}
// Modal dialog (confirm / simple form) — built in-page because WebView confirm()/prompt() are no-ops.
function showModal(opts: { title: string; body?: string; input?: { value: string; placeholder?: string }; confirmLabel: string; danger?: boolean; onConfirm: (value?: string) => void }) {
  const back = document.createElement('div'); back.className = 'modal-scrim';
  const dlg = document.createElement('div'); dlg.className = 'modal';
  let html = '<div class="modal-title">' + escapeHtml(opts.title) + '</div>';
  if (opts.body) html += '<div class="modal-body">' + opts.body + '</div>';
  if (opts.input) html += '<input class="modal-input" id="__mdlInput">';
  html += '<div class="modal-actions"><button class="btn ghost" id="__mdlCancel">Cancel</button>' +
    '<button class="btn ' + (opts.danger ? 'dfill' : 'primary') + '" id="__mdlOk">' + escapeHtml(opts.confirmLabel) + '</button></div>';
  dlg.innerHTML = html; back.appendChild(dlg); document.body.appendChild(back);
  const close = () => back.remove();
  const inp = opts.input ? (document.getElementById('__mdlInput') as HTMLInputElement) : null;
  if (inp && opts.input) { inp.value = opts.input.value; inp.placeholder = opts.input.placeholder || ''; inp.autocapitalize = 'none'; inp.spellcheck = false; setTimeout(() => { inp.focus(); inp.select(); }, 30); }
  const ok = () => { close(); opts.onConfirm(inp ? inp.value : undefined); };
  document.getElementById('__mdlOk')!.onclick = ok;
  document.getElementById('__mdlCancel')!.onclick = close;
  back.onclick = (e) => { if (e.target === back) close(); };
  if (inp) inp.onkeydown = (e) => { if (e.key === 'Enter') ok(); };
}
function mkDelete(cmd: () => Promise<ExecResult>, name: string): HTMLButtonElement {
  return mkBtn('Delete', 'btn ghost danger', () => showModal({
    title: 'Delete branch',
    body: 'Delete <b>' + escapeHtml(name) + '</b>? This can’t be undone.',
    confirmLabel: 'Delete', danger: true,
    onConfirm: () => manageRun(cmd, 'Deleted ' + name),
  }));
}
function mkRename(oldName: string): HTMLButtonElement {
  return mkBtn('Rename', 'btn ghost', () => showModal({
    title: 'Rename branch',
    body: 'Rename <b>' + escapeHtml(oldName) + '</b> to:',
    input: { value: oldName, placeholder: 'branch name' },
    confirmLabel: 'Rename',
    onConfirm: (v) => { const nn = (v || '').trim(); if (!nn || nn === oldName) return; void manageRun(() => git('branch -m ' + sh(oldName) + ' ' + sh(nn)), 'Renamed to ' + nn); },
  }));
}
// Checkout — warn first if the working tree is dirty (uncommitted/staged), else switch directly.
function checkoutBranch(name: string) {
  void (async () => {
    const dirty = out(await git('status --porcelain')).trim();
    const go = () => manageRun(() => git('checkout ' + sh(name)), 'Checked out ' + name);
    if (dirty) {
      showModal({
        title: 'Uncommitted changes',
        body: 'You have uncommitted changes. Checking out <b>' + escapeHtml(name) + '</b> may fail or carry them over.',
        confirmLabel: 'Checkout anyway',
        onConfirm: go,
      });
    } else go();
  })();
}
function localBranchRow(name: string, isCurrent: boolean, upstream: string): HTMLElement {
  const row = document.createElement('div'); row.className = 'brow';
  const nm = document.createElement('span'); nm.className = 'bname' + (isCurrent ? ' cur' : ''); nm.textContent = name; nm.title = name;
  row.appendChild(nm);
  if (upstream) { const u = document.createElement('span'); u.className = 'bup'; u.textContent = upstream; row.appendChild(u); }
  if (isCurrent) { const t = document.createElement('span'); t.className = 'btag'; t.textContent = 'current'; row.appendChild(t); }
  const act = document.createElement('div'); act.className = 'bact';
  if (!isCurrent) act.appendChild(mkBtn('Checkout', 'btn ghost', () => checkoutBranch(name)));
  act.appendChild(mkRename(name));
  if (!isCurrent) act.appendChild(mkDelete(() => git('branch -D ' + sh(name)), name));
  row.appendChild(act);
  return row;
}
function remoteBranchRow(name: string, current: string): HTMLElement {
  const slash = name.indexOf('/');
  const short = slash >= 0 ? name.slice(slash + 1) : name;
  const row = document.createElement('div'); row.className = 'brow';
  const nm = document.createElement('span'); nm.className = 'bname' + (short === current ? ' cur' : ''); nm.textContent = name; nm.title = name;
  row.appendChild(nm);
  const act = document.createElement('div'); act.className = 'bact';
  // Remote branches are checkout-only — deleting a remote branch is destructive and easy to do by
  // accident on a touch device, so it's intentionally not offered here.
  act.appendChild(mkBtn('Checkout', 'btn ghost', () => checkoutBranch(short)));
  row.appendChild(act);
  return row;
}
async function manageRun(cmd: () => Promise<ExecResult>, okMsg: string) {
  mMsg('Working…');
  const r = await cmd();
  if (r.exitCode === 0) { mMsg(okMsg); await refreshManage(); }
  else { mMsg(out(r) || 'Failed.', true); await renderBranchList(); }
}
function manageCreate() {
  const el = document.getElementById('mNewBranch') as HTMLInputElement | null;
  const n = (el?.value || '').trim();
  if (!n) { mMsg('Enter a branch name.', true); return; }
  void manageRun(() => git('checkout -b ' + sh(n)), 'Created ' + n).then(() => { if (el) el.value = ''; });
}
async function renderCommits() {
  const list = $('mCommitList'); list.innerHTML = '';
  const r = await git("log -n 100 --pretty=format:'%h%x1f%an%x1f%ar%x1f%s'");
  const lines = r.exitCode === 0 ? out(r).split('\n').filter(Boolean) : [];
  if (!lines.length) { list.innerHTML = '<div class="empty">No commits yet.</div>'; return; }
  lines.forEach((line) => {
    const p = line.split('\x1f');
    const row = document.createElement('div'); row.className = 'crow';
    const cl = document.createElement('div'); cl.className = 'cline';
    const h = document.createElement('span'); h.className = 'chash'; h.textContent = p[0] || '';
    const s = document.createElement('span'); s.className = 'csubj'; s.textContent = p[3] || ''; s.title = p[3] || '';
    cl.appendChild(h); cl.appendChild(s);
    const meta = document.createElement('div'); meta.className = 'cmeta'; meta.textContent = (p[1] || '') + ' · ' + (p[2] || '');
    row.appendChild(cl); row.appendChild(meta);
    list.appendChild(row);
  });
}

// ---- diff view (tap a changed file → its diff opens as a full editor page via workbench.openView) ----
const FILE_ICON = '<svg viewBox="0 0 16 16" width="24" height="24"><path fill="currentColor" d="M3 1.75C3 .78 3.78 0 4.75 0h4.69c.46 0 .9.18 1.23.51l2.82 2.82c.33.33.51.77.51 1.23v9.69c0 .97-.78 1.75-1.75 1.75H4.75A1.75 1.75 0 013 14.25V1.75zm6.5.81V4.5c0 .14.11.25.25.25h1.94L9.5 2.56z"/></svg>';

// Encode which diff to show in the view hash — diff:<mode>:<repo>:<path> — where mode is s = staged
// (index vs HEAD), w = working-tree (vs index), u = untracked (whole file as an addition), and repo is
// the SCM panel's active repo root. The app derives a nice tab title from the passed `title`.
function openDiff(f: FileEntry, staged: boolean) {
  if (f.code === '!') { openMerge(f); return; } // conflicted file → the 3-way merge editor
  const mode = staged ? 's' : (f.untracked ? 'u' : 'w');
  // Carry the active repo into the hash — the diff opens as its own page and can't see this sidebar's
  // in-memory `repo`, so without this it would re-guess the repo (wrong in a multi-repo workspace).
  const r = repo ? encodeURIComponent(repo) : '';
  void api('workbench.openView', { view: 'diff:' + mode + ':' + r + ':' + encodeURIComponent(f.path), title: baseName(f.path) + ' — diff' });
}

// Open the real working-tree file in the editor at [line]. `repo` is the guest repo root, so
// repo + '/' + <repo-relative path> is a /workspace path the app maps back to the host file.
function openFileAt(path: string, line: number) {
  if (!repo) return;
  void api('workbench.openFile', { path: repo.replace(/\/$/, '') + '/' + path, line });
}

async function renderDiffPage() {
  // diff:<mode>:<repo>:<path> (current) or diff:<mode>:<path> (legacy tabs). Prefer the repo carried in
  // the hash, then the remembered active repo, and only fall back to deriving it from the selected project.
  const m3 = VIEW.match(/^diff:([swu]):([^:]*):([\s\S]*)$/);
  const m2 = m3 ? null : VIEW.match(/^diff:([swu]):([\s\S]*)$/);
  const mode = m3 ? m3[1] : (m2 ? m2[1] : 'w');
  let path = m3 ? m3[3] : (m2 ? m2[2] : '');
  try { path = decodeURIComponent(path); } catch { /* the hash was already decoded */ }
  let hashRepo = '';
  if (m3 && m3[2]) { try { hashRepo = decodeURIComponent(m3[2]); } catch { hashRepo = m3[2]; } }
  repo = hashRepo || localStorage.getItem('scm.activeRepo') || null;
  if (!repo) {
    const info = await api('workbench.projectInfo');
    projectPath = info.ok && info.data && info.data.path ? info.data.path : null;
    if (projectPath) {
      const top = await rawGit(projectPath, 'rev-parse --show-toplevel 2>/dev/null');
      const root = out(top).split('\n').filter(Boolean).pop() || '';
      if (top.exitCode === 0 && root) repo = root;
    }
  }
  const sub = mode === 's' ? 'Staged changes' : mode === 'u' ? 'New file' : 'Working-tree changes';
  document.body.className = 'authpage';
  document.body.innerHTML =
    '<div class="page pagewide">' +
    '<div class="page-hd">' + FILE_ICON +
    '<div style="flex:1;min-width:0"><h1 class="mono difftitle">' + escapeHtml(path) + '</h1><div class="sub">' + sub + '</div></div>' +
    '<button id="openFile" class="btn ghost">Open file</button></div>' +
    '<div id="diffBody" class="diffwrap"><div class="dempty">Loading diff…</div></div>' +
    '</div>';
  if (!repo) { document.getElementById('diffBody')!.innerHTML = '<div class="dempty">This project isn’t a git repository.</div>'; return; }
  const cmd = mode === 's' ? 'diff --cached --no-color -- ' + sh(path)
    : mode === 'u' ? 'diff --no-index --no-color -- /dev/null ' + sh(path)
      : 'diff --no-color -- ' + sh(path);
  const r = await rawGit(repo, cmd, 60000);
  const diffText = out(r);
  const firstHunk = diffText.match(/^@@ -\d+(?:,\d+)? \+(\d+)/m);
  const firstLine = firstHunk ? parseInt(firstHunk[1], 10) : 1;
  ($('openFile') as HTMLButtonElement).onclick = () => openFileAt(path, firstLine);
  renderDiffInto(document.getElementById('diffBody')!, diffText, path);
}

// Render unified-diff text as colored rows with a new-file line-number gutter; clicking a row jumps to
// that line in the real file. +added / -removed / @@ hunk / file-header meta.
function renderDiffInto(el: HTMLElement, text: string, path: string) {
  if (!text.replace(/\s+$/, '')) { el.innerHTML = '<div class="dempty">No differences to show.</div>'; return; }
  el.innerHTML = '';
  const frag = document.createDocumentFragment();
  let newLine = 0;
  for (const line of text.replace(/\n$/, '').split('\n')) {
    let kind = 'ctx', gutter = '', openLn = 0;
    const hm = line.match(/^@@ -\d+(?:,\d+)? \+(\d+)/);
    if (hm) { kind = 'hunk'; newLine = parseInt(hm[1], 10); openLn = newLine; }
    else if (/^(diff --git|index |new file|deleted file|old mode|new mode|similarity |rename |copy |--- |\+\+\+ |Binary )/.test(line)) { kind = 'meta'; }
    else if (line[0] === '+') { kind = 'add'; gutter = String(newLine); openLn = newLine; newLine++; }
    else if (line[0] === '-') { kind = 'del'; openLn = newLine; }
    else { gutter = String(newLine); openLn = newLine; newLine++; }
    const div = document.createElement('div');
    div.className = 'dl' + (kind === 'ctx' ? '' : ' ' + kind);
    const g = document.createElement('span'); g.className = 'ln'; g.textContent = gutter;
    const c = document.createElement('span'); c.className = 'code'; c.textContent = line || ' ';
    div.appendChild(g); div.appendChild(c);
    if (openLn > 0 && kind !== 'meta') { div.classList.add('tap'); div.title = 'Open at line ' + openLn; div.onclick = () => openFileAt(path, openLn); }
    frag.appendChild(div);
  }
  el.appendChild(frag);
}

// ---- merge conflict resolution: the "Merge Changes" list actions + the 3-way merge editor page ----

// Take one whole side of a conflicted file, then stage it. --ours = current branch (HEAD),
// --theirs = the incoming branch being merged/rebased.
async function resolveConflict(path: string, side: 'ours' | 'theirs') {
  await run(() => git('checkout --' + side + ' -- ' + sh(path)), async (r) => {
    if (r.exitCode === 0) await git('add -- ' + sh(path));
    await refreshStatus();
  });
}
// Stage a hand-edited conflicted file as resolved.
async function markResolved(path: string) {
  await run(() => git('add -- ' + sh(path)), async () => { await refreshStatus(); });
}
// Open the 3-way merge editor for a conflicted file (its own editor page, like the diff view).
function openMerge(f: FileEntry) {
  const r = repo ? encodeURIComponent(repo) : '';
  void api('workbench.openView', { view: 'merge:' + r + ':' + encodeURIComponent(f.path), title: baseName(f.path) + ' — merge' });
}

interface MergeSeg { conflict: boolean; text: string[]; ours: string[]; theirs: string[] }
// Split file contents (with git conflict markers) into plain + conflict segments. Handles the default
// (<<< ours === theirs >>>) and diff3 (adds ||| base) marker styles.
function parseConflicts(raw: string): MergeSeg[] {
  const lines = raw.split('\n');
  const segs: MergeSeg[] = [];
  let buf: string[] = [];
  for (let i = 0; i < lines.length;) {
    if (lines[i].indexOf('<<<<<<<') === 0) {
      if (buf.length) { segs.push({ conflict: false, text: buf, ours: [], theirs: [] }); buf = []; }
      const ours: string[] = [], theirs: string[] = [];
      i++;
      while (i < lines.length && lines[i].indexOf('=======') !== 0 && lines[i].indexOf('|||||||') !== 0) ours.push(lines[i++]);
      if (i < lines.length && lines[i].indexOf('|||||||') === 0) { i++; while (i < lines.length && lines[i].indexOf('=======') !== 0) i++; }
      if (i < lines.length && lines[i].indexOf('=======') === 0) i++;
      while (i < lines.length && lines[i].indexOf('>>>>>>>') !== 0) theirs.push(lines[i++]);
      if (i < lines.length && lines[i].indexOf('>>>>>>>') === 0) i++;
      segs.push({ conflict: true, text: [], ours, theirs });
    } else buf.push(lines[i++]);
  }
  if (buf.length) segs.push({ conflict: false, text: buf, ours: [], theirs: [] });
  return segs;
}

// The 3-way merge editor: shows each conflict's Current (ours) and Incoming (theirs) sides plus an
// editable Result; on save it writes the reassembled file and stages it.
async function renderMergePage() {
  const m = VIEW.match(/^merge:([^:]*):([\s\S]*)$/);
  let path = ''; try { path = decodeURIComponent(m ? m[2] : ''); } catch { path = m ? m[2] : ''; }
  let mRepo = ''; try { mRepo = decodeURIComponent(m && m[1] ? m[1] : ''); } catch { mRepo = m ? m[1] : ''; }
  repo = mRepo || localStorage.getItem('scm.activeRepo') || repo;
  document.body.className = 'authpage';
  document.body.innerHTML =
    '<div class="page pagewide">' +
    '<div class="page-hd">' + FILE_ICON +
    '<div style="flex:1;min-width:0"><h1 class="mono difftitle">' + escapeHtml(path) + '</h1><div class="sub">Resolve conflicts</div></div>' +
    '<button id="mSave" class="btn primary">Save &amp; resolve</button></div>' +
    '<div id="mNote" class="notice hide"></div>' +
    '<div id="mBody" class="mergewrap"><div class="dempty">Loading…</div></div>' +
    '</div>';
  if (!repo) { $('mBody').innerHTML = '<div class="dempty">No repository.</div>'; return; }
  const rr = await exec('cat ' + sh(path), { workdir: repo });
  const raw = rr.stdout || '';
  const segs = parseConflicts(raw);
  if (!segs.some((s) => s.conflict)) {
    $('mBody').innerHTML = '<div class="dempty">No conflict markers found. If this file is already resolved, use “Mark resolved” in the Merge Changes list.</div>';
    return;
  }
  const body = $('mBody'); body.innerHTML = '';
  const results: (HTMLTextAreaElement | null)[] = [];
  let ci = 0;
  for (const seg of segs) {
    if (!seg.conflict) {
      const n = seg.text.length;
      const onlyBlank = n === 1 && seg.text[0] === '';
      if (n && !onlyBlank) {
        const ctx = document.createElement('div'); ctx.className = 'mctx';
        ctx.textContent = '⋯ ' + n + ' unchanged line' + (n === 1 ? '' : 's') + ' ⋯';
        body.appendChild(ctx);
      }
      results.push(null);
      continue;
    }
    const idx = ci++;
    const oursText = seg.ours.join('\n'), theirsText = seg.theirs.join('\n');
    const block = document.createElement('div'); block.className = 'mconf';
    const hd = document.createElement('div'); hd.className = 'mconf-hd';
    const t = document.createElement('span'); t.className = 'mconf-t'; t.textContent = 'Conflict ' + (idx + 1); hd.appendChild(t);
    const ta = document.createElement('textarea'); ta.className = 'mresult mono'; ta.value = oursText;
    ta.rows = Math.min(20, Math.max(2, seg.ours.length + seg.theirs.length));
    const mk = (label: string, cls: string, val: string) => {
      const b = document.createElement('button'); b.className = 'btn tiny ' + cls; b.textContent = label;
      b.onclick = () => { ta.value = val; }; return b;
    };
    hd.appendChild(mk('Current', 'cur', oursText));
    hd.appendChild(mk('Incoming', 'inc', theirsText));
    hd.appendChild(mk('Both', '', oursText + (oursText && theirsText ? '\n' : '') + theirsText));
    block.appendChild(hd);
    const sides = document.createElement('div'); sides.className = 'msides';
    const oc = document.createElement('div'); oc.className = 'mside cur';
    oc.innerHTML = '<div class="mside-t">Current (ours)</div><pre class="mono">' + escapeHtml(oursText) + '</pre>';
    const tc = document.createElement('div'); tc.className = 'mside inc';
    tc.innerHTML = '<div class="mside-t">Incoming (theirs)</div><pre class="mono">' + escapeHtml(theirsText) + '</pre>';
    sides.appendChild(oc); sides.appendChild(tc); block.appendChild(sides);
    const rt = document.createElement('div'); rt.className = 'mside-t'; rt.textContent = 'Result (editable)'; block.appendChild(rt);
    block.appendChild(ta);
    body.appendChild(block);
    results.push(ta);
  }
  ($('mSave') as HTMLButtonElement).onclick = async () => {
    const parts: string[] = [];
    for (let k = 0; k < segs.length; k++) {
      const seg = segs[k];
      parts.push(seg.conflict ? (results[k] ? results[k]!.value : '') : seg.text.join('\n'));
    }
    const merged = parts.join('\n');
    const b64 = btoa(unescape(encodeURIComponent(merged)));
    await run(() => exec('printf %s ' + sh(b64) + ' | base64 -d > ' + sh(path), { workdir: repo! }), async (r) => {
      const note = $('mNote'); note.classList.remove('hide');
      if (r.exitCode !== 0) { note.textContent = out(r) || 'Could not write the file.'; return; }
      await git('add -- ' + sh(path));
      note.innerHTML = '<b>Resolved and staged.</b> Close this tab and commit from Source Control.';
    });
  };
}

// ---- misc ----
function escapeHtml(s: string) { return s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] as string)); }
function escapeAttr(s: string) { return s.replace(/"/g, '&quot;'); }

// ---- Clone repo + Remote repo browser (opened in the editor via workbench.openView #clone / #remoteRepo) ----
// Must match WorkspaceManager.sanitizedFolderName so the clone target lines up with what the app
// registers. Additionally strips leading dots: workbench.addFolder refuses dot-named staged folders
// (and `ls`/hidden-file conventions would hide them), so a repo like ".dotfiles" stages as "dotfiles".
function sanitizeName(s: string): string {
  return (s || '').toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^[.-]+|-+$/g, '') || 'project';
}

// The whole clone flow lives in this extension; the app only provides infrastructure. Current hosts
// bind an ext4 staging dir at guest /sources and expose the generic workbench.addFolder bridge. The
// user picks Project / Workspace (or cancels) BEFORE the clone starts; the clone then lands in
// /sources only as a mechanical stopover and is adopted the moment it finishes — a repo shipping its
// own .jcode type keeps what it declares, the upfront pick covers repos that declare nothing. So
// nothing user-visible ever lives in /sources, and leftovers from interrupted sessions are swept,
// not surfaced. Older hosts have neither mount nor bridge: probed once via the mount's existence,
// the clone then lands directly in /workspace and is registered through the legacy
// workbench.openFolder, matching the pre-staging behavior.
const SOURCES = '/sources';
let sourcesSupported: boolean | null = null;

async function probeSources(): Promise<boolean> {
  if (sourcesSupported === null) {
    // Cache only a definitive yes/no — a transient exec failure (runtime still booting) must not
    // permanently route a staging-capable host down the legacy path.
    const t = out(await exec('test -d ' + SOURCES + ' && echo yes || echo no')).trim();
    if (t === 'yes' || t === 'no') sourcesSupported = t === 'yes';
    else return false;
  }
  return sourcesSupported;
}

function updateClonePreview() {
  const el = document.getElementById('clPreview');
  if (!el) return;
  const raw = (document.getElementById('clName') as HTMLInputElement | null)?.value || '';
  const name = raw.trim() ? sanitizeName(raw) : '…';
  el.textContent = 'Clones into ' + (sourcesSupported ? SOURCES : '/workspace') + '/' + name;
}

// Folder-name auto-fill: a valid web URL fills the name field with its repo segment (last path
// piece, .git stripped — the same default the clone itself would use). A name the user typed is
// never overwritten: the field is only auto-owned while empty or still holding the last auto-fill.
let lastAutoName = '';

function nameFromUrl(url: string): string {
  const parts = url.replace(/^https?:\/\//, '').replace(/\/+$/, '').split('/');
  if (parts.length < 2) return '';
  return (parts.pop() || '').replace(/\.git$/, '');
}

function autofillName() {
  const url = ((document.getElementById('clUrl') as HTMLInputElement | null)?.value || '').trim();
  const n = document.getElementById('clName') as HTMLInputElement | null;
  if (!n) return;
  const current = n.value.trim();
  if (current !== '' && current !== lastAutoName) return;
  const next = /^https?:\/\/\S+$/.test(url) ? nameFromUrl(url) : '';
  n.value = next;
  lastAutoName = next;
  updateClonePreview();
}

// Live peek at the repo before cloning: for a valid web URL, a commits-only shallow fetch (bare,
// depth 6, tree:0 filter — servers without partial-clone support just ignore the filter) shows the
// last 6 commits at the bottom of the page, styled like the SCM panel's history. Debounced while
// typing, superseded fetches are dropped, and any failure hides the section — the peek never blocks
// or gates cloning.
let peekSeq = 0;
let peekTimer: number | undefined;
let peekShownFor = '';

function schedulePeek() {
  if (peekTimer !== undefined) clearTimeout(peekTimer);
  peekTimer = window.setTimeout(() => void peekCommits(), 700);
}

async function peekCommits() {
  const holder = document.getElementById('clPeek');
  if (!holder) return;
  const url = ((document.getElementById('clUrl') as HTMLInputElement | null)?.value || '').trim();
  const seq = ++peekSeq;
  if (!/^https?:\/\/\S+$/.test(url)) { peekShownFor = ''; holder.innerHTML = ''; return; }
  if (url === peekShownFor) return;
  holder.innerHTML = '<div class="card" style="margin-top:10px"><div class="muted" style="font-size:12.5px">Loading latest commits…</div></div>';
  const tmp = '/root/.jcode-peek';
  const cmd = 'rm -rf ' + sh(tmp) + ' && ' + GITP + 'clone -q --bare --depth 6 --filter=tree:0 --no-tags ' +
    sh(url) + ' ' + sh(tmp) + ' && ' + GITP + '--git-dir=' + sh(tmp) +
    " log -n 6 --pretty=format:'%h%x1f%an%x1f%ar%x1f%s'; rm -rf " + sh(tmp);
  const r = await exec(cmd, { timeoutMs: 60000 });
  if (seq !== peekSeq) return;
  const cur = document.getElementById('clPeek');
  if (!cur) return;
  const lines = r.exitCode === 0 ? out(r).split('\n').filter((l) => l.includes('\x1f')) : [];
  if (!lines.length) { peekShownFor = ''; cur.innerHTML = ''; return; }
  peekShownFor = url;
  cur.innerHTML =
    '<div class="card" style="margin-top:10px"><h3 style="margin:0 0 4px">Latest commits</h3>' +
    lines.slice(0, 6).map((line) => {
      const p = line.split('\x1f');
      return '<div class="crow"><div class="cline"><span class="chash">' + escapeHtml(p[0] || '') + '</span>' +
        '<span class="csubj">' + escapeHtml(p[3] || '') + '</span></div>' +
        '<div class="cmeta">' + escapeHtml((p[1] || '') + ' · ' + (p[2] || '')) + '</div></div>';
    }).join('') + '</div>';
}

// Hand a staged folder to the host, which moves it out of /sources and opens it. Passing no `type`
// lets the host adopt the folder as whatever its own `.jcode` declares — a repo that ships one
// already knows what it is. The host answers `needsType` when the folder declares nothing, and the
// caller then retries with the type the user picked before the clone.
type AddOutcome = 'added' | 'needsType' | 'failed';

async function addStagedFolder(name: string, type: string, msgId: string): Promise<AddOutcome> {
  setBusy(true);
  setMsg(msgId, 'Adding…');
  try {
    const payload: Record<string, string> = { path: SOURCES + '/' + name };
    if (type) payload.type = type;
    const r = await api('workbench.addFolder', payload);
    if (!r || !r.ok) {
      setMsg(msgId, (r && (r as any).error) || 'Could not add the folder.', true);
      return 'failed';
    }
    if ((r.data as any) && (r.data as any).needsType) return 'needsType';
    const asWorkspace = !!((r.data as any) && (r.data as any).workspace);
    setMsg(msgId, (asWorkspace ? "Workspace '" : "Opened '") + name + (asWorkspace ? "' opened." : "'."));
    return 'added';
  } finally {
    setBusy(false);
  }
}

// The clone is in the workbench now, so this page has done its job — close the tab it was opened as
// (#clone or #remoteRepo). Hosts without the verb answer not-ok, which is a harmless no-op.
async function closeSelf() {
  await api('workbench.closeView', { view: VIEW });
}

// Renders the clone form. When opened from the Remote-repo browser (via cloneRemoteRepo) the URL and
// name are pre-filled and a Cancel returns to the list — the clone only runs when the user taps Clone.
async function renderClonePage(prefill?: { url?: string; name?: string; fromRemote?: boolean }) {
  document.body.className = 'authpage';
  const staging = await probeSources();
  // Nothing legitimate lives in /sources between clones — adoption happens the moment a clone
  // finishes — so quietly sweep leftovers from interrupted sessions instead of surfacing them.
  if (staging) void exec('find ' + SOURCES + ' -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null');
  document.body.innerHTML =
    '<div class="page">' +
    '<div class="page-hd">' + OCTOCAT + '<div><h1>Clone a repository</h1><div class="sub">Clone a Git repository into a new project</div></div></div>' +
    '<div class="card">' +
    '<div class="frow"><label>Repository URL</label><input id="clUrl" placeholder="https://github.com/owner/repo.git" autocapitalize="none" autocorrect="off" spellcheck="false"></div>' +
    '<div class="frow"><label>Folder name</label><input id="clName" placeholder="(optional — taken from the URL)"></div>' +
    '<div class="cl-preview" id="clPreview"></div>' +
    '<div class="brow"><button id="clBtn" class="btn primary">Clone</button>' +
    (prefill && prefill.fromRemote ? '<button id="clCancel" class="btn">Cancel</button>' : '') +
    '<span class="msg" id="clMsg"></span></div>' +
    '<pre class="modal-log" id="clLog" style="display:none;margin-top:10px"></pre>' +
    '</div><div id="clPeek"></div></div>';
  const u = document.getElementById('clUrl') as HTMLInputElement | null;
  if (u && prefill && prefill.url) u.value = prefill.url;
  const n = document.getElementById('clName') as HTMLInputElement | null;
  if (n && prefill && prefill.name) n.value = prefill.name;
  // A prefilled name (Remote Repo flow) counts as auto-filled, so later URL edits keep updating it.
  lastAutoName = n?.value.trim() || '';
  $('clBtn').onclick = () => void doClone();
  const cancel = document.getElementById('clCancel');
  if (cancel) cancel.onclick = () => void renderRemotePage();
  n?.addEventListener('input', updateClonePreview);
  u?.addEventListener('input', () => { autofillName(); schedulePeek(); });
  autofillName();
  updateClonePreview();
  schedulePeek();
}

// Ask BEFORE anything is downloaded how the repo should open; Cancel (or the scrim) clones nothing.
// Same modal vocabulary as the branch/commit confirms. A repo that ships its own .jcode type keeps
// what it declares — the pick only decides for repos that declare nothing.
function askCloneIntent(name: string, onChoice: (type: string) => void) {
  const back = document.createElement('div'); back.className = 'modal-scrim';
  const dlg = document.createElement('div'); dlg.className = 'modal';
  dlg.innerHTML =
    '<div class="modal-title">Clone &#8216;' + escapeHtml(name) + '&#8217;</div>' +
    '<div class="modal-body">Add <b>' + escapeHtml(name) + '</b> as a project, or open it as a ' +
    'workspace — its top-level folders become projects.</div>' +
    '<div class="modal-actions">' +
    '<button class="btn ghost" id="__ciCancel">Cancel</button>' +
    '<button class="btn ghost" id="__ciWorkspace">Open as Workspace</button>' +
    '<button class="btn primary" id="__ciProject">Add as Project</button></div>';
  back.appendChild(dlg); document.body.appendChild(back);
  const close = () => back.remove();
  document.getElementById('__ciCancel')!.onclick = close;
  back.onclick = (e) => { if (e.target === back) close(); };
  document.getElementById('__ciProject')!.onclick = () => { close(); onChoice('project'); };
  document.getElementById('__ciWorkspace')!.onclick = () => { close(); onChoice('workspace'); };
}

async function doClone(url0?: string, name0?: string) {
  const url = (url0 || (document.getElementById('clUrl') as HTMLInputElement | null)?.value || '').trim();
  if (!url) { setMsg('clMsg', 'Enter a repository URL.', true); return; }
  const raw = (name0 || (document.getElementById('clName') as HTMLInputElement | null)?.value || '').trim() ||
    url.replace(/\/$/, '').replace(/\.git$/, '').split('/').pop() || 'repo';
  const name = sanitizeName(raw);
  if (await probeSources()) askCloneIntent(name, (type) => void runStagedClone(url, name, type));
  else await runLegacyClone(url, name);
}

// Clone into /sources and adopt immediately: first ask the host to take the folder as whatever its
// own .jcode declares; when it declares nothing, retry with the type the user picked up front.
async function runStagedClone(url: string, name: string, type: string) {
  const btn = document.getElementById('clBtn') as HTMLButtonElement | null;
  if (btn) btn.disabled = true;
  setMsg('clMsg', 'Cloning…');
  const target = SOURCES + '/' + name;
  const tmp = '/root/.jcode-clone-' + name;
  // The tree is assembled at a dot-named sibling and mv'd into place last (same-fs rename), so a
  // crash mid-copy never leaves a half-populated dir where the finished clone should appear.
  const stage = SOURCES + '/.stage-' + name;
  // Clone into a guest-home temp dir first, dereference proot --link2symlink pack symlinks (stat()
  // gives EPERM but open()/cat still reads them) into regular files, then copy the symlink-free tree
  // to the destination so the host can read every file (e.g. from the Explorer/DocumentsProvider).
  // /sources holds nothing worth keeping, so a stale same-named leftover is cleared, not an error.
  const deref = 'find ' + sh(tmp) + ' -type l 2>/dev/null | while IFS= read -r l; do ' +
    'if cat "$l" > "$l.deref" 2>/dev/null && [ -s "$l.deref" ]; then rm -f "$l"; mv "$l.deref" "$l"; ' +
    'else rm -f "$l.deref" "$l"; fi; done';
  const finalize = 'rm -rf ' + sh(stage) + ' && cp -r ' + sh(tmp) + ' ' + sh(stage) +
    ' && rm -rf ' + sh(tmp) + ' && mv ' + sh(stage) + ' ' + sh(target);
  // Deref FIRST, delete `.l2s.*` after: the backing files are named `.l2s.<original>` and deleting
  // them before the deref materializes the symlinks orphans (then deletes) whatever they carried —
  // that ordering destroyed every pack-transferred clone's object store.
  const cmd = 'rm -rf ' + sh(tmp) + ' ' + sh(target) + ' && ' + GITP + 'clone --progress ' + sh(url) + ' ' + sh(tmp) + ' && ' +
    '{ ' + deref + '; ' +
    'find ' + sh(tmp) + " -name '.l2s.*' -delete 2>/dev/null; find " + sh(tmp) + ' -xtype l -delete 2>/dev/null; ' +
    finalize + '; }';
  const r = await exec(cmd, { workdir: SOURCES, timeoutMs: 600000 });
  if (btn) btn.disabled = false;
  const log = document.getElementById('clLog');
  if (log) { log.style.display = 'block'; log.textContent = out(r); }
  if (r.exitCode !== 0) {
    setMsg('clMsg', 'Clone failed.', true);
    await exec('rm -rf ' + sh(tmp) + ' ' + sh(stage) + ' ' + sh(target));
    return;
  }
  let outcome = await addStagedFolder(name, '', 'clMsg');
  if (outcome === 'needsType') outcome = await addStagedFolder(name, type, 'clMsg');
  if (outcome === 'added') await closeSelf();
  // Adoption failed (the host's error is on screen): remove the clone rather than leave an
  // unusable copy behind — cloning again after resolving is the retry path.
  else await exec('rm -rf ' + sh(target));
}

// Older host without /sources: the clone lands directly in /workspace (the Default Workspace's
// projects root), so the registration targets it explicitly — a blank destinationId would register
// a phantom folder under whatever workspace happens to be open. No upfront ask: this host's
// openFolder only registers projects.
async function runLegacyClone(url: string, name: string) {
  const btn = document.getElementById('clBtn') as HTMLButtonElement | null;
  if (btn) btn.disabled = true;
  setMsg('clMsg', 'Cloning…');
  const wd = '/workspace';
  const target = wd + '/' + name;
  const tmp = '/root/.jcode-clone-' + name;
  const exists = out(await exec('test -e ' + sh(target) + ' && echo yes', { workdir: wd })).trim();
  if (exists === 'yes') { if (btn) btn.disabled = false; setMsg('clMsg', "A folder named '" + name + "' already exists.", true); return; }
  const deref = 'find ' + sh(tmp) + ' -type l 2>/dev/null | while IFS= read -r l; do ' +
    'if cat "$l" > "$l.deref" 2>/dev/null && [ -s "$l.deref" ]; then rm -f "$l"; mv "$l.deref" "$l"; ' +
    'else rm -f "$l.deref" "$l"; fi; done';
  const cmd = 'rm -rf ' + sh(tmp) + ' && ' + GITP + 'clone --progress ' + sh(url) + ' ' + sh(tmp) + ' && ' +
    '{ ' + deref + '; ' +
    'find ' + sh(tmp) + " -name '.l2s.*' -delete 2>/dev/null; find " + sh(tmp) + ' -xtype l -delete 2>/dev/null; ' +
    'cp -r ' + sh(tmp) + ' ' + sh(target) + ' && rm -rf ' + sh(tmp) + '; }';
  const r = await exec(cmd, { workdir: wd, timeoutMs: 600000 });
  if (btn) btn.disabled = false;
  const log = document.getElementById('clLog');
  if (log) { log.style.display = 'block'; log.textContent = out(r); }
  if (r.exitCode !== 0) {
    setMsg('clMsg', 'Clone failed.', true);
    await exec('rm -rf ' + sh(tmp) + ' ' + sh(target), { workdir: wd });
    return;
  }
  setMsg('clMsg', 'Cloned — opening…');
  await api('workbench.openFolder', { name, destinationId: 'default' });
  await closeSelf();
}

let rrRepos: any[] = [];
let rrUser = '';
let rrOwner = '';

async function renderRemotePage() {
  document.body.className = 'authpage';
  document.body.innerHTML =
    '<div class="page">' +
    '<div class="page-hd">' + OCTOCAT + '<div><h1>Remote repositories</h1><div class="sub">Clone one of your GitHub repositories</div></div></div>' +
    '<div id="rrBody"><div class="muted">Loading…</div></div></div>';
  await loadRemote();
}

async function loadRemote() {
  const body = $('rrBody');
  body.innerHTML = '<div class="muted">Loading…</div>';
  rrUser = out(await exec('git config --global --get github.user 2>/dev/null')).trim();
  const credLine = out(await exec('grep -m1 github.com ~/.git-credentials 2>/dev/null')).trim();
  let token = '';
  if (credLine.indexOf('https://') === 0) { token = credLine.slice(8).split('@')[0].split(':')[1] || ''; }
  if (!rrUser || !token) {
    body.innerHTML = '<div class="card"><h2>Sign in to GitHub</h2>' +
      '<div class="muted">Sign in to browse and clone your repositories.</div>' +
      '<div class="brow"><button id="rrSignin" class="btn primary">Sign in to GitHub</button></div></div>';
    $('rrSignin').onclick = () => void api('workbench.openView', { view: 'github' });
    return;
  }
  const cmd = 'curl -fsS -H "Authorization: token $GH_TOKEN" -H "User-Agent: JCode" -H "Accept: application/vnd.github+json" "https://api.github.com/user/repos?per_page=100&sort=updated"';
  const r = await exec(cmd, { env: { GH_TOKEN: token }, timeoutMs: 30000 });
  if (r.exitCode !== 0) {
    const hasCurl = out(await exec('command -v curl >/dev/null 2>&1 && echo yes')).trim() === 'yes';
    body.innerHTML = '<div class="card"><div class="msg err">' +
      (hasCurl ? 'Failed to load repositories.' : 'curl isn’t installed in this environment — install it from Tools → Toolchains.') +
      '</div><pre class="modal-log">' + escapeHtml(out(r)) + '</pre>' +
      '<div class="brow"><button id="rrRetry" class="btn">Retry</button></div></div>';
    $('rrRetry').onclick = () => void loadRemote();
    return;
  }
  try { rrRepos = JSON.parse(r.stdout); } catch { body.innerHTML = '<div class="card"><div class="msg err">Could not parse the GitHub response.</div></div>'; return; }
  if (!Array.isArray(rrRepos) || !rrRepos.length) { body.innerHTML = '<div class="card"><div class="muted">No repositories found for @' + escapeHtml(rrUser) + '.</div></div>'; return; }
  const owners = Array.from(new Set(rrRepos.map((x) => x.owner && x.owner.login).filter(Boolean)))
    .sort((a, b) => (a === rrUser ? -1 : b === rrUser ? 1 : String(a).localeCompare(String(b))));
  if (owners.indexOf(rrOwner) < 0) rrOwner = owners[0] as string;
  renderRemoteList(owners as string[]);
}

function renderRemoteList(owners: string[]) {
  const body = $('rrBody');
  const tabs = owners.map((o) => '<button class="rr-tab' + (o === rrOwner ? ' on' : '') + '" data-o="' + escapeAttr(o) + '">' +
    escapeHtml(o === rrUser ? 'You' : o) + '</button>').join('');
  const rows = rrRepos.filter((x) => x.owner && x.owner.login === rrOwner)
    .sort((a, b) => String(a.name).toLowerCase().localeCompare(String(b.name).toLowerCase()))
    .map((x) => '<div class="rr-repo" data-url="' + escapeAttr(x.clone_url || '') + '" data-name="' + escapeAttr(x.name || '') + '">' +
      '<div class="rr-nm">' + escapeHtml(x.name || '') + (x.private ? '<span class="rr-priv">private</span>' : '') + '</div>' +
      (x.description ? '<div class="muted rr-desc">' + escapeHtml(x.description) + '</div>' : '') + '</div>').join('');
  body.innerHTML = '<div class="rr-tabs">' + tabs + '</div><div class="rr-list">' + (rows || '<div class="muted">No repositories.</div>') + '</div>';
  body.querySelectorAll<HTMLElement>('.rr-tab').forEach((t) => { t.onclick = () => { rrOwner = t.dataset.o as string; renderRemoteList(owners); }; });
  body.querySelectorAll<HTMLElement>('.rr-repo').forEach((el) => { el.onclick = () => void cloneRemoteRepo(el.dataset.url as string, el.dataset.name as string); });
}

// Tapping a repo in the Remote-repo browser opens the clone form pre-filled for review (editable
// name + destination) instead of cloning immediately — the user confirms with the Clone button.
async function cloneRemoteRepo(url: string, name: string) {
  await renderClonePage({ url, name, fromRemote: true });
}

// The extension renders two surfaces from one bundle: the SCM sidebar (drawer) and, when opened via
// workbench.openView with #github, a full-page GitHub sign-in + git-identity screen in the editor.
const VIEW = location.hash.replace(/^#/, '');
if (VIEW === 'github') {
  void renderAuthPage();
} else if (VIEW === 'manage') {
  void renderManagePage();
} else if (VIEW === 'clone') {
  void renderClonePage();
} else if (VIEW === 'remoteRepo') {
  void renderRemotePage();
} else if (VIEW.indexOf('merge:') === 0) {
  void renderMergePage();
} else if (VIEW.indexOf('diff:') === 0) {
  void renderDiffPage();
} else {
  document.querySelectorAll<HTMLElement>('.sec-hd').forEach((h) => {
    h.addEventListener('click', (e) => { if ((e.target as HTMLElement).closest('.act')) return; $(h.dataset.sec!).classList.toggle('collapsed'); });
  });
  $('refresh').dataset.keep = '1'; $('branchBtn').dataset.keep = '1'; $('ghBtn').dataset.keep = '1'; $('viewToggle').dataset.keep = '1';
  $('refresh').onclick = () => { if (!busy) bootP = boot(); };
  $('branchBtn').onclick = toggleBranchMenu;
  $('scrim').onclick = closePops;
  $('ghBtn').onclick = () => void api('workbench.openView', { view: 'github' });
  $('viewToggle').onclick = toggleView;
  $('commit').onclick = commit;
  $('commitMore').onclick = toggleCommitMenu;
  $('genMsg').onclick = generateCommitMessage;
  $('stageAll').onclick = stageAll;
  $('unstageAll').onclick = unstageAll;
  $('discardAll').onclick = discardAll;
  $('saveId').onclick = saveIdentity;
  $('fetch').onclick = fetch_;
  $('pull').onclick = pull;
  $('push').onclick = push;
  $<HTMLTextAreaElement>('msg').addEventListener('input', function (this: HTMLTextAreaElement) { this.style.height = 'auto'; this.style.height = Math.min(this.scrollHeight, 120) + 'px'; refreshCommitState(); });
  updateViewToggle();
  bootP = boot();
}
