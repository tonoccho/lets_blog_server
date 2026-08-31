# Getting Started Guide

Welcome to **Let's Blog Server**! This guide will walk you through the setup process step-by-step with detailed instructions.

## Table of Contents

- [What is Let's Blog Server?](#what-is-lets-blog-server)
- [System Requirements](#system-requirements)
- [Installation Steps](#installation-steps)
- [First Login and Initial Setup](#first-login-and-initial-setup)
- [Installing the VSCode Extension](#installing-the-vscode-extension)
- [Next Steps](#next-steps)

## What is Let's Blog Server?

Let's Blog Server is a self-hosted blogging platform that allows you to:

- **Write in VSCode**: Author articles in Markdown using the comfortable VSCode editor
- **Multi-site Publishing**: Publish to multiple WordPress sites simultaneously
- **AI-Powered Features**: 
  - AI-assisted writing (draft generation, proofreading)
  - Automatic featured image generation
  - Smart tag suggestions
- **Visual Management**: Web-based admin panel for site and post management
- **Diagram Support**: Render diagrams (PlantUML) directly in articles

The system is built on Docker and includes all necessary services (API server, database, AI models, image generation engine).

## System Requirements

### Hardware

| Component | Requirement | Notes |
|-----------|-------------|-------|
| **GPU** | NVIDIA GPU with 16GB+ VRAM | Required for AI features (Ollama, ComfyUI) |
| **CPU** | Dual-core or better | Recommended 4+ cores |
| **RAM** | 8GB minimum, 16GB+ recommended | Multiple services run concurrently |
| **Storage** | 50GB+ available space | For Docker images and AI model files |
| **Network** | Ports 80 & 443 open | Reverse proxy (nginx) uses these ports |

### Software

- **Git** - For cloning the repository
- **Docker Engine + Docker Compose v2** - Container orchestration
- **NVIDIA Container Toolkit** - GPU support in Docker (if using NVIDIA GPU)
- **openssl** - For generating TLS certificates (included on Linux/macOS)
- **Visual Studio Code** - For using the VSCode extension

## Installation Steps

### Step 1: Clone the Repository

```bash
git clone https://github.com/tonoccho/lets_blog_server.git
cd lets_blog_server
```

### Step 2: Configure Environment Variables

**Screenshot placeholder: Environment configuration file with editable fields**

Copy the example configuration and edit it:

```bash
cp .env.example .env
bash scripts/check-env.sh   # .env が .env.example の全項目を満たしているか確認
```

Edit `.env` with your favorite text editor and change these critical values:

```env
# Database passwords (change these!)
MYSQL_ROOT_PASSWORD=your_secure_password_here
MYSQL_PASSWORD=your_secure_password_here

# API authentication key (used by VSCode extension and Web UI)
SERVER_API_KEY=your_unique_api_key_here

# Encryption key for WordPress credentials (generate with: openssl rand -base64 32)
APP_ENCRYPTION_KEY=base64_encoded_32_byte_key_here

# Web and email settings
APP_WEB_BASE_URL=https://localhost
APP_MAIL_FROM=noreply@yourdomain.local

# External email service (SendGrid/Resend/AWS SES/etc.) SMTP connection settings
MAIL_HOST=smtp.sendgrid.net
MAIL_PORT=587
MAIL_USERNAME=apikey
MAIL_PASSWORD=your_smtp_password_here

# Authentication secret (generate with: openssl rand -hex 32)
NEXTAUTH_SECRET=your_nextauth_secret_here

# GPU/AI Model configuration (optional customization)
COMFYUI_IMAGE=nvidia/cuda:12.1.0-runtime-ubuntu22.04
OLLAMA_MODEL=qwen2.5:7b-instruct
COMFYUI_CHECKPOINT=sd-v1-5-fp16.safetensors
```

#### Generating Secure Keys

```bash
# Generate encryption key (32-byte Base64)
openssl rand -base64 32

# Generate NextAuth secret (hex)
openssl rand -hex 32
```

### Step 3: Generate TLS Certificate

The system uses HTTPS with a self-signed certificate for local development.

```bash
bash scripts/generate-certs.sh
```

**Screenshot placeholder: Certificate generation confirmation**

This creates:
- `certs/localhost.crt` - Public certificate
- `certs/localhost.key` - Private key (never commit this!)

### Step 4: Start Docker Services

```bash
docker compose up -d
```

This starts all ~19 containers (reverse proxy, web, API, log-writer, MySQL, RabbitMQ,
ComfyUI, PlantUML, drawio, WordPress, phpMyAdmin, and the Penpot design-tooling suite).
Wait for all services to start — first-time startup (pulling/building images) can take
several minutes, and subsequent restarts typically take 30 seconds to 2 minutes depending
on hardware, since services that declare a healthcheck (`api`, `log-writer`, `mysql`,
`rabbitmq`, `penpot-postgres`, `penpot-valkey`) wait for their dependencies to report
healthy before starting (see [docs/DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md)
for the full port/healthcheck layout):

```bash
docker compose ps
```

**Screenshot placeholder: Docker services running status**

Expected output should show these containers as "Up" (the ones with a healthcheck should
say "healthy", not just "Up"):
- `lbs-reverse-proxy` (nginx)
- `lbs-web` (Next.js admin panel)
- `lbs-api` (REST API server)
- `lbs-log-writer` (async error/operation/audit log writer)
- `lbs-mysql` (Database)
- `lbs-rabbitmq` (log message queue)
- `lbs-comfyui` (Image generation)
- `lbs-plantuml` (Diagram rendering)
- `lbs-drawio`, `lbs-wordpress`, `lbs-phpmyadmin`, and the `lbs-penpot-*` containers

If a container never reaches "healthy", see
[Multi-service startup failures](COMPREHENSIVE_TROUBLESHOOTING.md#multi-service-startup-failures)
in the troubleshooting guide.

#### Restarting or rebuilding a single service

You don't need to restart the whole stack after changing one service:

```bash
# Restart a running container (e.g. after an env var change)
docker compose restart api

# Rebuild the image and recreate the container (e.g. after a code change)
docker compose build api
docker compose up -d api
```

#### Reading logs

```bash
# Follow one service's logs
docker compose logs -f api

# Follow several at once
docker compose logs -f api log-writer

# Last 100 lines, no follow
docker compose logs --tail 100 api
```

### Step 5: Access the Web Admin Panel

Open your browser and navigate to:

```
https://localhost
```

**Screenshot placeholder: Browser security warning**

You'll see a security warning because the certificate is self-signed. This is normal and expected.

**Steps:**
1. Click "Advanced" or "More info"
2. Click "Proceed to localhost (unsafe)" or similar
3. You'll be redirected to `/setup`

## First Login and Initial Setup

### Create Admin Account

**Screenshot placeholder: Setup page with form fields**

On the `/setup` page:

1. **Email**: Enter your email address
2. **Password**: Create a strong password (minimum 8 characters)
3. **Confirm Password**: Repeat the password
4. Click **Create Admin Account**

### Add Your First WordPress Site

**Screenshot placeholder: WordPress site configuration form**

After logging in:

1. Navigate to **Settings** → **Sites** (or Admin > Websites)
2. Click **Add New Site**
3. Fill in the following:

   | Field | Example |
   |-------|---------|
   | **Site Name** | My Personal Blog |
   | **WordPress URL** | https://myblog.example.com |
   | **Username** | admin |
   | **App Password** | Generate from WordPress |
   | **Description** | (Optional) Brief site description |

4. Click **Test Connection** to verify credentials
5. Click **Save Site**

#### Getting WordPress App Password

1. Log in to your WordPress admin panel
2. Navigate to **Users** → **Your Profile**
3. Scroll to **Application Passwords**
4. Enter an app name (e.g., "Let's Blog Server")
5. Click **Create Application Password**
6. Copy the generated password

## Installing the VSCode Extension

### Download the Extension

**Screenshot placeholder: System page with download button**

1. While logged in to the Web Admin Panel, navigate to **System Settings**
2. Look for **Download Extension** button
3. Save the `.vsix` file (e.g., `letsblog-vscode-1.0.0.vsix`)

### Install in VSCode

**Option A: Via VSCode UI (Recommended)**

1. Open VSCode
2. Press `Ctrl+Shift+P` (or `Cmd+Shift+P` on macOS)
3. Type: `Extensions: Install from VSIX...`
4. Select the downloaded `.vsix` file

**Option B: Via Command Line**

```bash
code --install-extension letsblog-vscode-1.0.0.vsix
```

### Configure the Extension

1. Set `letsBlog.serverUrl` in your VSCode settings if it differs from the default
   (`https://localhost`).
2. Open VSCode command palette: `Ctrl+Shift+P`
3. Search for: `Let's Blog: Login`
4. Your default browser opens automatically to Keycloak's device verification page, and a
   progress notification shows a short code (e.g. `ABCD-1234`). Enter that code (it's usually
   pre-filled from the URL) and approve the login with your account.

**Screenshot placeholder: VSCode extension login progress notification**

The extension stores the access/refresh tokens it receives in VSCode's Secret Storage and
refreshes them automatically for future sessions — you won't be prompted for a password. This
is a Device Authorization Grant flow (RFC 8628): the extension never sees your password.

### Handling SSL Certificate Warning

Since the server uses a self-signed certificate, VSCode will show SSL warnings.

**Solution:**

Set the `NODE_EXTRA_CA_CERTS` environment variable before launching VSCode:

```bash
# Linux/macOS
export NODE_EXTRA_CA_CERTS=~/lets_blog_server/certs/localhost.crt
code .

# Or add to ~/.bashrc / ~/.zshrc for permanent setup
echo 'export NODE_EXTRA_CA_CERTS=~/lets_blog_server/certs/localhost.crt' >> ~/.zshrc
```

## Next Steps

- **[Create Your First Post](ARTICLE_AUTHORING_BEST_PRACTICES.md)** - Learn best practices for writing articles
- **[Video Tutorials](VIDEO_TUTORIALS_GUIDE.md)** - Watch step-by-step video guides
- **[Features Guide](FEATURES_AND_USAGE.md)** - Explore all available features
- **[Troubleshooting](COMPREHENSIVE_TROUBLESHOOTING.md)** - Solutions to common issues
- **[API Documentation](../README.md#api-documentation)** - For advanced users and developers

## Performance Tips

- **First-time setup**: Ollama and ComfyUI models take time to download (~5-15 minutes)
- **Monitor resources**: Check `docker compose logs -f ollama` if AI features feel slow
- **GPU optimization**: Ensure NVIDIA drivers are updated for best performance

## Getting Help

If you encounter issues:

1. Check **[Troubleshooting Guide](COMPREHENSIVE_TROUBLESHOOTING.md)**
2. Review service logs: `docker compose logs <service-name>`
3. Check project GitHub Issues: https://github.com/tonoccho/lets_blog_server/issues

Welcome to Let's Blog! 🎉
