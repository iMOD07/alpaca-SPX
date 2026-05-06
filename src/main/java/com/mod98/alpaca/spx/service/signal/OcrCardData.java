// src/main/java/com/mod98/alpaca/spx/service/signal/OcrCardData.java
package com.mod98.alpaca.spx.service.signal;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OcrCardData {

    // From header: "SPXW $6,845 28 Nov 25 (W)Call 100"
    private String symbolRaw;   // e.g. "SPXW"
    private String strikeRaw;   // e.g. "6,845"
    private String dateRaw;     // e.g. "28 Nov 25"
    private String typeRaw;     // e.g. "Call"

    // From big price in the middle
    private String entryRaw;    // e.g. "3.30" or "330" (before normalization)
}
