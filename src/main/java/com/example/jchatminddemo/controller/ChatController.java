package com.example.jchatminddemo.controller;

import com.example.jchatminddemo.service.KnowledgeBaseService;
import com.example.jchatminddemo.tools.DateTimeTools;
import com.example.jchatminddemo.tools.ShortLinkTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final DateTimeTools dateTimeTools;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ShortLinkTools shortLinkTools;
    public ChatController(ChatClient.Builder builder,
                          DateTimeTools dateTimeTools,
                          ShortLinkTools shortLinkTools,
                          KnowledgeBaseService knowledgeBaseService) {
        this.chatClient = builder.build();
        this.dateTimeTools = dateTimeTools;
        this.shortLinkTools = shortLinkTools;
        this.knowledgeBaseService = knowledgeBaseService;

    }

    // ==========================================
    // 1. 原有的同步阻塞接口（保留用于调试和对比）
    // ==========================================
    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        List<Document> knowledge = knowledgeBaseService.getVectorStore().similaritySearch(message);
        String context = knowledge.isEmpty() ? "" : knowledge.get(0).getText();

        String finalPrompt = """
                你是一个智能助手。请严格根据以下【背景知识】来回答用户的问题。
                如果【背景知识】中没有相关信息，请直接说“我不知道”，绝对不要自己编造。
                【背景知识】:
                %s
                【用户问题】:
                %s
                """.formatted(context, message);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(finalPrompt));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(dateTimeTools,shortLinkTools))
                .build();

        int maxSteps = 5, currentStep = 0;

        while (currentStep < maxSteps) {
            currentStep++;
            Prompt prompt = new Prompt(messages, options);
            ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();

            // 同步调用一般不会有空块，但为了安全也加上判空
            if (chatResponse.getResult() == null) break;

            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            messages.add(assistantMessage);

            if (assistantMessage.hasToolCalls()) {
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    if ("getCurrentDateTime".equals(toolCall.name())) {
                        String res = dateTimeTools.getCurrentDateTime();
                        toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), res));
                    }
                }
                messages.add(new ToolResponseMessage(toolResponses, Map.of()));
            } else {
                return assistantMessage.getText();
            }
        }
        return "达到最大执行轮数，强制中断。";
    }

    // ==========================================
    // 2. 新增的 SSE 流式接口（打字机效果 + 状态推送）
    // ==========================================
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5分钟超时

        // 新线程处理耗时逻辑，防止阻塞 Tomcat 工作线程
        new Thread(() -> {
            try {
                // 1. RAG 检索
                emitter.send(SseEmitter.event().name("status").data("正在检索知识库..."));
                List<Document> knowledge = knowledgeBaseService.getVectorStore().similaritySearch(message);
                String context = "";
                if (!knowledge.isEmpty()) {
                    context = knowledge.get(0).getText();
                    emitter.send(SseEmitter.event().name("status").data("知识库命中！"));
                } else {
                    emitter.send(SseEmitter.event().name("status").data("未命中知识库。"));
                }

                String finalPrompt = """
                        你是一个专业的【短链系统智能运维助手】。
                        你的职责是解答关于短链系统架构的问题（参考背景知识），并帮助用户查询短链的实时访问量和系统健康状态。
                        如果用户问时间，请调用工具。如果用户问数据，请调用工具。
                        如果不知道，请直接说不知道。
                        
                        【背景知识】:
                        %s
                        
                        【用户问题】:
                        %s
                        """.formatted(context, message);

                List<Message> messages = new ArrayList<>();
                messages.add(new UserMessage(finalPrompt));

                ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                        .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(dateTimeTools,shortLinkTools))
                        .build();

                int maxSteps = 5, currentStep = 0;

                // 2. Agent Loop
                while (currentStep < maxSteps) {
                    currentStep++;

                    // 使用 .stream() 获取响应式流
                    Flux<ChatResponse> responseFlux = chatClient.prompt()
                            .messages(messages)
                            .options(options)
                            .stream()
                            .chatResponse();

                    StringBuilder fullResponseText = new StringBuilder();
                    AssistantMessage[] finalAssistantMsg = new AssistantMessage[1];

                    // 遍历流式数据块
                    responseFlux.toStream().forEach(chatResponse -> {
                        // 【关键修复】：判空保护，流式中会有空块
                        if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
                            return; // 跳过这个空块
                        }

                        AssistantMessage output = chatResponse.getResult().getOutput();
                        finalAssistantMsg[0] = output;

                        String text = output.getText();
                        if (text != null && !text.isEmpty()) {
                            fullResponseText.append(text);
                            try {
                                // 推给前端：增量文本
                                emitter.send(SseEmitter.event().name("message").data(text));
                            } catch (IOException e) {
                                throw new RuntimeException("SSE 发送失败", e);
                            }
                        }
                    });

                    // 如果整轮下来没拿到有效 AssistantMessage，直接中断
                    if (finalAssistantMsg[0] == null) {
                        emitter.send(SseEmitter.event().name("error").data("模型返回了空响应"));
                        emitter.complete();
                        return;
                    }

                    AssistantMessage assistantMessage = finalAssistantMsg[0];
                    messages.add(assistantMessage);

                    // 3. 工具调用判断
                    if (assistantMessage.hasToolCalls()) {
                        emitter.send(SseEmitter.event().name("status").data("正在调用工具..."));
                        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

                        for (AssistantMessage.ToolCall toolCall : toolCalls) {
                            if ("getCurrentDateTime".equals(toolCall.name())) {
                                String res = dateTimeTools.getCurrentDateTime();
                                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), res));
                                emitter.send(SseEmitter.event().name("status").data("工具执行完毕，准备总结..."));
                            }else if("getShortLink".equals(toolCall.name())) {
                                String res = shortLinkTools.getShortLinkStats("abc123");
                                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), res));

                            }else if("getSystemHealth".equals(toolCall.name())) {
                                String res = shortLinkTools.getSystemHealth();
                                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), res));

                            }

                        }
                        messages.add(new ToolResponseMessage(toolResponses, Map.of()));
                    } else {
                        // 没有工具调用，说明大模型已经给出了最终回答，结束循环
                        break;
                    }
                }

                // 推送完成事件并关闭连接
                emitter.send(SseEmitter.event().name("done").data("生成结束"));
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.completeWithError(e);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }).start();

        return emitter;
    }
}