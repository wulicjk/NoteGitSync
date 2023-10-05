package com.cjk.fileOperation;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.Date;
@AllArgsConstructor
@Data
public class Event {
    private WatchEvent<?> event;
    private Date date;
    //该事件所在文件的父文件路径
    private Path parentPath;
}
