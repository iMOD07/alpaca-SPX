package com.mod98.alpaca.spx.service.telegram;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TelegramSignalContext {

    private long chatId;
    private long messageId;
    private Long replyToMessageId; // // It could be null
    private String headerText; // From the contract image (SPXW $6,845 28 Nov 25 (W) Call 100)
    private String bodyText; // Arabic text under the image
    private String fullText; // Merging them together for OpenAI
    private boolean hasImage;
    private String imagePath; // Image path on disk (from TDLib)

}
