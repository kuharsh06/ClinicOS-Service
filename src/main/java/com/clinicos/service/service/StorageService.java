package com.clinicos.service.service;

import java.io.InputStream;

/**
 * Abstraction for file storage. Implementations can target local filesystem,
 * S3, DigitalOcean Spaces, or any S3-compatible storage.
 */
public interface StorageService {

    /**
     * Store a file.
     * @param key Storage key (e.g., "org-uuid/patients/pat-uuid/file-uuid.jpg")
     * @param data File input stream
     * @param size File size in bytes
     * @param contentType MIME type
     * @return Storage result with key and public URL
     */
    StorageResult store(String key, InputStream data, long size, String contentType);

    /**
     * Delete a file by its storage key.
     */
    void delete(String key);

    /**
     * Get the public URL for a storage key.
     */
    String getPublicUrl(String key);

    record StorageResult(String key, String url, long sizeBytes) {}
}
