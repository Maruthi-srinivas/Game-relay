package com.example.gamechat.storage;

import java.io.InputStream;

public interface ObjectStore {

    void put(String key, byte[] data, String contentType);

    InputStream get(String key);
}
