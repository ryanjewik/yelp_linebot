package com.ryanhideo.linebot.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class FileLogger {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void appendToFile(String fileName, String content) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String wrapped = "\n===== " + timestamp + " =====\n" +
                content +
                "\n===========================\n";

        try {
            Files.writeString(
                    Path.of(fileName),
                    wrapped,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
