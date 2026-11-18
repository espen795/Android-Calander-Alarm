# Publishing to GitHub (maintainer notes)

## One-time setup

1. Create the repository on GitHub **empty** (no README/.gitignore/license from the
   web form — the license is `LICENSE.md` in this repo, PolyForm Noncommercial 1.0.0,
   which GitHub's dropdown does not offer; choose "None").
2. Push access uses a **repository-scoped deploy key**, never a personal token:
   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/id_github_<repo>_deploy -N "" -C "<repo>-deploy-key"
   ```
   Paste only the `.pub` half at `https://github.com/<user>/<repo>/settings/keys`
   with **Allow write access** checked.
3. Add a host alias to `~/.ssh/config` (does not affect other GitHub use):
   ```
   Host github-<repo>
       HostName github.com
       User git
       IdentityFile ~/.ssh/id_github_<repo>_deploy
       IdentitiesOnly yes
   ```
   Verify: `ssh -T git@github-<repo>` → `Hi <user>/<repo>! You've successfully authenticated`.
4. `git remote add origin git@github-<repo>:<user>/<repo>.git && git push -u origin main`
   (use `-f` only for the very first push if GitHub created an initial README commit).

Never share API keys or tokens with an assistant or in an issue. If one leaks: revoke it
at `github.com/settings/tokens`, or delete the deploy key under the repo's
*Settings → Deploy keys*.

## Repository settings after the first push

- *Settings → Code security*: enable **Secret scanning** + **Push protection**, Dependabot alerts.
- *Settings → Branches*: protect `main` (require PR + passing CI).
- *Settings → Secrets and variables → Actions*: add the signing secrets listed in
  [RELEASE.md](RELEASE.md).
- Add topics, e.g. `android`, `alarm-clock`, `kotlin`, `jetpack-compose`, `outlook-calendar`.

## Every push

```bash
tools/sanitize-check.sh            # must print CLEAN
tools/sanitize-check.sh --history  # before the first push / after history rewrites
```

The private denylist (names, domains, hostnames) lives outside the repo at
`~/.config/calendar-alarm/denylist.txt`, so it is never published itself.

## Releases

A deploy key can push code and tags only. Tag pushes (`vX.Y.Z`) trigger
`.github/workflows/release.yml`, which uses the built-in `GITHUB_TOKEN` to create the
GitHub Release, so no extra credentials are needed. See [RELEASE.md](RELEASE.md).
