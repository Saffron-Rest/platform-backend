# Deploy (GitHub Actions)

Repo: [Saffron-Rest/platform-backend](https://github.com/Saffron-Rest/platform-backend)

## GitHub secrets

**Settings → Secrets and variables → Actions → New repository secret**

| Secret | Example |
|--------|---------|
| `VPS_HOST` | `76.13.130.67` |
| `VPS_USER` | `root` |
| `VPS_SSH_KEY` | Private SSH key (full PEM) |
| `VPS_APP_DIR` | `/docker/saffron-backend` (optional) |
| `POSTGRES_PASSWORD` | Same as Postgres on VPS |
| `JWT_SECRET` | Min 32 characters |
| `GHCR_TOKEN` | GitHub PAT with `read:packages` (for VPS to pull image) |
| `TELEGRAM_ENABLED` | `true` (optional — admin alerts) |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather) |
| `TELEGRAM_CHAT_ID` | Your user or group chat id |

`GITHUB_TOKEN` is used automatically to **push** images. The VPS needs `GHCR_TOKEN` to **pull** private images.

Make package public: **Packages → platform-backend → Package settings → Change visibility → Public** (then `GHCR_TOKEN` is optional).

## Requires on VPS

- Docker + Docker Compose
- Network `saffron_net` and container `saffron-postgres` (from main Saffron stack)
- Port **3001** reachable from frontend nginx (`saffron-backend` hostname on Docker network)

## Manual deploy on VPS

```bash
export BACKEND_IMAGE=ghcr.io/saffron-rest/platform-backend:latest
export POSTGRES_PASSWORD=...
export JWT_SECRET=...
bash deploy/deploy.sh
```
