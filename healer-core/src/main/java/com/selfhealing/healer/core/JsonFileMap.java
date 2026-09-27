package com.selfhealing.healer.core;

import com.fasterxml.jackson.databind.JavaType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A small string-keyed map persisted as a pretty-printed JSON file.
 *
 * <p>Safe for several JVMs sharing the file (e.g. Maven Surefire with {@code forkCount > 1}): every write
 * takes an OS file lock, re-reads the file, applies only its own change and writes atomically - so entries
 * written by other processes in the meantime are kept.
 */
public class JsonFileMap<V> {

    private final Path file;
    private final Path lockFile;
    private final JavaType type;
    private Map<String, V> entries;

    public JsonFileMap(Path file, Class<V> valueType) {
        this.file = file;
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
        this.type = Json.MAPPER.getTypeFactory().constructMapType(TreeMap.class, String.class, valueType);
        this.entries = read();
    }

    private Map<String, V> read() {
        if (!Files.exists(file)) return new TreeMap<>();
        try {
            return Json.MAPPER.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public synchronized Optional<V> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public synchronized void put(String key, V value) {
        update(m -> m.put(key, value));
    }

    public synchronized void remove(String key) {
        if (entries.containsKey(key) || Files.exists(file)) update(m -> m.remove(key));
    }

    /** A snapshot of all entries. */
    public synchronized Map<String, V> all() {
        return new java.util.LinkedHashMap<>(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Lock, merge with what is on disk now, apply the change, write atomically. */
    private void update(Consumer<Map<String, V>> change) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Map<String, V> current = read();
                change.accept(current);
                Path tmp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
                Json.MAPPER.writeValue(tmp.toFile(), current);
                entries = current;
                replace(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /**
     * Moves the new file into place. On Windows a virus scanner, OneDrive or an IDE may hold the file for a moment
     * (AccessDeniedException): retried, and if it stays locked the change is kept in memory - it is written with the
     * next change - instead of failing the test.
     */
    private void replace(Path tmp) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.FileSystemException locked) {
                if (attempt >= 10) {
                    Files.deleteIfExists(tmp);
                    System.out.println("[healer] could not save " + file + " (" + locked.getClass().getSimpleName()
                            + " - is another program holding it?); kept in memory and saved with the next change");
                    return;
                }
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw locked;
                }
            }
        }
    }
}
