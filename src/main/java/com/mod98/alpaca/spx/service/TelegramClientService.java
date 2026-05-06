package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.config.TelegramProperties;
import com.mod98.alpaca.spx.service.telegram.TelegramMessageHandler;
import it.tdlight.client.*;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramClientService {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientService.class);

    private final TelegramProperties props;
    private final TelegramMessageHandler messageHandler;

    private SimpleTelegramClientFactory factory;
    private SimpleTelegramClient client;

    // نخزّن هنا الرسائل اللي فيها صور بانتظار ما تتحمّل الملفات بالكامل
    // key = fileId من تيليجرام
    private final Map<Integer, TdApi.Message> pendingPhotoMessages = new ConcurrentHashMap<>();

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

            // Auth states
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthUpdate);

            // رسائل جديدة
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);

            // تحديثات الملفات (تنزيل الصور)
            builder.addUpdateHandler(TdApi.UpdateFile.class, this::onUpdateFile);

            // Log connection state
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
        String dir = (sessionDir == null || sessionDir.isBlank())
                ? "tdlight-session"
                : sessionDir;

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
            log.warn("Authorization: WAIT 2FA PASSWORD — Enter your two-step verification password:");
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
            log.info("🔍 IGNORED — mismatch");
            return;
        }
        // ... باقي الكود



       // قناة عناد فقط
       // long targetChatId = props.getChannelTelegramId();
       //if (msg.chatId != targetChatId) {
       //  return;
       //}

//        if (msg.chatId != -3970356976L ) {
//            // 3970356976
//            return;
//        }

        log.info("📩 Telegram delivered message at: {}", Instant.now());

        // لو الرسالة صورة (الكارد)
        if (msg.content instanceof TdApi.MessagePhoto photoMsg) {
            TdApi.PhotoSize size = photoMsg.photo.sizes[photoMsg.photo.sizes.length - 1];
            int fileId = size.photo.id;

            // نخزّن الرسالة بانتظار تحميل الصورة
            pendingPhotoMessages.put(fileId, msg);

            // نطلب من TDLib تحميل الملف
            TdApi.DownloadFile df = new TdApi.DownloadFile(fileId, 32, 0, 0, true);
            client.send(df);

            log.info("📥 Requested download for fileId={} (messageId={})", fileId, msg.id);
        } else {
            // رسالة نصية فقط (إلغاء، تحديث أمر، خروج يدوي…)
            try {
                messageHandler.onTelegramMessage(msg, null);
            } catch (Exception e) {
                log.error("Error while handling text-only Telegram message {}", msg.id, e);
            }
        }
    }

    private void onUpdateFile(TdApi.UpdateFile upd) {
        TdApi.File file = upd.file;

        // ما يهمنا إلا لما يكتمل التحميل
        if (!file.local.isDownloadingCompleted) {
            return;
        }

        int fileId = file.id;
        TdApi.Message msg = pendingPhotoMessages.remove(fileId);
        if (msg == null) {
            // ملف مو من الصور اللي نتابعها (نعطيه سكيب)
            return;
        }

        String localPath = file.local.path;
        log.info("✅ Photo downloaded fileId={} path={} (messageId={})", fileId, localPath, msg.id);

        try {
            // الآن فقط: الرسالة + الصورة الجاهزة نرسلهم للهاندلر
            messageHandler.onTelegramMessage(msg, localPath);
        } catch (Exception e) {
            log.error("Error while handling Telegram photo message {} after download", msg.id, e);
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
}
