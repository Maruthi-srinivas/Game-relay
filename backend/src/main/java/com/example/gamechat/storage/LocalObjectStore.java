package com.example.gamechat.storage;

import com.example.gamechat.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "app.s3.enabled", havingValue = "false", matchIfMissing = true)
public class LocalObjectStore implements ObjectStore {

    private final Path root;

    public LocalObjectStore(@Value("${app.s3.local-dir:${java.io.tmpdir}/gamechat-objects}") String dir) {
        this.root = Path.of(dir);
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        try {
            Path path = root.resolve(key).normalize();
            Files.createDirectories(path.getParent());
            Files.write(path, data);
        } catch (IOException ex) {
            throw ApiException.badRequest("Failed to store attachment");
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            Path path = root.resolve(key).normalize();
            if (!path.startsWith(root) || !Files.exists(path)) {
                throw ApiException.notFound("Attachment not found");
            }
            return Files.newInputStream(path);
        } catch (IOException ex) {
            throw ApiException.notFound("Attachment not found");
        }
    }
}
