package com.example.jchatminddemo.controller;

import com.example.jchatminddemo.tools.DateTimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {
    private final ChatClient chatClient;
    private final DateTimeTools dateTimeTools;
    public ChatController(ChatClient.Builder chat,DateTimeTools dateTimeTools){
        this.chatClient = chat.build();
        this.dateTimeTools = dateTimeTools;

    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message){
        try{
            return chatClient.prompt().user(message)
                    .tools(dateTimeTools)
                    .call()
                    .content();

        }catch (Exception e){
            e.printStackTrace();
            return "错误"+e.getMessage();

        }

    }
}
