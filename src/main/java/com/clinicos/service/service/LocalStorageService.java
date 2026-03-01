package com.clinicos.service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local filesystem storage implementation.
 * Files are saved to a configurable base path and served via Nginx.
 * Activated when clinicos.storage.type=local (default).
 */
@Service
@ConditionalOnProperty(name = "clinicos.storage.type", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalStorageService implements StorageService {

    @Value("${clinicos.storage.local.base-path:/opt/clinicos/uploads}")
    private String basePath;

    @Value("${clinicos.storage.local.base-url:http://localhost:8080/uploads}")
    private String baseUrl;

    @Override
    public StorageResult store(String key, InputStream data, long size, String contentType) {
        try {
            Path filePath = Path.of(basePath, key);
            Files.createDirectories(filePath.getParent());
            Files.copy(data, filePath, StandardCopyOption.REPLACE_EXISTING);

            String url = getPublicUrl(key);
            log.info("File stored locally: {} ({} bytes)", key, size);
            return new StorageResult(key, url, size);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path filePath = Path.of(basePath, key);
            Files.deleteIfExists(filePath);
            log.info("File deleted: {}", key);
        } catch (IOException e) {
            log.error("Failed to delete file: {}", key, e);
        }
    }

    @Override
    public String getPublicUrl(String key) {
        return baseUrl + "/" + key;
    }
}
