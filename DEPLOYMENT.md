# Production Deployment Guide (DigitalOcean Droplet)

This guide walks through setting up the Moneat app on an Ubuntu droplet with blue/green deployments, nginx, SSL, and protected database volumes.

---

## A. Initial Droplet Setup

1. **Create a Droplet** on DigitalOcean: Ubuntu 24.04 LTS, recommended 4GB RAM / 2 vCPU minimum. Add your SSH key to the droplet.

2. **SSH in as root** (replace with your droplet IP):

   ```bash
   ssh root@YOUR_DROPLET_IP
   ```

3. **Update packages**:

   ```bash
   apt update && apt upgrade -y
   ```

4. **Create the `deploy` user** with sudo access (no password login; key-only later):

   ```bash
   adduser --disabled-password --gecos "" deploy
   usermod -aG sudo deploy
   mkdir -p /home/deploy/.ssh
   chmod 700 /home/deploy/.ssh
   ```

5. **Configure UFW firewall** (allow SSH, HTTP, HTTPS only):

   ```bash
   ufw allow 22/tcp
   ufw allow 80/tcp
   ufw allow 443/tcp
   ufw enable
   ufw status
   ```

6. **Harden SSH** (disable root login and password auth after you have key-based access for `deploy`):

   Edit `/etc/ssh/sshd_config` and set (or add):

   ```text
   PermitRootLogin no
   PasswordAuthentication no
   ```

   Then restart SSH:

   ```bash
   systemctl restart sshd
   ```

   **Important:** Ensure the `deploy` user has your SSH public key in `/home/deploy/.ssh/authorized_keys` and you can `ssh deploy@YOUR_DROPLET_IP` before disabling root login.

7. **Install Docker Engine and Compose plugin**:

   ```bash
   apt install -y ca-certificates curl
   install -m 0755 -d /etc/apt/keyrings
   curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
   chmod a+r /etc/apt/keyrings/docker.asc
   echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${VERSION_CODENAME:-$VERSION_ID}") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
   apt update
   apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
   systemctl enable docker
   systemctl start docker
   ```

8. **Add `deploy` to the `docker` group** so it can run Docker without sudo:

   ```bash
   usermod -aG docker deploy
   ```

   Log out and back in as `deploy` (or reboot) for the group to take effect.

---

## B. Deploy User and GitHub SSH Setup

1. **On your local machine**, generate an SSH key pair **only for GitHub Actions** (do not use your main key):

   ```bash
   ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/moneat_deploy -N ""
   ```

2. **Add the public key to the droplet** (as a user with access to `deploy`’s home):

   On the droplet (as root or deploy):

   ```bash
   echo "PASTE_CONTENTS_OF_moneat_deploy.pub_HERE" >> /home/deploy/.ssh/authorized_keys
   chown deploy:deploy /home/deploy/.ssh/authorized_keys
   chmod 600 /home/deploy/.ssh/authorized_keys
   ```

   Or from your machine (if you already have SSH access as deploy):

   ```bash
   ssh-copy-id -i ~/.ssh/moneat_deploy.pub deploy@YOUR_DROPLET_IP
   ```

3. **Add GitHub Actions secrets** in your repo: **Settings → Secrets and variables → Actions**:

   - `DROPLET_SSH_KEY`: contents of `~/.ssh/moneat_deploy` (private key).
   - `DROPLET_HOST`: droplet IP or hostname (e.g. `165.232.123.45`).

4. **Optional:** Create a GitHub environment named `production` (Settings → Environments) if you use deployment protection or approval.

5. **Verify:** From your machine, `ssh -i ~/.ssh/moneat_deploy deploy@YOUR_DROPLET_IP` should log you in without a password.

---

## C. Clone Repository and Setup Environment

1. **Set up SSH key for GitHub access** (if repo is private):

   On the droplet as `deploy`:

   ```bash
   # Generate SSH key for GitHub
   ssh-keygen -t ed25519 -C "deploy@moneat-production" -f ~/.ssh/github_deploy -N ""
   
   # Display the public key
   cat ~/.ssh/github_deploy.pub
   ```

   Copy the public key output, then add it to your GitHub repo:
   - Go to `https://github.com/moneat-io/moneat/settings/keys`
   - Click "Add deploy key"
   - Paste the public key
   - Give it a title like "Production Droplet"
   - **Do not** check "Allow write access" (read-only is safer)

   **Configure SSH to use this key for GitHub:**

   Create or edit the SSH config file:

   ```bash
   nano ~/.ssh/config
   ```

   Add these lines to the file (or append if the file already exists):

   ```
   Host github.com
       IdentityFile ~/.ssh/github_deploy
       StrictHostKeyChecking no
   ```

   Save and exit (Ctrl+O, Enter, Ctrl+X in nano).

   Then set the correct permissions:

   ```bash
   chmod 600 ~/.ssh/config
   ```

2. **Clone the repo** on the droplet:

   As `root` (or a user with sudo access), create the directory:

   ```bash
   mkdir -p /opt/moneat
   chown deploy:deploy /opt/moneat
   ```

   Then switch to the `deploy` user and clone:

   ```bash
   su - deploy
   git clone git@github.com:moneat-io/moneat.git /opt/moneat
   cd /opt/moneat
   ```

   If the repo is public, you can use HTTPS instead: `https://github.com/moneat-io/moneat.git`

3. **Create `.env`** in `/opt/moneat` with production values (do not commit this file):

   ```bash
   cp .env.example .env
   nano .env
   ```

   **Update these production values:**

   ```bash
   # URLs - Use your actual domain
   FRONTEND_URL=https://moneat.io
   BACKEND_URL=https://moneat.io
   
   # Database - Use Docker service names (from docker-compose.prod.yml)
   DATABASE_URL=jdbc:postgresql://postgres:5432/moneat
   DATABASE_PASSWORD=STRONG_RANDOM_PASSWORD_HERE
   
   CLICKHOUSE_URL=http://clickhouse:8123
   CLICKHOUSE_PASSWORD=STRONG_RANDOM_PASSWORD_HERE
   
   REDIS_URL=redis://redis:6379
   
   # Security
   JWT_SECRET=STRONG_RANDOM_SECRET_HERE
   
   # Email
   EMAIL_FROM=noreply@moneat.io
   SMTP_HOST=smtp.your-provider.com
   SMTP_PORT=587
   SMTP_USERNAME=your-email@example.com
   SMTP_PASSWORD=your-smtp-password
   ```

   **Generate strong passwords:**
   ```bash
   openssl rand -base64 32  # Run this 3 times for DATABASE_PASSWORD, CLICKHOUSE_PASSWORD, JWT_SECRET
   ```

4. **Make the deploy script executable**:

   ```bash
   chmod +x /opt/moneat/deploy/scripts/deploy.sh
   ```

---

## D. Create Protected External Volumes

**These volumes must exist before the first deploy and must never be deleted.** Compose declares them as `external: true`, so it will not create or remove them.

1. **Create the volumes** (run as a user that can use Docker, e.g. `deploy` after logging in):

   ```bash
   docker volume create moneat_postgres_data
   docker volume create moneat_clickhouse_data
   docker volume create moneat_redis_data
   docker volume create certbot_conf
   docker volume create certbot_www
   ```

2. **Verify** they exist:

   ```bash
   docker volume inspect moneat_postgres_data moneat_clickhouse_data moneat_redis_data certbot_conf certbot_www
   ```

**Warning:** Never run `docker system prune --volumes` or `docker volume prune` on this server. The deploy script only prunes dangling images (no volumes). See “Volume protection” below.

---

## E. Initial SSL Certificate Setup

1. **Ensure DNS is configured**: Your domain `moneat.io` DNS A record must point to the droplet IP before proceeding.

2. **Run the SSL setup script**:

   ```bash
   cd /opt/moneat
   ./deploy/scripts/setup-ssl.sh moneat.io
   ```

   This script will:
   - Request SSL certificate from Let's Encrypt
   - Create HTTPS nginx configuration
   - Enable HTTPS redirect
   - Reload nginx

   You should see "✅ SSL setup complete!" when done.

3. **Set up automatic renewal** (run as `deploy`):

   ```bash
   crontab -e
   ```

   Add this line:

   ```text
   0 0 */60 * * cd /opt/moneat && docker compose -f docker-compose.prod.yml run --rm certbot renew && docker exec moneat-nginx nginx -s reload
   ```

   This runs renewal every 60 days and reloads nginx.

---

## F. Database Initialization

1. **Start only infrastructure** (no app slots yet):

   ```bash
   cd /opt/moneat
   docker compose -f docker-compose.prod.yml up -d postgres clickhouse redis
   ```

   Ensure `.env.prod` exists and sets `DATABASE_PASSWORD` and `CLICKHOUSE_PASSWORD` (see below). Init scripts are mounted from the repo (`backend/src/main/resources/db/init.sql` and `clickhouse_init.sql`) and run automatically on first start when the data directories are empty.

2. **Verify** (optional):

   - Postgres:  
     `docker exec moneat-postgres pg_isready -U moneat`
   - ClickHouse:  
     `docker exec moneat-clickhouse wget -qO- http://localhost:8123/ping`

---

## G. First Deployment

1. **Run the first deploy** (after the first successful GitHub Actions build so images exist for the commit you want):

   Either trigger a push to `main` and let the workflow deploy, or on the server (with `IMAGE_TAG` set to the image tag you built, e.g. a git SHA):

   ```bash
   cd /opt/moneat
   export IMAGE_TAG=latest
   export BACKEND_IMAGE=ghcr.io/moneat-io/moneat-backend
   export DASHBOARD_IMAGE=ghcr.io/moneat-io/moneat-dashboard
   ./deploy/scripts/deploy.sh latest
   ```

   For CI, the workflow sets `IMAGE_TAG` to the git SHA and uses the same image names.

5. **Ensure infrastructure and one slot are up** (if you didn’t use the script’s full flow):

   ```bash
   docker compose -f docker-compose.prod.yml up -d nginx certbot postgres clickhouse redis backend-blue dashboard-blue
   ```

   The deploy script normally does this and then switches slots on subsequent runs.

---

## Volume Protection (Critical)

- **Never** run `docker system prune --volumes` or `docker volume prune` on the production server. Data for Postgres and ClickHouse lives in external volumes; pruning can delete them.
- The deploy script only runs `docker image prune -f --filter "until=24h"` (images only). It then checks that `moneat_postgres_data` and `moneat_clickhouse_data` exist and fails the deploy if they are missing.
- Database containers (postgres, clickhouse, redis) are never stopped or recreated as part of the blue/green app deploy.

---

## Backups (Recommended)

Add a cron job to back up Postgres and (if desired) ClickHouse.

**Postgres** (daily dump):

```bash
0 2 * * * docker exec moneat-postgres pg_dump -U moneat moneat | gzip > /opt/backups/moneat_pg_$(date +\%Y\%m\%d).sql.gz
```

Ensure `/opt/backups` exists and is writable, and consider copying dumps off the droplet (e.g. to S3 or another storage).

**ClickHouse** (optional): Use `clickhouse-backup` or export important tables; document the exact commands in your runbook.

---

## Summary Checklist

- [ ] Droplet created (Ubuntu 24.04), packages updated
- [ ] `deploy` user created, in `sudo` and `docker` groups
- [ ] UFW enabled (22, 80, 443); SSH hardened (key-only, no root)
- [ ] Docker and Compose plugin installed
- [ ] SSH key for GitHub Actions added to `deploy@droplet`, secrets `DROPLET_SSH_KEY` and `DROPLET_HOST` set in GitHub
- [ ] Five external volumes created and verified
- [ ] Domain DNS points to droplet; nginx app.conf updated; certbot certificate obtained; renewal cron added
- [ ] Repo cloned to `/opt/moneat`; `.env.prod` created; `deploy.sh` executable
- [ ] Database initialization complete (postgres, clickhouse, redis running)
- [ ] First deploy run (script or via push to `main`)
