package com.mod98.alpaca.spx.parsing;
import java.nio.file.Path;

public interface ImageAnalysisService {
    ImageAnalysisResult analyze(Path imagePath);
}
