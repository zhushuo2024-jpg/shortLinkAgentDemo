package com.example.jchatminddemo.controller;

import com.example.jchatminddemo.service.KnowledgeBaseService;
import com.example.jchatminddemo.tools.DateTimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.ai.document.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final DateTimeTools dateTimeTools;
    private final KnowledgeBaseService knowledgeBaseService;

    public ChatController(ChatClient.Builder builder, DateTimeTools dateTimeTools,KnowledgeBaseService knowledgeBaseService) {
        this.chatClient = builder.build();
        this.dateTimeTools = dateTimeTools;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        // 1. RAG 检索实现 prompt 增强
        List<Document> knowledge = knowledgeBaseService.getVectorStore().similaritySearch(message);

        // 接收命中内容
        String context = "";
        if (!knowledge.isEmpty()) {
            context = knowledge.get(0).getText();
            System.out.println(">>> RAG 检索命中");
        } else {
            System.out.println(">>> RAG 无命中");
        }

        String finalPrompt = """
            你是一个智能助手。请严格根据以下【背景知识】来回答用户的问题。
            如果【背景知识】中没有相关信息，请直接说“我不知道”，绝对不要自己编造。
            
            【背景知识】:
            %s
            
            【用户问题】:
            %s
            """.formatted(context, message);

        // 2. 初始化对话历史
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(finalPrompt));

        // 3. 初始化请求选项，把工具传进去（注意最后要 .build()）
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(dateTimeTools))
                .build(); // 补上 .build()

        int maxSteps = 5, currentStep = 0;

        // 4. 手动 Agent Loop
        while (currentStep < maxSteps) {
            currentStep++;
            System.out.println(">>> Start loops " + currentStep);

            Prompt prompt = new Prompt(messages, options);
            ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();
            Generation generation = chatResponse.getResult();
            AssistantMessage assistantMessage = generation.getOutput();

            // 把模型当前回复加入历史
            messages.add(assistantMessage);

            // 5. 判断是否有工具调用
            if (assistantMessage.hasToolCalls()) {
                System.out.println(">>> Using tool calls");
                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

                // 注意这里泛型是 ToolResponse，不是 ToolResponseMessage
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

                for (AssistantMessage.ToolCall toolCall : toolCalls) {
                    if ("getCurrentDateTime".equals(toolCall.name())) {
                        System.out.println(">>> Executing tool: " + toolCall.name());
                        String res = dateTimeTools.getCurrentDateTime();

                        toolResponses.add(new ToolResponseMessage.ToolResponse(
                                toolCall.id(),
                                toolCall.name(),
                                res
                        ));
                    }
                }

                // 必须在 for 循环外面，把整批工具执行结果加入历史
                messages.add(new ToolResponseMessage(toolResponses, Map.of()));

            } else {
                // 6. 重要！如果没有工具调用，说明模型直接给出了最终回答，直接 return
                System.out.println(">>> 模型给出最终回答，结束循环。");
                return assistantMessage.getText();
            }
        }

        return "达到最大执行轮数 (" + maxSteps + ")，强制中断，防止死循环。";
    }
}