package com.mod98.alpaca.spx.service.telegram;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class TelegramImageService {

    public byte[] loadImageBytes(String imagePath) throws Exception {
        return Files.readAllBytes(Path.of(imagePath));
    }
}
