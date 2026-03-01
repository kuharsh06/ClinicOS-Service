package com.clinicos.service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;

/**
 * S3-compatible storage implementation.
 * Works with AWS S3, DigitalOcean Spaces, MinIO, etc.
 * Activated when clinicos.storage.type=s3.
 */
@Service
@ConditionalOnProperty(name = "clinicos.storage.type", havingValue = "s3")
@Slf4j
public class S3StorageService implements StorageService {

    @Value("${clinicos.storage.s3.bucket}")
    private String bucket;

    @Value("${clinicos.storage.s3.region}")
    private String region;

    @Value("${clinicos.storage.s3.endpoint}")
    private String endpoint;

    @Value("${clinicos.storage.s3.access-key}")
    private String accessKey;

    @Value("${clinicos.storage.s3.secret-key}")
    private String secretKey;

    @Value("${clinicos.storage.s3.public-url}")
    private String publicUrl;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(false)
                .build();

        log.info("S3 storage initialized: bucket={}, endpoint={}", bucket, endpoint);
    }

    @Override
    public StorageResult store(String key, InputStream data, long size, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(data, size));

            String url = getPublicUrl(key);
            log.info("File stored in S3: {} ({} bytes)", key, size);
            return new StorageResult(key, url, size);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store file in S3: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            s3Client.deleteObject(request);
            log.info("File deleted from S3: {}", key);
        } catch (Exception e) {
            log.error("Failed to delete file from S3: {}", key, e);
        }
    }

    @Override
    public String getPublicUrl(String key) {
        return publicUrl + "/" + key;
    }
}
