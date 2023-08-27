package com.cjk.watcher;

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
    private final Path rootPath;
    private final WatchService watchService = FileSystems.getDefault().newWatchService();
    private final String[] excludedFiles = new String[]{".git", ".~"};
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
                Path sourceParentPath;
                //线程同步的标志
                int unCommitedCount = fileChangeCount.getAndIncrement();
                fileChangeEvent.release();
                switch (kind.toString()) {
                    case "ENTRY_CREATE":
                        //判断是创建文件还是移动文件
                        sourceParentPath = delMap.get(changedFileName.toString());
                        Path targetParentPath = (Path) key.watchable();
                        //map中存入了path且没过期
                        if (sourceParentPath != null) {
                            //扫描创建的文件并拿到文件名
                            Path currentFilePath = targetParentPath.resolve(changedFileName);
                            File currentFile = new File(currentFilePath.toString());
                            String regex = "!\\[.*]\\(assets/([^)]+)\\)";
                            StringBuilder fileContent = new StringBuilder();
                            List<String> moveFilesList = new ArrayList<>();
                            try (BufferedReader br = new BufferedReader(new FileReader(currentFile))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    fileContent.append(line).append("\n");
                                }
                                Pattern pattern = Pattern.compile(regex);
                                Matcher matcher = pattern.matcher(fileContent);
                                while (matcher.find()) {
                                    String foundText = matcher.group(1);  // 获取捕获组中的内容
                                    moveFilesList.add(foundText);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            //有图片要移动时，判断target目录是否存在
                            if (!moveFilesList.isEmpty()) {
                                if (!Files.exists(targetParentPath.resolve("assets"))) {
                                    try {
                                        Files.createDirectory(targetParentPath.resolve("assets"));
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            }
                            //移动图片
                            for (String imageFileName : moveFilesList) {
                                // 源文件路径
                                Path sourcePath = Paths.get(sourceParentPath.resolve("assets").resolve(imageFileName).toString());
                                // 目标目录路径
                                Path targetPath = Paths.get(targetParentPath.resolve("assets").resolve(imageFileName).toString());
                                try {
                                    if (Files.exists(sourcePath)) {
                                        // 如果目标位置已经存在同名文件，先删除它
                                        if (Files.exists(targetPath)) {
                                            Files.delete(targetPath);
                                            log.info("已删除目标位置上的同名文件");
                                        }
                                        Files.move(sourcePath, targetPath);
                                        log.info("File move:" + sourcePath + "--->" + targetPath);
                                    } else {
                                        log.info("文件移动失败，" + sourcePath + "不存在");
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    log.info("文件移动失败：" + e.getMessage());
                                }
                            }
                        } else {
                            //添加新创建的文件到监听器
                            registerAll(targetParentPath.resolve(changedFileName));
                        }
                        //提交保存到git
                        log.info("File created: " + changedFileName + " 当前待提交更改数: " + unCommitedCount);
                        break;
                    case "ENTRY_MODIFY":
                        //提交保存到git
                        log.info("File Modified: " + changedFileName + " 当前待提交更改数: " + unCommitedCount);
                        break;
                    case "ENTRY_DELETE":
                        //图片存放地址
                        sourceParentPath = (Path) key.watchable();
                        delMap.put(changedFileName.toString(), sourceParentPath);
                        //提交保存到git
                        log.info("File deleted: " + changedFileName + " 当前待提交更改数: " + unCommitedCount);
                        break;
                }
            }
            key.reset();
        }
    }

    private void registerAll(final Path start) {
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

    private boolean isSubPath(Path basePath, Path path) {
        return path.startsWith(basePath);
    }

    public void close() throws IOException {
        delMap.shutdown();
        watchService.close();
    }
}
