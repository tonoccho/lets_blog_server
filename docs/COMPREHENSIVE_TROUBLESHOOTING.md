# Comprehensive Troubleshooting Guide

This guide provides detailed solutions for common issues with Let's Blog Server.

## Table of Contents

- [Initial Setup Issues](#initial-setup-issues)
- [Docker and Service Issues](#docker-and-service-issues)
- [Web Admin Panel Issues](#web-admin-panel-issues)
- [VSCode Extension Issues](#vscode-extension-issues)
- [WordPress Publishing Issues](#wordpress-publishing-issues)
- [AI Features Issues](#ai-features-issues)
- [Database Issues](#database-issues)
- [Network and Connectivity](#network-and-connectivity)
- [Performance Issues](#performance-issues)
- [Getting Help](#getting-help)

## Initial Setup Issues

### Cannot Clone Repository

**Problem:** `git clone` fails with network error

**Symptoms:**
```
fatal: unable to access 'https://github.com/tonoccho/lets_blog_server.git/': Could not resolve host
```

**Solutions:**

1. **Check internet connection**
   ```bash
   ping google.com
   ```
   - If no response, verify network connectivity
   - Try disconnecting/reconnecting WiFi

2. **Try SSH instead of HTTPS**
   ```bash
   git clone git@github.com:tonoccho/lets_blog_server.git
   ```
   - Requires SSH keys configured in GitHub

3. **Check firewall**
   - Some networks block git:// protocol
   - Use HTTPS (`https://`) instead
   - Check corporate proxy settings

4. **Try again later**
   - GitHub API might be temporarily unavailable
   - Wait a few minutes and retry

---

### Environment File (.env) Issues

**Problem:** Cannot edit `.env` file or missing `SERVER_API_KEY`

**Symptoms:**
```
.env file not found
Permission denied when editing .env
```

**Solutions:**

1. **Ensure .env.example exists**
   ```bash
   ls -la .env.example
   ```
   
   If missing, pull latest code:
   ```bash
   git pull origin develop
   ```

2. **Create .env from example**
   ```bash
   cp .env.example .env
   chmod 600 .env   # Restrict permissions
   ```

3. **Edit with appropriate permissions**
   ```bash
   # If permission denied:
   sudo chown $USER:$USER .env
   nano .env   # or vim, code, etc.
   ```

4. **Verify critical fields are set**
   ```bash
   grep -E "^(MYSQL_ROOT_PASSWORD|SERVER_API_KEY|NEXTAUTH_SECRET|APP_ENCRYPTION_KEY)" .env
   ```
   
   All four should have values (not empty).

---

### Certificate Generation Failed

**Problem:** `generate-certs.sh` fails to create certificates

**Symptoms:**
```bash
$ bash scripts/generate-certs.sh
bash: scripts/generate-certs.sh: No such file or directory
```

**Solutions:**

1. **Verify script exists**
   ```bash
   ls -la scripts/generate-certs.sh
   ```

2. **Check file permissions**
   ```bash
   chmod +x scripts/generate-certs.sh
   ```

3. **Run from correct directory**
   ```bash
   # Must be in repository root
   pwd
   # Should show: /path/to/lets_blog_server
   
   bash scripts/generate-certs.sh
   ```

4. **Verify openssl is installed**
   ```bash
   openssl version
   ```
   
   If missing:
   ```bash
   # Linux (Ubuntu/Debian)
   sudo apt-get install openssl
   
   # macOS
   brew install openssl
   ```

5. **Manually create certificates**
   ```bash
   mkdir -p certs
   openssl req -new -x509 -nodes -out certs/localhost.crt \
     -keyout certs/localhost.key -days 365 \
     -subj "/C=US/ST=State/L=City/O=Organization/CN=localhost"
   ```

---

## Docker and Service Issues

### Docker Not Installed

**Problem:** `docker: command not found`

**Solution:**

```bash
# Check if Docker is installed
docker --version

# If not installed, install Docker
# Ubuntu/Debian:
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# macOS:
brew install docker docker-compose
# Then start Docker Desktop from Applications

# Windows:
# Download and install Docker Desktop
# https://www.docker.com/products/docker-desktop
```

After installation, restart terminal and verify:
```bash
docker --version
docker compose version
```

---

### Docker Compose Not Version 2

**Problem:** Using old Docker Compose v1

**Symptoms:**
```bash
$ docker compose version
Docker Compose version v1.29.2
# Want: v2.x
```

**Solution:**

```bash
# Upgrade Docker Compose
# Ubuntu/Debian:
sudo apt-get install -y docker-compose

# macOS (via Docker Desktop):
# Update Docker Desktop from menu

# Verify it's v2+
docker compose version
# Should show: Docker Compose version v2.x.x
```

---

### Ports 80/443 Already in Use

**Problem:** `docker compose up -d` fails with port conflict

**Symptoms:**
```
Error response from daemon: Ports are not available: 
listen tcp 0.0.0.0:443: bind: address already in use
```

**Solutions:**

1. **Check what's using the port**
   ```bash
   # Linux/macOS:
   sudo lsof -i :80
   sudo lsof -i :443
   
   # or
   sudo ss -ltnp | grep -E ':(80|443)'
   ```

2. **Stop the conflicting service**
   ```bash
   # Common services using these ports:
   # - nginx: sudo systemctl stop nginx
   # - apache2: sudo systemctl stop apache2
   # - other web server: kill the process
   
   sudo kill -9 <PID>
   ```

3. **Alternatively, change ports**
   Edit `docker-compose.yml`:
   ```yaml
   reverse-proxy:
     ports:
       - "8080:80"      # Change from 80 to 8080
       - "8443:443"     # Change from 443 to 8443
   ```
   
   Then access via `https://localhost:8443`

---

### Docker Service Fails to Start

**Problem:** `docker compose up -d` fails, service doesn't start

**Symptoms:**
```bash
$ docker compose up -d
ERROR: error running exit hooks: error removing container: <container_id>
```

**Solutions:**

1. **Check Docker daemon**
   ```bash
   docker ps
   ```
   
   If Docker isn't running:
   ```bash
   # Linux:
   sudo systemctl start docker
   
   # macOS/Windows:
   # Open Docker Desktop app
   ```

2. **Check available disk space**
   ```bash
   df -h
   ```
   
   Need at least 20GB free. If low:
   - Clean up old Docker images: `docker image prune -a`
   - Remove unused volumes: `docker volume prune`

3. **Check system resources**
   - Ensure enough RAM available (8GB+ recommended)
   - Check memory: `free -h` (Linux) or `top` (macOS)
   - Close unnecessary applications

4. **Remove broken containers**
   ```bash
   docker compose down --remove-orphans
   docker system prune -a
   docker compose up -d
   ```

---

### Individual Service Won't Start

**Problem:** One service keeps restarting or won't stay up

**Solution:**

1. **Check service logs**
   ```bash
   docker compose logs -f <service-name>
   
   # Examples:
   docker compose logs -f api
   docker compose logs -f web
   docker compose logs -f ollama
   ```

2. **Wait for dependent services**
   Some services depend on others. Wait ~30 seconds:
   ```bash
   docker compose ps
   # Wait until all show "Up"
   ```

3. **Rebuild the service**
   ```bash
   docker compose up -d --build <service-name>
   ```

4. **Check resource limits**
   - If `ollama` or `comfyui` restart, might be GPU/memory issues
   - Monitor: `docker stats`

---

### Cannot Access Docker Services

**Problem:** Cannot reach service at expected URL

**Symptoms:**
```
Connection refused
Service unavailable
```

**Solutions:**

1. **Verify service is running**
   ```bash
   docker compose ps
   
   # All services should show "Up"
   ```

2. **Check reverse proxy logs**
   ```bash
   docker compose logs -f reverse-proxy
   ```

3. **Test internal connectivity**
   ```bash
   docker compose exec api curl http://localhost:8080/api/health
   ```

4. **Try accessing directly (for debugging)**
   ```bash
   # Get container IP
   docker inspect lbs-api | grep '"IPAddress"'
   
   # Test connection to that IP
   curl http://<container-ip>:8080/api/health
   ```

---

## Web Admin Panel Issues

### Cannot Access https://localhost

**Problem:** Browser shows connection error when accessing admin panel

**Symptoms:**
```
This site can't be reached
refused to connect
```

**Solutions:**

1. **Check if services are running**
   ```bash
   docker compose ps
   ```
   
   If not:
   ```bash
   docker compose up -d
   ```

2. **Check reverse proxy logs**
   ```bash
   docker compose logs reverse-proxy | tail -20
   ```

3. **Try direct port access** (debugging only)
   ```bash
   # Get web container port
   docker compose exec web netstat -tlnp | grep LISTEN
   
   # Try accessing directly (might be 3000)
   curl http://localhost:3000/
   ```

4. **Verify certificate exists**
   ```bash
   ls -la certs/
   # Should have: localhost.crt and localhost.key
   ```

5. **Handle self-signed certificate warning**
   - Click "Advanced" or "More info"
   - Click "Proceed to localhost (unsafe)"
   - This is expected for self-signed certificates

---

### SSL Certificate Warning in Browser

**Problem:** `NET::ERR_CERT_AUTHORITY_INVALID` or similar warning

**Reason:** Using self-signed certificate (expected for development)

**Solutions:**

1. **Add certificate to browser** (permanent solution)
   - Open `certs/localhost.crt`
   - Import into browser's certificate store
   - Mark as trusted
   - (Varies by browser; search "add certificate to [browser name]")

2. **Proceed despite warning** (quick fix)
   - Click "Advanced"
   - Click "Proceed to localhost (unsafe)"
   - Access granted for current session

3. **Use different domain** (advanced)
   - Add to `/etc/hosts`: `127.0.0.1 blog.local`
   - Regenerate certificate for `blog.local`
   - Access via `https://blog.local`

---

### Slow Web Panel Loading

**Problem:** Admin panel takes very long to load

**Solutions:**

1. **Check backend API connection**
   ```bash
   curl -s https://localhost/api/health | jq .
   ```

2. **Monitor resources**
   ```bash
   docker stats
   # Watch CPU, memory usage
   ```

3. **Check database responsiveness**
   ```bash
   docker compose exec mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD \
     -e "SELECT 1;" 2>/dev/null
   ```

4. **Restart affected service**
   ```bash
   docker compose restart api
   # or
   docker compose restart web
   ```

---

### Cannot Log In

**Problem:** Login fails with invalid credentials

**Symptoms:**
```
Invalid email or password
```

**Solutions:**

1. **Verify user account exists**
   ```bash
   docker compose exec mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD \
     -D lets_blog -e "SELECT * FROM users;" 2>/dev/null
   ```

2. **Check if setup was completed**
   - If no users, should redirect to `/setup`
   - If stuck on login, try `/setup` manually

3. **Reset via direct database access**
   ```bash
   # This is destructive - only if you can't access setup
   docker compose exec mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD \
     -D lets_blog -e "TRUNCATE users;" 2>/dev/null
   
   # Then visit /setup to create new admin
   ```

---

## VSCode Extension Issues

### Extension Won't Install

**Problem:** Cannot install `.vsix` file in VSCode

**Symptoms:**
```
Command 'Extensions: Install from VSIX...' not found
```

**Solution:**

1. **Download extension**
   - While logged in, go to **System Settings**
   - Click **Download Extension** button
   - Save the `.vsix` file

2. **Install via command line**
   ```bash
   code --install-extension letsblog-vscode-<version>.vsix
   ```

3. **Or install via VSCode UI**
   - Open VSCode
   - Press `Ctrl+Shift+P`
   - Search: `Extensions: Install from VSIX...`
   - Select downloaded file

4. **Verify installation**
   - Press `Ctrl+Shift+P`
   - Search: `Let's Blog: Login`
   - Should appear in command palette

---

### Extension Won't Connect

**Problem:** Extension fails to connect to server

**Symptoms:**
```
Connection failed
Authentication error
Cannot reach server
```

**Solutions:**

1. **Verify server is running**
   ```bash
   curl https://localhost/api/health
   ```

2. **Check extension settings**
   - VSCode menu: **Code** → **Preferences** → **Settings**
   - Search: `letsBlog.serverUrl`
   - Should be `https://localhost` (or your server URL)

3. **Verify API key**
   - Press `Ctrl+Shift+P`
   - Run: `Let's Blog: Set API Key`
   - Enter API key from web admin panel
   - Find key in: **Settings** → **System** → **API Key**

4. **Handle SSL certificate**
   Set environment variable before launching VSCode:
   ```bash
   export NODE_EXTRA_CA_CERTS=~/lets_blog_server/certs/localhost.crt
   code .
   ```

5. **Check firewall**
   ```bash
   # Ensure port 443 is accessible
   curl -v https://localhost
   ```

---

### VSCode Extension Keyboard Shortcuts Not Working

**Problem:** Keyboard shortcuts don't trigger extensions

**Example:** `Ctrl+Alt+P` for publish doesn't work

**Solutions:**

1. **Check if extension is active**
   - VSCode **Extensions** panel
   - Search: "Let's Blog"
   - Should show **Installed** status

2. **Restart VSCode**
   - Close all VSCode windows
   - Reopen

3. **Verify keybindings**
   - **Code** → **Preferences** → **Keyboard Shortcuts**
   - Search: "Let's Blog"
   - Verify shortcuts are mapped

4. **Check for conflicts**
   - Same keybindings might be used by other extensions
   - Try different shortcut
   - Or disable conflicting extension

---

## WordPress Publishing Issues

### Publishing Fails with Authentication Error

**Problem:** "Authentication failed" when publishing to WordPress

**Symptoms:**
```
Error: Invalid credentials
```

**Solutions:**

1. **Verify WordPress app password**
   - Log in to WordPress admin
   - **Users** → **Your Profile**
   - **Application Passwords** section
   - Verify password hasn't expired or been revoked

2. **Test connection in web panel**
   - Go to **Settings** → **Websites**
   - Find the site
   - Click **Test Connection**
   - Should show success or specific error

3. **Regenerate app password**
   - Delete old password from WordPress
   - Create new app password
   - Update in Let's Blog: **Settings** → **Websites** → **Edit**
   - Paste new password

4. **Check WordPress permissions**
   - Logged-in user must have publish capabilities
   - In WordPress: **Users** → check user role (Editor+ required)

---

### Network Timeout During Publishing

**Problem:** Publishing takes forever, then times out

**Symptoms:**
```
Connection timeout
Request timeout
```

**Solutions:**

1. **Check network connectivity**
   ```bash
   ping <your-wordpress-domain>
   ```

2. **Check WordPress server status**
   - Visit WordPress site directly
   - Should load normally
   - If slow, might be server issue

3. **Increase timeout** (advanced)
   - Edit API configuration
   - Set longer timeout for publish requests
   - Default is 30 seconds

4. **Try smaller article**
   - Large articles with images take longer
   - Split into multiple posts
   - Publish separately

5. **Check WordPress plugins**
   - Some plugins slow down REST API
   - Temporarily disable unnecessary plugins
   - Test publishing
   - Re-enable if not the cause

---

### Article Appears but Formatting is Wrong

**Problem:** Published article looks wrong on WordPress

**Symptoms:**
- Markdown formatting not converted properly
- Code blocks look incorrect
- Images not placed correctly

**Solutions:**

1. **Check WordPress content type**
   - WordPress might be expecting HTML
   - Let's Blog sends Markdown
   - WordPress needs markdown plugin to render correctly

2. **Install WordPress plugin**
   WordPress might need markdown plugin:
   - WordPress Plugins: Search "markdown"
   - Install and activate plugin
   - Re-publish article (or convert manually)

3. **Verify Markdown is rendering**
   - Visit article in WordPress
   - Check if raw markdown showing (e.g., `# Title` not rendered as heading)
   - If so, plugin issue

4. **Check image URLs**
   - Images should be full URLs, not relative paths
   - Let's Blog should generate correct URLs
   - If broken, check WordPress image upload settings

5. **Manual fix**
   - Switch WordPress editor to visual mode
   - Manually fix formatting
   - Save

---

### Post Appears on WordPress But Not in Let's Blog

**Problem:** Article published but doesn't show in "My Articles"

**Solution:**

1. **Sync articles**
   - Go to **My Articles**
   - Click **Sync from WordPress** button
   - System re-imports all articles

2. **Check specific site**
   - Might be synced to different site
   - Check **Site** filter
   - Select correct site

---

## AI Features Issues

### Ollama Model Not Found

**Problem:** AI features give "Model not found" error

**Symptoms:**
```
Model qwen2.5:7b-instruct not found
```

**Solution:**

1. **Check if model is pulled**
   ```bash
   docker exec lbs-ollama ollama list
   ```

2. **If model missing, pull it**
   ```bash
   docker exec lbs-ollama ollama pull qwen2.5:7b-instruct
   ```
   
   This may take several minutes depending on internet speed and model size.

3. **Verify after pull**
   ```bash
   docker exec lbs-ollama ollama list
   # Should show qwen2.5:7b-instruct
   ```

4. **Restart API service**
   ```bash
   docker compose restart api
   ```

---

### AI Generation is Very Slow

**Problem:** AI features take too long (minutes instead of seconds)

**Symptoms:**
- Drafting takes 5+ minutes
- Proofreading is very slow

**Solutions:**

1. **Check model size**
   ```bash
   docker exec lbs-ollama ollama list
   ```
   
   Larger models (14b, 32b) are slower:
   - 3b model: ~3-5 seconds
   - 7b model: ~10-20 seconds
   - 14b model: ~30-60 seconds
   
   Use smaller model if too slow.

2. **Check GPU usage**
   ```bash
   docker stats lbs-ollama
   ```
   
   If GPU not showing ~90%+ usage, GPU might not be properly configured.

3. **Check system resources**
   - Running other processes?
   - Close unnecessary applications
   - Check available RAM

4. **Switch to smaller model**
   Edit `.env`:
   ```env
   OLLAMA_MODEL=qwen2.5:3b-instruct
   ```
   
   Then restart:
   ```bash
   docker compose up -d api
   ```

---

### ComfyUI Image Generation Fails

**Problem:** Featured image generation fails or shows error

**Symptoms:**
```
Image generation failed
ComfyUI error
```

**Solutions:**

1. **Check ComfyUI service**
   ```bash
   docker compose logs -f comfyui | tail -20
   ```

2. **Verify model checkpoint exists**
   ```bash
   docker exec lbs-comfyui ls /root/ComfyUI/models/checkpoints/
   ```
   
   Should see your checkpoint file (e.g., `sd-v1-5-fp16.safetensors`)

3. **If missing, download checkpoint**
   - Download model from Hugging Face
   - Copy to ComfyUI container:
   ```bash
   docker cp model.safetensors lbs-comfyui:/root/ComfyUI/models/checkpoints/
   ```

4. **Check `.env` configuration**
   ```bash
   grep COMFYUI .env
   ```
   
   Verify `COMFYUI_CHECKPOINT` matches actual filename.

5. **Restart ComfyUI**
   ```bash
   docker compose restart comfyui
   ```

---

### GPU Not Recognized by AI Services

**Problem:** AI features run on CPU only (very slow)

**Symptoms:**
```
GPU usage: 0%
Model running on CPU
```

**Solutions:**

1. **Check NVIDIA drivers**
   ```bash
   nvidia-smi
   ```
   
   If command not found, NVIDIA drivers not installed.

2. **Install NVIDIA drivers**
   ```bash
   # Ubuntu/Debian:
   sudo apt-get install nvidia-driver-<version>
   
   # Visit nvidia.com for latest driver version
   ```

3. **Install NVIDIA Container Toolkit**
   ```bash
   sudo apt-get install nvidia-container-toolkit
   sudo nvidia-ctk runtime configure --runtime=docker
   sudo systemctl restart docker
   ```

4. **Verify GPU passthrough**
   ```bash
   docker run --rm --gpus all nvidia/cuda:12.1.0-runtime-ubuntu22.04 nvidia-smi
   ```
   
   Should show GPU info if working.

5. **Check container GPU access**
   ```bash
   docker exec lbs-ollama nvidia-smi
   docker exec lbs-comfyui nvidia-smi
   ```

6. **Restart services after GPU is available**
   ```bash
   docker compose down
   docker compose up -d
   ```

---

## Database Issues

### Database Connection Failed

**Problem:** API can't connect to MySQL

**Symptoms:**
```
FATAL ERROR in pool: PoolError: connect ECONNREFUSED 127.0.0.1:3306
```

**Solutions:**

1. **Check MySQL is running**
   ```bash
   docker compose ps mysql
   # Should show "Up"
   ```

2. **Check credentials in `.env`**
   ```bash
   grep MYSQL .env
   MYSQL_ROOT_PASSWORD=<password>
   MYSQL_PASSWORD=<password>
   ```

3. **Test MySQL directly**
   ```bash
   docker exec lbs-mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD \
     -e "SHOW DATABASES;" 2>/dev/null
   ```

4. **Check MySQL logs**
   ```bash
   docker compose logs mysql | tail -20
   ```

5. **Restart MySQL**
   ```bash
   docker compose down mysql
   docker compose up -d mysql
   ```

---

### Database Disk Space Full

**Problem:** Database stops accepting writes

**Symptoms:**
```
Disk quota exceeded
Cannot write to database
```

**Solutions:**

1. **Check available space**
   ```bash
   docker exec lbs-mysql df -h /var/lib/mysql
   ```

2. **Check database size**
   ```bash
   docker exec lbs-mysql du -sh /var/lib/mysql
   ```

3. **Clean up old data** (if applicable)
   - Delete old posts/revisions
   - This requires database access

4. **Expand storage**
   - Add more disk space to system
   - Docker volume might need expansion

---

## Network and Connectivity

### Cannot Reach Server from Another Machine

**Problem:** Can access from localhost but not from other computers

**Solutions:**

1. **Check firewall**
   ```bash
   # Linux:
   sudo ufw allow 80/tcp
   sudo ufw allow 443/tcp
   
   # macOS:
   # System Preferences → Security & Privacy → Firewall
   ```

2. **Find server IP address**
   ```bash
   # Linux:
   ip addr show
   
   # macOS:
   ifconfig | grep "inet "
   ```
   
   Look for `192.168.x.x` or `10.x.x.x` (internal IP)

3. **Access from other machine**
   ```bash
   https://<server-ip>
   # Example: https://192.168.1.100
   ```

4. **Handle SSL certificate warning**
   - For remote connections, certificate warning is normal
   - Browser will show untrusted certificate
   - Add to exceptions or install certificate

---

## Performance Issues

### High CPU Usage

**Problem:** Services using too much CPU

**Solution:**

1. **Monitor what's using CPU**
   ```bash
   docker stats --no-stream
   ```

2. **If Ollama is high:**
   - AI model is processing
   - Wait for processing to complete
   - Or use smaller model

3. **If API is high:**
   - Large number of requests
   - Check for problematic queries in logs

4. **Restart service**
   ```bash
   docker compose restart <service>
   ```

---

### High Memory Usage

**Problem:** System running out of memory

**Solution:**

1. **Check memory usage**
   ```bash
   free -h
   docker stats --no-stream
   ```

2. **Reduce service memory allocation**
   Edit `docker-compose.yml`:
   ```yaml
   services:
     api:
       mem_limit: 2g
     ollama:
       mem_limit: 8g
   ```

3. **Close unnecessary applications**
   - Browser tabs
   - Other services

---

## Getting Help

### When to Check Each Resource

| Problem Type | Check First |
|--------------|------------|
| Installation/setup | [GETTING_STARTED.md](GETTING_STARTED.md) |
| Feature questions | [FEATURES_AND_USAGE.md](FEATURES_AND_USAGE.md) |
| Writing tips | [ARTICLE_AUTHORING_BEST_PRACTICES.md](ARTICLE_AUTHORING_BEST_PRACTICES.md) |
| Technical issue | This troubleshooting guide |
| Learning by example | [VIDEO_TUTORIALS_GUIDE.md](VIDEO_TUTORIALS_GUIDE.md) |

### Collecting Debug Information

Before reporting issues, gather:

```bash
# System info
docker --version
docker compose version
nvidia-smi 2>/dev/null || echo "No GPU"

# Service status
docker compose ps

# Recent logs
docker compose logs --tail=50 > logs.txt

# Environment check (sanitize passwords first!)
env | grep -i lets_blog || env | grep -i docker
```

### Reporting Issues

When creating GitHub issue:

1. **Describe the problem clearly**
2. **Include steps to reproduce**
3. **Share relevant logs** (remove sensitive info)
4. **List system specs** (OS, Docker version, GPU)
5. **Include error messages** (full output, not summary)

GitHub Issues: https://github.com/tonoccho/lets_blog_server/issues

---

**Still stuck?** Please don't hesitate to:
- Check recent issues in GitHub
- Ask in discussions
- Create detailed bug report with logs and steps to reproduce

We're here to help! 🙌
