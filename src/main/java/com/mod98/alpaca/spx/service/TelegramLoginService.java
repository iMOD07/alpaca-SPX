package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.config.TelegramProperties;
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
import java.util.Scanner;

@Service
public class TelegramLoginService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLoginService.class);

    private final TelegramProperties props;

    private SimpleTelegramClientFactory factory;
    private SimpleTelegramClient client;

    public TelegramLoginService(TelegramProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void startLogin() {
        try {
            // 1) API Token
            APIToken apiToken = new APIToken(props.getApiId(), props.getApiHash());

            // 2) TDLib settings and session storage paths (ensure dirs exist + writable)
            TDLibSettings td = TDLibSettings.create(apiToken);
            Path base = ensureSessionDirs(props.getSessionDir()); // creates <base>/db and <base>/files if missing
            td.setDatabaseDirectoryPath(base.resolve("db"));
            td.setDownloadedFilesDirectoryPath(base.resolve("files"));

            // 3) Build client and add handlers
            factory = new SimpleTelegramClientFactory();
            SimpleTelegramClientBuilder builder = factory.builder(td);

            // 3.a) Authorization flow
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthUpdate);

            // 3.b) Connection-state logs (useful for 24/7 bots)
            builder.addUpdateHandler(TdApi.UpdateConnectionState.class, u ->
                    log.info("TDLib connection state: {}", u.state.getClass().getSimpleName())
            );

            // 4) Auth source: user phone (first run will ask for code/2FA; next runs use saved session)
            AuthenticationSupplier<?> auth = AuthenticationSupplier.user(props.getPhone());

            client = builder.build(auth);

            log.info("Telegram TDLight client started — waiting for authorization…");

        } catch (Throwable t) {
            log.error("Failed to launch the Telegram client for login purposes. API will continue without Telegram.", t);
        }
    }

    private Path ensureSessionDirs(String sessionDir) throws Exception {
        String dir = (sessionDir == null || sessionDir.isBlank())
                ? "tdlight-session"
                : sessionDir;

        Path base = Paths.get(dir).toAbsolutePath();
        // Create base, db, files
        Files.createDirectories(base.resolve("db"));
        Files.createDirectories(base.resolve("files"));
        // Writability checks
        if (!Files.isWritable(base)) {
            throw new IllegalStateException("Session dir not writable: " + base);
        }
        log.info("TD session base: {}", base);
        return base;
    }

    private void onAuthUpdate(TdApi.UpdateAuthorizationState upd) {
        var st = upd.authorizationState;

        if (st instanceof TdApi.AuthorizationStateWaitCode) {
            // First run: will ask for login code
            log.warn("Authorization: WAIT CODE — Enter login code:");
            System.out.print("Enter Telegram login code: ");
            String code = new Scanner(System.in).nextLine().trim();
            client.send(new TdApi.CheckAuthenticationCode(code));

        } else if (st instanceof TdApi.AuthorizationStateWaitPassword) {
            // If 2FA enabled
            log.warn("Authorization: WAIT 2FA PASSWORD — Enter your two-step verification password:");
            System.out.print("Enter 2FA password: ");
            String password = new Scanner(System.in).nextLine();
            client.send(new TdApi.CheckAuthenticationPassword(password));

        } else if (st instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation conf) {
            // QR confirmation from another device
            log.info("Authorization: WAIT QR CONFIRMATION → Open the link and approve: {}", conf.link);

        } else if (st instanceof TdApi.AuthorizationStateReady) {
            // Login successful — session saved; next launches won't ask for code unless session dir is removed
            log.info("Authorization: READY ✅ — Session saved at: {}", props.getSessionDir());
            log.info("Bot stays online 24/7 and will keep listening for updates.");

        } else if (st instanceof TdApi.AuthorizationStateClosed) {
            log.info("Authorization: CLOSED — Client closed by TDLib.");

        } else if (st instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            // Usually auto-provided by AuthenticationSupplier.user(phone)
            log.info("Authorization: WAIT PHONE NUMBER — Will use: {}", props.getPhone());

        } else {
            log.info("Authorization: Unexpected state: {}", st.getClass().getSimpleName());
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
