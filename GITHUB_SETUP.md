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
| `VPS_SSH_KEY` | Full private key file (`~/.ssh/id_ed25519`) |
| `POSTGRES_PASSWORD` | From `~/Desktop/saffron-app/deploy/.env` on your Mac |
| `JWT_SECRET` | From same `.env` file |
| `GHCR_TOKEN` | GitHub PAT with `read:packages` (only if image is private) |

**Easier:** after first build, go to **Packages** → open `platform-backend` → **Package settings → Change visibility → Public** (then skip `GHCR_TOKEN`).

---

## Step 3 — Run deploy

1. **Actions** tab → workflow **Deploy** → **Run workflow** → branch `main`
2. Or push any commit to `main` (auto-runs)

Jobs: **build-and-push** → **deploy**

---

## Step 4 — Check it works

```bash
curl http://76.13.130.67/api/health
```

Expected: `{"ok":true}`

(Postgres must already run on VPS as `saffron-postgres` on network `saffron_net`.)

---

## What the repo contains

| Path | Purpose |
|------|---------|
| `src/` | Spring Boot API |
| `Dockerfile` | Production image |
| `.github/workflows/ci.yml` | Test on every push |
| `.github/workflows/deploy.yml` | Build + deploy to VPS |
| `deploy/` | VPS compose + script |

Image URL after deploy:  
`ghcr.io/saffron-rest/platform-backend:latest`
