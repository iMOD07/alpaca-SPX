package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.config.TelegramProperties;
import com.mod98.alpaca.spx.service.telegram.TelegramMessageHandler;
import it.tdlight.client.*;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TDLight client with robust photo-download handling.
 *
 * Fixes:
 *  1. CACHE HIT: لو الصورة موجودة بالفعل (نفس fileId)، UpdateFile قد لا يصل.
 *     نفحص isDownloadingCompleted قبل ما نطلب download جديد.
 *  2. STALE PENDING: لو download فشل بصمت، watchdog ينظّف entries القديمة كل 30s.
 *  3. DOWNLOAD ERRORS: نلتقط TdApi.Error من callback ونحذف الـ pending entry.
 */
@Service
public class TelegramClientService {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientService.class);

    /** Max time to wait for a photo download before giving up. */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final TelegramProperties props;
    private final TelegramMessageHandler messageHandler;

    private SimpleTelegramClientFactory factory;
    private SimpleTelegramClient client;

    /** key = fileId, value = pending entry (msg + queuedAt) */
    private final Map<Integer, PendingPhoto> pendingPhotoMessages = new ConcurrentHashMap<>();

    public TelegramClientService(TelegramProperties props,
                                 TelegramMessageHandler messageHandler) {
        this.props = props;
        this.messageHandler = messageHandler;
    }

    @PostConstruct
    public void startLogin() {
        try {
            APIToken apiToken = new APIToken(props.getApiId(), props.getApiHash());

            TDLibSettings td = TDLibSettings.create(apiToken);
            Path base = ensureSessionDirs(props.getSessionDir());
            td.setDatabaseDirectoryPath(base.resolve("db"));
            td.setDownloadedFilesDirectoryPath(base.resolve("files"));

            factory = new SimpleTelegramClientFactory();
            SimpleTelegramClientBuilder builder = factory.builder(td);

            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthUpdate);
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);
            builder.addUpdateHandler(TdApi.UpdateFile.class, this::onUpdateFile);
            builder.addUpdateHandler(TdApi.UpdateConnectionState.class, u ->
                    log.info("TDLib connection state: {}", u.state.getClass().getSimpleName())
            );

            AuthenticationSupplier<?> auth = AuthenticationSupplier.user(props.getPhone());
            client = builder.build(auth);

            log.info("Telegram TDLight client started — waiting for authorization…");
        } catch (Throwable t) {
            log.error("Failed to launch the Telegram client.", t);
        }
    }

    private Path ensureSessionDirs(String sessionDir) throws Exception {
        String dir = (sessionDir == null || sessionDir.isBlank()) ? "tdlight-session" : sessionDir;
        Path base = Paths.get(dir).toAbsolutePath();
        Files.createDirectories(base.resolve("db"));
        Files.createDirectories(base.resolve("files"));
        if (!Files.isWritable(base)) {
            throw new IllegalStateException("Session dir not writable: " + base);
        }
        log.info("TD session base: {}", base);
        return base;
    }

    private void onAuthUpdate(TdApi.UpdateAuthorizationState upd) {
        var st = upd.authorizationState;
        if (st instanceof TdApi.AuthorizationStateWaitCode) {
            log.warn("Authorization: WAIT CODE — Enter login code:");
            System.out.print("Enter Telegram login code: ");
            String code = new Scanner(System.in).nextLine().trim();
            client.send(new TdApi.CheckAuthenticationCode(code));
        } else if (st instanceof TdApi.AuthorizationStateWaitPassword) {
            log.warn("Authorization: WAIT 2FA PASSWORD");
            System.out.print("Enter 2FA password: ");
            String password = new Scanner(System.in).nextLine();
            client.send(new TdApi.CheckAuthenticationPassword(password));
        } else if (st instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation conf) {
            log.info("Authorization: WAIT QR CONFIRMATION → {}", conf.link);
        } else if (st instanceof TdApi.AuthorizationStateReady) {
            log.info("Authorization: READY ✅ — Session saved at: {}", props.getSessionDir());
        } else if (st instanceof TdApi.AuthorizationStateClosed) {
            log.info("Authorization: CLOSED — Client closed by TDLib.");
        } else if (st instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            log.info("Authorization: WAIT PHONE NUMBER — Will use: {}", props.getPhone());
        } else {
            log.info("Authorization: Unexpected state: {}", st.getClass().getSimpleName());
        }
    }

    private void onNewMessage(TdApi.UpdateNewMessage upd) {
        TdApi.Message msg = upd.message;

        log.info("🔍 RAW chatId={} msgId={}", msg.chatId, msg.id);
        log.info("🔍 EXPECTED chatId from props={}", props.getChannelTelegramId());

        if (msg.chatId != props.getChannelTelegramId()) {
            log.info("🔍 IGNORED — chatId mismatch");
            return;
        }

        log.info("📩 Telegram delivered message at: {}", Instant.now());

        if (msg.content instanceof TdApi.MessagePhoto photoMsg) {
            handlePhotoMessage(msg, photoMsg);
        } else {
            try {
                messageHandler.onTelegramMessage(msg, null);
            } catch (Exception e) {
                log.error("Error while handling text-only Telegram message {}", msg.id, e);
            }
        }
    }

    /**
     * Robust photo handling:
     *  - Cache hit: file already on disk → process immediately, skip download
     *  - Otherwise: register pending + request download
     */
    private void handlePhotoMessage(TdApi.Message msg, TdApi.MessagePhoto photoMsg) {
        TdApi.PhotoSize size = photoMsg.photo.sizes[photoMsg.photo.sizes.length - 1];
        TdApi.File file = size.photo;
        int fileId = file.id;

        // 🎯 CACHE HIT: file already downloaded and present on disk
        if (file.local != null && file.local.isDownloadingCompleted
                && file.local.path != null && !file.local.path.isBlank()
                && new File(file.local.path).exists()) {
            log.info("✅ Photo cached fileId={} path={} (messageId={}) — processing immediately",
                    fileId, file.local.path, msg.id);
            dispatchPhoto(msg, file.local.path);
            return;
        }

        // Not cached — register pending and request download
        pendingPhotoMessages.put(fileId, new PendingPhoto(msg, Instant.now()));

        // priority=32, offset=0, limit=0, synchronous=true
        TdApi.DownloadFile df = new TdApi.DownloadFile(fileId, 32, 0, 0, true);

        client.send(df, result -> {
            // tdlight wraps response in Result<T> — check error first
            if (result.isError()) {
                TdApi.Error err = result.getError();
                log.error("DownloadFile FAILED | fileId={} code={} msg={}",
                        fileId, err.code, err.message);
                pendingPhotoMessages.remove(fileId);
                return;
            }

            // Success — get the unwrapped TdApi.File
            TdApi.File f = result.get();
            if (f.local != null && f.local.isDownloadingCompleted
                    && f.local.path != null && !f.local.path.isBlank()) {
                PendingPhoto pp = pendingPhotoMessages.remove(fileId);
                if (pp != null) {
                    log.info("✅ Photo downloaded (sync result) fileId={} path={} (messageId={})",
                            fileId, f.local.path, pp.msg.id);
                    dispatchPhoto(pp.msg, f.local.path);
                }
            }
            // Otherwise: still downloading — wait for UpdateFile event
        });

        log.info("📥 Requested download for fileId={} (messageId={})", fileId, msg.id);
    }

    private void onUpdateFile(TdApi.UpdateFile upd) {
        TdApi.File file = upd.file;
        int fileId = file.id;

        // Only act on files we're tracking
        PendingPhoto pp = pendingPhotoMessages.get(fileId);
        if (pp == null) return;

        // Download completed successfully
        if (file.local != null && file.local.isDownloadingCompleted
                && file.local.path != null && !file.local.path.isBlank()) {
            pendingPhotoMessages.remove(fileId);
            log.info("✅ Photo downloaded fileId={} path={} (messageId={})",
                    fileId, file.local.path, pp.msg.id);
            dispatchPhoto(pp.msg, file.local.path);
            return;
        }

        // Download stalled silently (not active, not complete)
        if (file.local != null && !file.local.isDownloadingActive
                && !file.local.isDownloadingCompleted) {
            pendingPhotoMessages.remove(fileId);
            log.error("❌ Download stalled silently for fileId={} (messageId={})",
                    fileId, pp.msg.id);
        }
        // else: still downloading — keep waiting
    }

    private void dispatchPhoto(TdApi.Message msg, String localPath) {
        try {
            messageHandler.onTelegramMessage(msg, localPath);
        } catch (Exception e) {
            log.error("Error dispatching photo message {}", msg.id, e);
        }
    }

    /**
     * Cleans up stale pending downloads that never received UpdateFile.
     */
    @Scheduled(fixedDelay = 30_000)
    public void cleanupStalePending() {
        if (pendingPhotoMessages.isEmpty()) return;
        Instant cutoff = Instant.now().minus(DOWNLOAD_TIMEOUT);
        Iterator<Map.Entry<Integer, PendingPhoto>> it = pendingPhotoMessages.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PendingPhoto> e = it.next();
            if (e.getValue().queuedAt.isBefore(cutoff)) {
                log.error("⏱️ Stale download — fileId={} msgId={} pendingFor={}s — dropping",
                        e.getKey(), e.getValue().msg.id,
                        Duration.between(e.getValue().queuedAt, Instant.now()).toSeconds());
                it.remove();
            }
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (client != null) {
                client.close();
                log.info("TDLight client stopped.");
            }
        } catch (Exception ignored) {}
        try {
            if (factory != null) {
                factory.close();
                log.info("TDLight factory closed.");
            }
        } catch (Exception ignored) {}
    }

    private record PendingPhoto(TdApi.Message msg, Instant queuedAt) {}
}
