package com.example.jchatminddemo.tools;


import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateTimeTools {
    @Tool(description = "获取系统日期和时间")
    public String getCurrentDateTime(){
        return LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")
        );
    }
}
