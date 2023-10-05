package com.cjk.watcher;

import com.cjk.fileOperation.FileMoveDetector;
import com.cjk.tools.ExpiringConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by cjk on 2023/8/18.
 **/
public class FileWatcher {
    private static Path rootPath = null;
    private static final WatchService watchService;

    static {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final String[] excludedFiles = new String[]{".git", ".~", ".DS_Store", ".png"};
    //<文件名，图片父路径>
    private final ExpiringConcurrentHashMap<String, Path> delMap = new ExpiringConcurrentHashMap<>();
    private final Logger log = LoggerFactory.getLogger(FileWatcher.class);


    public FileWatcher(Path rootPath) throws IOException {
        this.rootPath = rootPath;
        registerAll(rootPath);
    }

    @SuppressWarnings("InfiniteLoopStatement")
    public void start(AtomicInteger fileChangeCount, Semaphore fileChangeEvent) {
        WatchKey key;
        FileMoveDetector fileMoveDetector = new FileMoveDetector();
        while (true) {
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            //处理事件
            for (WatchEvent<?> event : key.pollEvents()) {
                //todo 这里不睡的话会打印出来很多东西，也就是触发了很多事件
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                Path changedFileName = (Path) event.context();
                //排除不想监听的文件
                if (isExcludedFile(changedFileName)) continue;
                //处理事件
                WatchEvent.Kind<?> kind = event.kind();
                //线程同步的标志
                int unCommitedCount = fileChangeCount.incrementAndGet();
                fileChangeEvent.release();
                switch (kind.toString()) {
                    case "ENTRY_CREATE":
                        Path targetParentPath = (Path) key.watchable();
                        fileMoveDetector.OnCreate(event, targetParentPath);
                        //提交保存到git
                        log.info("File created: " + changedFileName + " 当前待提交更改数: " + unCommitedCount);
                        break;
                    case "ENTRY_MODIFY":
                        //提交保存到git
                        log.info("File Modified: " + changedFileName + " 当前待提交更改数: " + unCommitedCount);
                        break;
                    case "ENTRY_DELETE":
                        //图片存放地址
                        Path sourceParentPath = (Path) key.watchable();
                        fileMoveDetector.OnDelete(event, sourceParentPath);
                        //提交保存到git
                        log.info("File deleted: " + changedFileName + " 当前待提交更改数: " + unCommitedCount);
                        break;
                }
            }
            key.reset();
        }
    }

    public static void registerAll(final Path start) {
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    //不监控.git的子文件
                    if (!isSubPath(rootPath.resolve(".git"), dir)) {
                        dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isExcludedFile(Path filePath) {
        String fileName = filePath.getFileName().toString();
        for (String s : excludedFiles) {
            if (fileName.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSubPath(Path basePath, Path path) {
        return path.startsWith(basePath);
    }

    public void close() throws IOException {
        delMap.shutdown();
        watchService.close();
    }
}
