package com.mod98.alpaca.spx.service.telegram;

import it.tdlight.jni.TdApi;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalTime;

@Component
public class TelegramMessageExtractor {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessageExtractor.class);

    public TelegramSignalContext extract(TdApi.Message msg, String imagePath) {

        // 🔔 تسجيل وقت وصول الرسالة (زي كودك بالضبط)
        log.info("📩 Telegram delivered message at: {} | msgId={} | chatId={}", Instant.now(), msg.id, msg.chatId);
        System.out.println("🔍 Extract started at: " + LocalTime.now());

        TelegramSignalContext ctx = new TelegramSignalContext();

        // IDs
        ctx.setChatId(msg.chatId);
        ctx.setMessageId(msg.id);
        ctx.setReplyToMessageId(extractReplyTo(msg));

        // Header + Body (نفس فكرتك، الهيدر حالياً فاضي)
        String header = extractHeaderText(msg);
        String body = extractBodyText(msg);

        ctx.setHeaderText(header);
        ctx.setBodyText(body != null ? body : "");
        ctx.setFullText((header + "\n\n" + ctx.getBodyText()).trim());

        // ✅ استخدم المسار الجاهز اللي جاك من TelegramClientService
        ctx.setImagePath(imagePath);
        ctx.setHasImage(imagePath != null && !imagePath.isBlank());

        return ctx;
    }

    private Long extractReplyTo(TdApi.Message msg) {
        if (msg.replyTo == null) return null;

        if (msg.replyTo instanceof TdApi.MessageReplyToMessage r) {
            if (r.messageId != 0) {
                return r.messageId;
            }
        }
        return null;
    }

    private String extractHeaderText(TdApi.Message msg) {
        // حالياً ما نستخدم هيدر منفصل
        return "";
    }

    private String extractBodyText(TdApi.Message msg) {
        if (msg.content instanceof TdApi.MessageText textMsg) {
            return textMsg.text == null ? "" : textMsg.text.text;
        }
        if (msg.content instanceof TdApi.MessagePhoto photoMsg) {
            if (photoMsg.caption == null || photoMsg.caption.text == null) {
                return "";
            }
            return photoMsg.caption.text;
        }
        return "";
    }
}
