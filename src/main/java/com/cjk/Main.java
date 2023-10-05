package com.cjk;

import com.cjk.watcher.FileWatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.core.util.StatusPrinter;
import ch.qos.logback.classic.LoggerContext;

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
    private static final Logger log;

    //设置logback读取配置文件的路径
    static {
//        System.setProperty("logback.configurationFile", "./logback.xml");
        log = LoggerFactory.getLogger(Main.class);
    }

    public static void main(String[] args) throws IOException {
//        // 获取 LoggerContext 实例
//        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
//
//        // 设置占位符的实际值
//        loggerContext.putProperty("log.file.path", "/Users/cjk/Documents/logs/NoteGitSync/info.log");
//
//        // 打印 Logback 内部状态
//        StatusPrinter.print(loggerContext);
//
//        System.out.println("log.file.path: " + loggerContext.getProperty("log.file.path"));


        String currentWorkingDir = System.getProperty("user.dir");
        System.out.println("当前工作目录：" + currentWorkingDir);
        String configFilePath = "config.properties"; // 配置文件对相对路径（相对于当前工作目录）
        Properties properties = new Properties();
        try {
            FileInputStream fis = new FileInputStream(configFilePath);
            properties.load(fis);
            fis.close();

            //判断当前的操作系统
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                System.out.println("当前系统是Windows");
                NoteDirectoryPath = properties.getProperty("WinNoteDirectoryPath");
                GitExeLocation = properties.getProperty("WinGitExeLocation");
                CommitInfo = properties.getProperty("WinCommitInfo");
                GitBranch = properties.getProperty("WinGitBranch");
                sleepTime = Long.parseLong(properties.getProperty("WinSleepTime"));
            } else if (os.contains("mac")) {
                System.out.println("当前系统是Mac");
                NoteDirectoryPath = properties.getProperty("MacNoteDirectoryPath");
                GitExeLocation = properties.getProperty("MacGitExeLocation");
                CommitInfo = properties.getProperty("MacCommitInfo");
                GitBranch = properties.getProperty("MacGitBranch");
                sleepTime = Long.parseLong(properties.getProperty("MacSleepTime"));
            } else {
                System.out.println("当前系统不是Windows也不是Mac");
                return;
            }
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
                // set to zero
                fileChangeEvent.drainPermits();
                pushGit();
            }
            try {
                fileChangeEvent.acquire();
                // 合并指定时间内的文件变化一起push
                TimeUnit.SECONDS.sleep(sleepTime);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
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
