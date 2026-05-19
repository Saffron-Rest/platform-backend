# Push to GitHub — do this once (5 minutes)

Your code is ready locally with CI/CD. GitHub repo:  
**https://github.com/Saffron-Rest/platform-backend**

## Step 1 — Push code

Open **Terminal**:

```bash
cd ~/Desktop/saffron-app/platform-backend
git push -u origin main
```

### If it asks for login or fails

**Option A — SSH (recommended)**

1. Show your public key:
   ```bash
   cat ~/.ssh/id_ed25519.pub
   ```
2. GitHub → **Saffron-Rest** org (or your user) → **Settings → SSH and GPG keys → New SSH key**
3. Paste key → **Add**
4. You must have **write access** to `Saffron-Rest/platform-backend`
5. Run again:
   ```bash
   git remote set-url origin git@github.com:Saffron-Rest/platform-backend.git
   git push -u origin main
   ```

**Option B — HTTPS + token**

1. GitHub → **Settings → Developer settings → Personal access tokens → Generate (classic)**
2. Scope: **repo**
3. ```bash
   git remote set-url origin https://github.com/Saffron-Rest/platform-backend.git
   git push -u origin main
   ```
   Username: your GitHub username  
   Password: paste the **token** (not your GitHub password)

After push, refresh https://github.com/Saffron-Rest/platform-backend — you should see Java files and `.github/workflows`.

---

## Step 2 — GitHub Actions secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value |
|--------|--------|
| `VPS_HOST` | `76.13.130.67` |
| `VPS_USER` | `root` |
| `SSH_PRIVATE_KEY` or `VPS_SSH_KEY` | Full private key (same as frontend repo) |
| `SSH_PASSPHRASE` | Only if your key has a passphrase |
| `GHCR_USERNAME` | Your GitHub username |
| `GHCR_TOKEN` | PAT with `read:packages` |
| `POSTGRES_USER` | `saffron` |
| `POSTGRES_DB` | `cashflow` |
| `POSTGRES_PASSWORD` | Must match VPS Postgres (see `deploy/vps-credentials.env` in monorepo) |
| `JWT_SECRET` | Same as VPS / local `.env` |

---

## Step 3 — Run deploy

1. **Actions** tab → workflow **pipeline** → **Run workflow** → branch `main`
2. Or push any commit to `main` (auto-runs)

Jobs: **1 · Test** → **2 · Build & push image** → **3 · Deploy API to VPS**

---

## Step 4 — Check it works

```bash
curl http://cash-flow.saffron.waw.pl/api/health
```

Expected: `{"ok":true}` (Kong routes `/api` to the backend)

(Postgres must already run on VPS as `saffron-postgres` on network `saffron_net`.)

---

## What the repo contains

| Path | Purpose |
|------|---------|
| `src/` | Spring Boot API |
| `Dockerfile` | Production image |
| `.github/workflows/pipeline.yml` | Test, build (linux/amd64), deploy to VPS |
| `deploy/` | VPS compose + script |

Image URL after deploy:  
`ghcr.io/saffron-rest/platform-backend:latest`
