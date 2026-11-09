package com.example.jchatminddemo.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class ShortLinkTools {
    private final Random random = new Random();

    //模拟短链系统里查询数据的方法（真实环境里，这里会注入 RedisTemplate 或查询 MySQL
    @Tool(description = "根据短链的Code查询该短链的今日访问量（PV）和独立访客数（UV")
    public  String getShortLinkStats(String code){
        System.out.println("[INFO] Searching database");//实际查询数据库和redis
        //模型拆查询数据
        int pv = 1000 + random.nextInt(9000);
        int uv = pv / 3;
        return String.format("短链 [%s] 今日数据：PV = %d，UV = %d。", code, pv, uv);

    }

    @Tool(description = "查询当前短链系统Redis缓存的命中率以及 RocketMQ 的消息堆积情况")
    public String getSystemHealth() {
        System.out.println(">>> [业务工具] 正在查询系统健康状态");
        // 模拟生成数据
        double hitRate = 98.5 + random.nextDouble();
        int mqLag = random.nextInt(100);
        return String.format("当前 Redis 缓存命中率: %.2f%%，RocketMQ 消息堆积量: %d 条。", hitRate, mqLag);
    }


}
