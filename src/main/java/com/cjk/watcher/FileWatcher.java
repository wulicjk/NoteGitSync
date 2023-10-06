package com.cjk.watcher;

import com.cjk.fileOperation.FileMoveDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

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
    private final Logger log = LoggerFactory.getLogger(FileWatcher.class);


    public FileWatcher(Path rootPath) {
        FileWatcher.rootPath = rootPath;
        registerAll(rootPath);
    }

    @SuppressWarnings("InfiniteLoopStatement")
    public void start(Semaphore fileChangeEvent) {
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
                handleEvent(fileChangeEvent, event, key, fileMoveDetector);
            }
            key.reset();
        }
    }

    private void handleEvent(Semaphore fileChangeSemaphore, WatchEvent<?> event, WatchKey key, FileMoveDetector fileMoveDetector) {
        Path parentPath = (Path) key.watchable();
        Path changedFileName = (Path) event.context();

        //排除不想监听的文件
        if (isExcludedFile(changedFileName))
            return;

        WatchEvent.Kind<?> kind = event.kind();
        fileChangeSemaphore.release();
        int changeCount = fileChangeSemaphore.availablePermits();
        switch (kind.toString()) {
            case "ENTRY_CREATE":
                fileMoveDetector.OnCreate(event, parentPath);
                log.info("File created: " + changedFileName + " 当前待提交更改数: " + changeCount);
                break;
            case "ENTRY_MODIFY":
                log.info("File Modified: " + changedFileName + " 当前待提交更改数: " + changeCount);
                break;
            case "ENTRY_DELETE":
                fileMoveDetector.OnDelete(event, parentPath);
                log.info("File deleted: " + changedFileName + " 当前待提交更改数: " + changeCount);
                break;
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
        return Arrays.stream(excludedFiles).anyMatch(fileName::contains);
    }

    private static boolean isSubPath(Path basePath, Path path) {
        return path.startsWith(basePath);
    }

    public void close() throws IOException {
        watchService.close();
    }
}
