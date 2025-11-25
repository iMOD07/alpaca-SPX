package com.mod98.alpaca.spx.parsing;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImageAnalysisResult {
    private String imageRole;      // PREPARE / ENTRY / IGNORE
    private Double entryPrice;
    private Integer contractCount;
    private String direction;      // LONG / SHORT / UNKNOWN
    private Double confidence;
    private String notes;
}
