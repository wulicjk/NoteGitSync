package com.cjk.tools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ExpiringConcurrentHashMap<K, V> {
    private final Map<K, Long> expirationMap = new ConcurrentHashMap<>();
    private final Map<K, V> dataMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    public void put(K key, V value) {
        long defaultExpirationMillis = 1000;
        long expirationTime = System.currentTimeMillis() + defaultExpirationMillis;
        expirationMap.put(key, expirationTime);
        dataMap.put(key, value);
        // Schedule removal after 2 seconds
        executorService.schedule(() -> {
            remove(key);
        }, 2, TimeUnit.SECONDS);
    }

    public V get(K key) {
        Long expirationTime = expirationMap.get(key);
        if (expirationTime != null && expirationTime >= System.currentTimeMillis()) {
            return dataMap.get(key);
        } else {
            // Remove the expired entry
            expirationMap.remove(key);
            dataMap.remove(key);
            return null;
        }
    }

    public void remove(K key) {
        expirationMap.remove(key);
        dataMap.remove(key);
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public int size() {
        return dataMap.size();
    }

    public void clear() {
        expirationMap.clear();
        dataMap.clear();
    }
    public void shutdown() {
        executorService.shutdown();
    }
}
