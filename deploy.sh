#!/bin/bash
# ============================================================
# SPX Trading Bot - Deploy Script
# Build locally with Maven, upload JAR, restart systemd service
# ============================================================

set -e

# ==============================
# Configuration
# ==============================
PROJECT_DIR="/Users/abdullahali/Desktop/myProject/SPX_2026/SPX"
JAR_NAME="spx.jar"
LOCAL_JAR="$PROJECT_DIR/target/SPX-0.0.1-SNAPSHOT.jar"

SSH_KEY="/Users/abdullahali/Desktop/myProject/SPX_2026/AWS/my_key_SPX.pem"
SERVER_USER="ubuntu"
SERVER_IP="13.216.181.255"
REMOTE_DIR="/opt/spx-bot"
SERVICE_NAME="spx.service"
APP_PORT="8080"

# Telegram alerts (set to empty to disable)
TELEGRAM_BOT_TOKEN=""
TELEGRAM_CHAT_ID=""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $1"; }
info() { echo -e "${BLUE}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }
fail() { echo -e "${RED}[FATAL]${NC} $1"; telegram_alert "❌ Deploy FAILED: $1"; exit 1; }

SSH_CMD="ssh -i $SSH_KEY -o StrictHostKeyChecking=no $SERVER_USER@$SERVER_IP"

# ==============================
# Telegram Alert Helper
# ==============================
telegram_alert() {
    local msg="$1"
    if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ]; then
        curl -s -X POST \
            "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
            -d "chat_id=${TELEGRAM_CHAT_ID}" \
            -d "text=🤖 SPX Deploy [$(date +%H:%M)]: $msg" \
            > /dev/null 2>&1 || true
    fi
}

# ==============================
# 1. Pre-flight checks
# ==============================
log "🔍 Pre-flight checks..."

[ -f "$SSH_KEY" ] || fail "SSH key not found: $SSH_KEY"
[ -d "$PROJECT_DIR" ] || fail "Project dir not found: $PROJECT_DIR"

if ! $SSH_CMD "echo connected" > /dev/null 2>&1; then
    fail "Cannot SSH to server $SERVER_IP"
fi

info "✅ SSH connection OK"

# Check IB Gateway is running on server
GATEWAY_STATUS=$($SSH_CMD "sudo systemctl is-active ibgateway.service" 2>/dev/null || echo "inactive")
if [ "$GATEWAY_STATUS" != "active" ]; then
    warn "IB Gateway is not active: $GATEWAY_STATUS"
    warn "SPX bot may fail to connect. Continue? (y/n)"
    read -r CONFIRM
    [ "$CONFIRM" = "y" ] || fail "Aborted by user"
fi

info "✅ IB Gateway is active"

# ==============================
# 2. Build project
# ==============================
if [ "$1" != "--skip-build" ]; then
    log "🔨 Building project with Maven..."
    cd "$PROJECT_DIR"
    mvn clean package -DskipTests || fail "Maven build failed"
fi

if [ ! -f "$LOCAL_JAR" ]; then
    fail "JAR file not found: $LOCAL_JAR"
fi

JAR_SIZE=$(du -h "$LOCAL_JAR" | cut -f1)
log "✅ JAR ready — size: $JAR_SIZE"

# ==============================
# 3. Backup current JAR on server
# ==============================
log "📦 Backing up current JAR on server..."
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
$SSH_CMD "[ -f $REMOTE_DIR/$JAR_NAME ] && cp $REMOTE_DIR/$JAR_NAME $REMOTE_DIR/$JAR_NAME.bak_$TIMESTAMP || true"
info "Backup: $JAR_NAME.bak_$TIMESTAMP (if existed)"

# ==============================
# 4. Stop service
# ==============================
log "🛑 Stopping $SERVICE_NAME..."
$SSH_CMD "sudo systemctl stop $SERVICE_NAME" 2>/dev/null || info "Service was not running"

# ==============================
# 5. Upload new JAR
# ==============================
log "⬆️  Uploading new JAR to $REMOTE_DIR ..."
scp -i "$SSH_KEY" -o StrictHostKeyChecking=no "$LOCAL_JAR" "$SERVER_USER@$SERVER_IP:$REMOTE_DIR/$JAR_NAME" \
    || fail "JAR upload failed"

info "✅ JAR uploaded"

# Verify upload
REMOTE_SIZE=$($SSH_CMD "stat -c%s $REMOTE_DIR/$JAR_NAME")
LOCAL_SIZE=$(stat -f%z "$LOCAL_JAR")
if [ "$REMOTE_SIZE" != "$LOCAL_SIZE" ]; then
    fail "Size mismatch! Local: $LOCAL_SIZE, Remote: $REMOTE_SIZE"
fi
info "✅ Size verified: $REMOTE_SIZE bytes"

# ==============================
# 6. Start service
# ==============================
log "🚀 Starting $SERVICE_NAME..."
$SSH_CMD "sudo systemctl start $SERVICE_NAME" || fail "Failed to start service"

# ==============================
# 7. Health checks
# ==============================
log "⏳ Waiting for SPX bot to boot (max 90s)..."
sleep 15

log "🔍 Checking service status..."
STATUS=$($SSH_CMD "sudo systemctl is-active $SERVICE_NAME" 2>/dev/null || echo "failed")
if [ "$STATUS" = "active" ]; then
    log "✅ Service is active"
else
    err "Service failed to start. Last 30 lines of logs:"
    $SSH_CMD "sudo journalctl -u $SERVICE_NAME -n 30 --no-pager" || true
    fail "Service is not active: $STATUS"
fi

log "🔍 Checking port $APP_PORT (Spring Boot)..."
PORT_UP=false
for i in {1..10}; do
    PORT_CHECK=$($SSH_CMD "sudo ss -tlnp | grep :$APP_PORT || true")
    if [ -n "$PORT_CHECK" ]; then
        PORT_UP=true
        break
    fi
    info "Port not listening yet... retry $i/10"
    sleep 6
done

if [ "$PORT_UP" = true ]; then
    log "✅ Port $APP_PORT is listening"
else
    warn "Port $APP_PORT not listening after 60s. Check logs."
    $SSH_CMD "sudo journalctl -u $SERVICE_NAME -n 50 --no-pager" || true
    fail "App did not start listening on port $APP_PORT"
fi

# Verify Spring Boot startup in logs
log "🔍 Verifying Spring Boot startup..."
STARTED=$($SSH_CMD "sudo journalctl -u $SERVICE_NAME --since '2 minutes ago' | grep -c 'Started SpxApplication' || true")
if [ "$STARTED" -gt 0 ]; then
    log "✅ SpxApplication started successfully"
else
    warn "Could not find 'Started SpxApplication' in logs (may still be starting)"
fi

# ==============================
# 8. Clean up old backups (keep last 5)
# ==============================
log "🧹 Cleaning old backups (keeping last 5)..."
$SSH_CMD "cd $REMOTE_DIR && ls -t $JAR_NAME.bak_* 2>/dev/null | tail -n +6 | xargs -r rm -f" || true

# ==============================
# Done
# ==============================
echo ""
log "🎉 Deployment complete!"
telegram_alert "✅ Deploy successful (JAR size: $JAR_SIZE)"

echo ""
echo "📋 Useful commands:"
echo "  Logs:    $SSH_CMD 'sudo journalctl -u $SERVICE_NAME -f'"
echo "  Status:  $SSH_CMD 'sudo systemctl status $SERVICE_NAME'"
echo "  Restart: $SSH_CMD 'sudo systemctl restart $SERVICE_NAME'"
echo ""
