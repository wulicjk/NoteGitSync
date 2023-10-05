package com.cjk.fileOperation;

import com.cjk.tools.ExpiringConcurrentHashMap;
import com.cjk.watcher.FileWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows和Mac在文件移动时，文件操作事件的触发顺序不同。
 * 实现一个与事件下发顺序无关的文件移动检测器
 */
public class FileMoveDetector {
    private List<Event> eventList = new ArrayList<>();
    private final Logger log = LoggerFactory.getLogger(FileMoveDetector.class);

    public void OnCreate(WatchEvent<?> event, Path targetParentPath) {
        Event fileEvent = new Event(event, new Date(), targetParentPath);
        eventList.add(fileEvent);
        Detect();
    }

    public void OnDelete(WatchEvent<?> event, Path sourceParentPath) {
        Event fileEvent = new Event(event, new Date(), sourceParentPath);
        eventList.add(fileEvent);
        Detect();
    }

    //检测是否有文件移动
    public void Detect() {
        Collections.sort(eventList, new EventComparator());
        long current = System.currentTimeMillis();
        int i = 0;
        while (i < eventList.size() - 1) {
            Event currentEvent = eventList.get(i);
            Event nextEvent = eventList.get(i + 1);

            if (current - currentEvent.getDate().getTime() > 1000) {
                //如果是createEvent则添加新创建的文件到监听器，deleteEvent不进行处理
                WatchEvent<?> event = currentEvent.getEvent();
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    FileWatcher.registerAll(currentEvent.getParentPath().resolve((Path) event.context()));
                }
                eventList.remove(i);
                continue;
            }

            if (currentEvent.getEvent().context().equals(nextEvent.getEvent().context())) {
                // 如果相邻的两个事件的 context 相同，则执行文件移动处理,前一个event是deleteEvent
                Path changedFileName = (Path) currentEvent.getEvent().context();
                fileMoveProcess(currentEvent.getParentPath(), nextEvent.getParentPath(), changedFileName);
                //移除这两个事件
                eventList.remove(i);
                eventList.remove(i);
                continue;
            }

            i++;
        }
    }

    private void fileMoveProcess(Path sourceParentPath, Path targetParentPath, Path changedFileName) {
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

    }

    public class EventComparator implements Comparator<Event> {
        @Override
        public int compare(Event event1, Event event2) {
            // 首先按照context进行排序
            String context1 = event1.getEvent().context().toString();
            String context2 = event2.getEvent().context().toString();
            int contextComparison = context1.compareTo(context2);

            if (contextComparison != 0) {
                // 如果context不相等，则返回context的比较结果
                return contextComparison;
            } else {
                // 如果context相等，则按照kind进行排序
                WatchEvent.Kind<?> kind1 = event1.getEvent().kind();
                WatchEvent.Kind<?> kind2 = event2.getEvent().kind();

                // 将delete类型的排在create类型的前面
                if (kind1 == StandardWatchEventKinds.ENTRY_DELETE && kind2 == StandardWatchEventKinds.ENTRY_CREATE) {
                    return -1;
                } else if (kind1 == StandardWatchEventKinds.ENTRY_CREATE && kind2 == StandardWatchEventKinds.ENTRY_DELETE) {
                    return 1;
                } else {
                    // 其他情况返回kind的比较结果
                    String kind1Str = kind1.toString();
                    String kind2Str = kind2.toString();
                    return kind1Str.compareTo(kind2Str);
                }
            }
        }
    }
}
