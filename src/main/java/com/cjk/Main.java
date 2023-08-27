package com.cjk;

import com.cjk.watcher.FileWatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by cjk on 2023/8/16.
 **/
public class Main {
    private static String NoteDirectoryPath;
    private static String GitExeLocation;

    private static String CommitInfo;
    private static String GitBranch;
    private static long sleepTime;
    private static final AtomicInteger fileChangeCount = new AtomicInteger(0);
    private static final Semaphore fileChangeEvent = new Semaphore(0);
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        String configFilePath = "config.properties"; // 相对路径
        Properties properties = new Properties();
        try {
            FileInputStream fis = new FileInputStream(configFilePath);
            properties.load(fis);
            fis.close();
            // 读取属性值
            NoteDirectoryPath = properties.getProperty("NoteDirectoryPath");
            GitExeLocation = properties.getProperty("GitExeLocation");
            CommitInfo = properties.getProperty("CommitInfo");
            GitBranch = properties.getProperty("GitBranch");
            sleepTime = Long.parseLong(properties.getProperty("sleepTime"));
        } catch (IOException e) {
            if (e instanceof java.io.FileNotFoundException) {
                System.out.println("Config file not found.");
            } else {
                e.printStackTrace();
            }
        }

        FileWatcher fileWatcher = new FileWatcher(Paths.get(NoteDirectoryPath));
        new Thread(() -> {
            fileWatcher.start(fileChangeCount, fileChangeEvent);
        }).start();

        //push到github
        new Thread(Main::commitThread).start();

        System.out.println("正在监控文件: " + NoteDirectoryPath);
        System.out.println("输入任意字符退出...");
        System.in.read();
        fileWatcher.close();
    }

    private static void commitThread() {
        while (true) {
            if (fileChangeCount.getAndSet(0) > 0) {
                //acquire所有的信号量
                fileChangeEvent.drainPermits();
                pushGit();
            }
            try {
                //合并30s内的文件变化一起push
                TimeUnit.SECONDS.sleep(sleepTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static void pushGit() {
        log.info("start push!");
        runGitCommand(GitExeLocation, "add", "-A");
        runGitCommand(GitExeLocation, "commit", "-m", "\"" + CommitInfo + "\"");
        runGitCommand(GitExeLocation, "pull", "origin", GitBranch);
        runGitCommand(GitExeLocation, "push", "-u", "origin", GitBranch);
        log.info("push success!");
    }

    private static void runGitCommand(String... arguments) {
        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        processBuilder.directory(new File(NoteDirectoryPath));
        processBuilder.redirectErrorStream(true); // 合并标准输出和错误输出流
        try {
            Process process = processBuilder.start();
            //获取git命令执行结果
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                log.info(line);
            }
            process.waitFor();
        } catch (IOException e) {
            log.error("Error executing git command", e);
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
