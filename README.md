# JchatMind 智能agent系统

这是一个最基础的agent项目，了解一个基本的agent开发的各种功能：

**基于 Spring AI 的短链系统智能运维 Agent**

- 基于 Spring Boot 3 \+ Spring AI 搭建，接入 Claude 模型，实现自然语言驱动的短链系统运维与知识问答。

- **RAG 检索**：读取本地 Markdown 架构文档，使用 Ollama `bge-m3` 进行向量化，基于 PostgreSQL \(`pgvector`\) 实现 HNSW 索引的持久化向量存储，支撑私有知识问答。

- **Agent Loop**：手动接管 LLM 的工具调用循环（Think\-Execute），基于状态机实现多轮工具调用，并加入最大步数限制防止 Token 耗尽。

- **工具生态**：封装了时间查询、短链 PV/UV 统计、系统健康度查询等 3 个 `@Tool`，大模型可根据用户意图自主路由调用。

- **SSE 流式交互**：基于 `SseEmitter` 和 Reactor `Flux` 实现逐字流式输出，并通过自定义事件推送 Agent 执行状态（检索中、工具调用中），实现 Agent 思考过程的可视化。

# 新建一个java agent项目

第一步：新建工程 \(Spring Initializr\)

1. 打开 IDEA，点击 `File` \-\> `New` \-\> `Project...`

2. 左侧选择 Spring Boot。

3. 配置基础信息：

    - Name: `shortlink-agent` \(或者你喜欢的名字\)

    - Location: 选你的代码存放目录

    - Language: `Java`

    - Type: `Maven`

    - Group: `com.example` \(或者你的专属前缀，如 `com.yourname`\)

    - Artifact: `shortlink-agent`

    - JDK: 选 17 或 21（必须是 17 及以上，因为 Spring AI 强依赖高版本）

    - Java: 选对应的 17 或 21

    - Packaging: `Jar`

4. 点击 Next。

5. 选择 Spring Boot 版本：

    - 选择 3\.3\.x 或 3\.4\.x（千万不要选带 SNAPSHOT 或 M 后缀的版本，选最新的稳定版即可）。

    - Dependencies（依赖）：在左侧栏只勾选一个 `Spring Web`（在 Web 分类下）。

6. 点击 Create。



引入spring ai

```Plain Text
<properties>
    <java.version>17</java.version>
    <spring-ai.version>1.0.0</spring-ai.version>
</properties>
```

引入spring oi的BOM

```XML
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

引入claude code的Anthropic 依赖：


```XML
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
    <version>1.0.0</version>
</dependency>
```

指定版本号不然会报错





# 一个带有工具类的最基本agent写法

定义Controller  用Spirng的GetMapping注解处理/chat请求

```Java
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
```

实例化一个chatClient对象  将接收来的用户消息作为prompt之一调用接口返回内容

在此版本中，工具类作为提前写好的对象被实例化

工具类的书写与注册

```Java
package com.example.jchatminddemo.tools;


import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateTimeTools {
    @Tool(description = "获取系统日期和时间")
    public String getCurrentDateTime(){
        return LocalDateTime.*now*().format(
                DateTimeFormatter.*ofPattern*("yyyy-MM-dd-HH:mm:ss")
        );
    }
}
```

这个工具类的书写要点

1\.在类上面有@Component组件，此组件的作用是：告诉spring，这个类交给容器管理，像@Service、@Repository、@Controller

都是它的派生注解，功能一样，只是语义更明确。@Repository 额外多一个异常转换

2\.defaultTools\(\.\.\.\) / \.tools\(\.\.\.\) 需要一个工具对象的实例。加了 @Component，这个实例由容器创建，可以直接注入到配置类或 Service 里使用。

- 工具本身需要依赖：真实场景里工具要调数据库、HTTP 客户端、其他 Service。只有成为 Bean，这些依赖才能被自动注入。如果自己 new WeatherTools\(\)，里面的 @Autowired 字段就是 null。

- 享受容器能力：事务、AOP 日志、权限、配置注入这些对工具方法同样生效。

    需要说明的是，@Tool 本身不依赖 @Component。Spring AI 是通过反射读取你传进去的那个对象上的 @Tool 方法，所以你自己 new 一个对象传给\.tools\(new DateTimeTools\(\)\) 也能工作，前提是这个工具不需要 Spring 注入任何东西。另一种写法是用 @Bean 声明

    Function/ToolCallback，然后按 Bean 名称引用，这种方式同样依赖容器。

# 不使用框架手动管理循环

```Java
package com.example.jchatminddemo.controller;

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

import java.util.ArrayList;
import java.util.List;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final DateTimeTools dateTimeTools;

    public ChatController(ChatClient.Builder builder, DateTimeTools dateTimeTools) {
        this.chatClient = builder.build();
        this.dateTimeTools = dateTimeTools;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        // 1. 初始化对话历史
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(message));

        // 2. 初始化请求选项，把工具传进去
        ToolCallingChatOptions options = ToolCallingChatOptions.*builder*()
                .toolCallbacks(org.springframework.ai.support.ToolCallbacks.*from*(dateTimeTools))
                .build();

        int maxSteps = 5; // 最大循环次数，防止死循环耗尽 Token
        int currentStep = 0;

        while (currentStep < maxSteps) {
            currentStep++;
            System.*out*.println(">>> [Agent Loop] 开始第 " + currentStep + " 轮思考...");

            // 3. 发起请求
            Prompt prompt = new Prompt(messages, options);
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
            Generation generation = response.getResult();
            AssistantMessage assistantMessage = generation.getOutput();

            // 4. 把模型的回复加入历史
            messages.add(assistantMessage);

            // 5. 检查模型是否要求调用工具
            if (assistantMessage.hasToolCalls()) {
                System.*out*.println(">>> [Agent Loop] 模型决定调用工具！");
                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : toolCalls) {
                    // 这里简单硬编码一下，如果你后面有多个工具，这里需要一个路由分发
                    if ("getCurrentDateTime".equals(toolCall.name())) {
                        System.*out*.println(">>> [Agent Loop] 正在执行工具: " + toolCall.name());
                        String result = dateTimeTools.getCurrentDateTime();
                        toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result));
                    }
                }

                // 6. 把工具执行结果塞回对话历史，准备进入下一轮
                messages.add(ToolResponseMessage.builder()
                        .responses(toolResponses)
                        .build());
                System.*out*.println(">>> [Agent Loop] 工具执行完毕，结果已追加。");
            } else {
                // 7. 没有工具调用，说明模型已经得出了最终结论，退出循环
                System.*out*.println(">>> [Agent Loop] 模型给出了最终回答，结束循环。");
                return assistantMessage.getText();
            }
        }

        return "达到最大执行轮数 (" + maxSteps + ")，强制中断，防止死循环。";
    }
}
```

手动管理循环需要关闭自动执行工具，`application.yml`，在 `spring.ai.openai.chat.options` 下面加上这一行配置

```YAML
spring:
  ai:
    openai:
      # ... 之前的配置 ...
      chat:
        options:
          # ...
          # 关闭框架内部的自动工具执行
          internal-tool-execution-enabled: false
```

# 一个简单的RAG入门

1\.CC 的Anthropic不支持 embedding化，需要调用其他模型的embedding模型，本文采用ollama 制作向量数据库

用ollama 拉取 bge\-m3

```Plain Text
ollama pull bge-m3
```

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZWMxNGZmODY3ZTY3ZWNlODMwMWYzNjMxMzEyMGFlYzFfN2ZhNzk0ODFlZTk0ZTlhM2ZjNDc2MzRhNGZjNjhjMjlfSUQ6NzcwMzkwMDI3Nzc5NTEzMDY3M18xNzk0MjExODU3OjE3OTQyOTgyNTdfVjM)

向量化词库

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MzEyM2EyOWQzNmYwM2EzYThhZTdmNWFhYWUyMTc4ZjBfZWFkZGJhOGUzMDhmYjc5MmQ0OTQxNTc3NjU4OWUyNzdfSUQ6NzcwMzkwMTI1MjAxNDM5NDQwMF8xNzk0MjExODU3OjE3OTQyOTgyNTdfVjM)

查看gpu使用情况

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZWE0MDQ1ZDQwN2FhNTNhZGU5ZTkyZjQ2ZDJjYzIyYmZfN2YxODI5MjVhYTkwZGJjNzU3NGZmNjA5NTUwMjI3OGRfSUQ6NzcwMzkwMTM2MTE5MjM3MzUyNl8xNzk0MjExODU3OjE3OTQyOTgyNTdfVjM)

将SpringAI接入ollama

```XML
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store</artifactId>
</dependency>
```

增加ollama配置修改 `application.yml`

```YAML

spring:
  application:
    name: jchat-mind-demo
  ai:
    anthropic:
      *# 你的中转地址，Spring AI 内部会自动补齐 /v1/messages*
*      *base-url: https://www.nbwcode.top/
      *# 优先从环境变量读取，如果没有配，可以直接替换成你的 sk-xxx token 进行测试*
*      *api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          *# 如果中转不支持这个模型，请去中转控制台看看它支持的模型名*
*          *model: claude-opus-5
          max-tokens: 1024
          internal-tool-execution-enabled: false
    ollama:
      base-url: http://localhost:11434
      embedding:
        options:
          model: bge-m3
```

由于配置冲突 修改依赖为

```XML
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.4</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    
    <groupId>com.example</groupId>
    <artifactId>jchat-mind-demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>jchat-mind-demo</name>
    <description>jchat-mind-demo</description>
    
    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.0.0</spring-ai.version>
    </properties>
    
    <!-- 必须在 dependencyManagement 中引入 BOM 统一管理版本 -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Web 基础依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring AI 核心及向量库抽象 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-vector-store</artifactId>
        </dependency>

        <!-- Anthropic (Claude) 模型接入 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-anthropic</artifactId>
        </dependency>

        <!-- Ollama 模型接入 (用于本地 Embedding) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
        </dependency>

        <!-- 测试依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

用Service来初始化每个client的rag项目

```Java
package com.example.jchatminddemo.service;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeBaseService {

    private final VectorStore vectorStore;

    public KnowledgeBaseService(EmbeddingModel embeddingModel) {
        this.vectorStore = SimpleVectorStore.*builder*(embeddingModel).build();
    }

    @PostConstruct
    public void init() {（个人项目）
        System.*out*.println(">>> [RAG] 正在读取本地 Markdown 文档...");

        try {
            // 方式一：直接用 String 路径（最简单）
            MarkdownDocumentReader reader = new MarkdownDocumentReader("classpath:docs/短链业务系统.md");

            // 方式二：如果要传 Resource，必须带上 config
            // ClassPathResource resource = new ClassPathResource("docs/短链业务系统.md");
            // MarkdownDocumentReader reader = new MarkdownDocumentReader(
            //         resource,
            //         MarkdownDocumentReaderConfig.defaultConfig()
            // );
            List<Document> documents = reader.get();

            // 3. 文本切分器
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(documents);

            System.*out*.println(">>> [RAG] 文档读取完毕，共切分为 " + splitDocuments.size() + " 个小块。");

            // 4. 存入向量库
            vectorStore.add(splitDocuments);

            System.*out*.println(">>> [RAG] 知识库初始化完成！");
        } catch (Exception e) {
            System.*err*.println(">>> [RAG] 知识库初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public VectorStore getVectorStore() {
        return this.vectorStore;
    }
}
```

增加rag检索能力

```Java
@GetMapping("/chat")
public String chat(@RequestParam String message) {
    // 1. 去向量库检索
    List<Document> similarDocuments = knowledgeBaseService.getVectorStore().similaritySearch(message);
    
    String context = "";
    if (!similarDocuments.isEmpty()) {
        context = similarDocuments.get(0).getText();
        System.out.println(">>> [RAG] 检索到了相关背景知识！内容片段：" + context.substring(0, Math.min(50, context.length())) + "...");
    } else {
        System.out.println(">>> [RAG] 未检索到相关背景知识。");
    }

    // 2. 把检索到的上下文和用户问题拼接
    String finalPrompt = """
            你是一个智能助手。请严格根据以下【背景知识】来回答用户的问题。
            如果【背景知识】中没有相关信息，请直接说“我不知道”，绝对不要自己编造。
            
            【背景知识】:
            %s
            
            【用户问题】:
            %s
            """.formatted(context, message);

    // 3. 打印出来，你自己看一眼，到底有没有把背景知识塞进去！
    System.out.println(">>> [发给大模型的最终 Prompt]:\n" + finalPrompt);

    // 4. 初始化对话历史
    List<Message> messages = new ArrayList<>();
    messages.add(new UserMessage(finalPrompt));

    // 5. 初始化请求选项，把工具传进去
    ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(dateTimeTools))
            .build();

    int maxSteps = 5;
    int currentStep = 0;

    while (currentStep < maxSteps) {
        currentStep++;
        // ... 下面保持你原本的 while 循环逻辑不变 ...
        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
        Generation generation = response.getResult();
        AssistantMessage assistantMessage = generation.getOutput();
        messages.add(assistantMessage);

        if (assistantMessage.hasToolCalls()) {
            // ... 工具调用逻辑 ...
        } else {
            return assistantMessage.getText();
        }
    }
    return "达到最大执行轮数，强制中断。";
}
```

# RAG系统进阶：持久化向量存储

在第一版的RAG系统当中，每次开启服务都需初始化内存向量库：

## 具体流程如下：

1\.服务启动 ，读取静态资源

2\.采用chunking策略切分文档

3\.将切分文档存入向量库

这样做每次开启服务都需要一次embedding的切分与向量化，且向量数据库运行在内存当中：

`SimpleVectorStore` 把所有向量存在 JVM 的内存里。Spring Boot 一关，内存清空，下次启动 `@PostConstruct` 又得重新读 md、调 Ollama 做 Embedding、再存一遍。如果文档有 100 页，启动会越来越慢。

换成 `PgVectorStore` 后，向量被写进 PostgreSQL 的一张表里。下次启动时，`@PostConstruct` 虽然还会跑，但我们可以改成“检测到库里已有数据就跳过灌入”，启动瞬间完成。后续检索直接查数据库，数据永久保留。



## 用docker拉取postgreSQL

```Plain Text
docker run -d \
  --name pgvector-db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=postgres \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

pom\.xml增加依赖

```XML
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

修改application\.yml

```YAML
spring:
  application:
    name: jchat-mind-demo
    *# ... 你的其他配置 ...*
*  *datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres
    password: postgres
  ai:
    *# 1. 全局指定只使用 Anthropic 作为聊天模型*
*    *model:
      chat: anthropic

    *# 2. Anthropic 的详细配置（与 model 平级）*
*    *anthropic:
      base-url: https://www.nbwcode.top/
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-opus-5   *# 注意：如果你中转不支持这个模型，请换成 claude-sonnet-4-5 等*
*          *max-tokens: 1024
          internal-tool-execution-enabled: false

    *# 3. Ollama 的配置（只用来做 Embedding，与 anthropic 平级）*
*    *ollama:
      base-url: http://localhost:11434
      embedding:
        options:
          model: bge-m3
```

自动建表失败，采用手动建表

```Plain Text
docker exec -it pgvector-db psql -U postgres -d postgres -c "CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS hstore; CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"; CREATE TABLE IF NOT EXISTS vector_store (id uuid DEFAULT uuid_generate_v4() PRIMARY KEY, content text, metadata jsonb, embedding vector(1024)); CREATE INDEX IF NOT EXISTS idx_vector_store_hnsw ON vector_store USING HNSW (embedding vector_cosine_ops);"
```

一些知识tips：

1. 比对内存版与持久化版的VectorStore

```Java
// 手动 new 出来一个基于内存的向量库
public KnowledgeBaseService(EmbeddingModel embeddingModel) {
    this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
}
```

```Java
// 直接依赖注入，由 Spring 根据 application.yml 自动装配 PgVectorStore
public KnowledgeBaseService(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
}
```

2. 向量存储与常规性关系存储的区别：

- 存储介质与结构

常规关系存储：存的是结构化数据（数字、字符串、日期），底层是 B\+ 树结构，按“主键/索引”精准查找。

向量存储：存的是高维浮点数组float\[\]（比如 `bge-m3` 是 1024 维，即 1024 个 float 数字）。它不是用来“精准匹配”的，而是用来计算距离的（余弦相似度、欧式距离）。

- 性能侧重点（面试高频）

常规存储：侧重 B\+ 树索引的精准查找（比如 `WHERE id = 123`），吞吐量高、延迟低。

向量存储：侧重 近似最近邻搜索（ANN，Approximate Nearest Neighbor）。因为如果有 100 万条 1024 维的向量，要计算目标向量与所有向量的距离，计算量极其巨大（全量计算是 O\(N\)）。所以工业界发明了 HNSW 索引（代码里配置的那个），它通过构建多层图结构，用空间换时间，将检索复杂度降到 O\(log N\)，极大提升检索性能。它的侧重点是牺牲极其微小的召回率精度，换取指数级的查询速度提升。

3. postgreSQL简介

postgreSQL 是纯正的“关系型数据库（RDBMS）”。但它是一个极其强大的关系型数据库，通过插件扩展机制（Extension）获得了类似 NoSQL 和向量数据库的能力。

1. 它本质上是个关系型库：支持 ACID、事务、强一致性，有完整的 SQL 支持。

2. 它可以变成文档型（NoSQL）：它支持 `JSONB` 数据类型（你建表时的 `metadata jsonb` 就是），可以直接存非结构化的 JSON，甚至可以对 JSON 里的字段建索引（GIN 索引），性能媲美 MongoDB。

3. 它可以变成向量数据库：通过安装 `pgvector` 扩展，它多了一种 `vector` 数据类型，专门用来存高维数组，并支持 HNSW 索引和余弦距离计算。



# Agent功能完善：流式推送（Server\-Sent\-Events）

当`return String`，用户必须等大模型把几百字全部生成完，页面才会突然显示一长串结果，体验极差

```Java
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

// ... 类名和其他代码保持不变 ...

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String message) {
        // 创建 SSE 发射器，设置超时时间为 5 分钟
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        // 由于流式处理需要异步，这里开启一个新线程处理 Agent 逻辑
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
                        .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(dateTimeTools))
                        .build();

                int maxSteps = 5, currentStep = 0;

                // 2. Agent Loop
                while (currentStep < maxSteps) {
                    currentStep++;
                    
                    // 这里注意：流式调用大模型用 .stream() 而不是 .call()
                    // 我们会拿到一个 Flux<ChatResponse>，用流的方式处理
                    reactor.core.publisher.Flux<ChatResponse> responseFlux = chatClient.prompt()
                            .messages(messages)
                            .options(options)
                            .stream()
                            .chatResponse();

                    // 用来暂存这一轮的完整回复（为了判断是否有工具调用）
                    StringBuilder fullResponseText = new StringBuilder();
                    AssistantMessage[] finalAssistantMsg = new AssistantMessage[1];

                    // 阻塞当前线程，等待流式输出完成（学习阶段先用 block，生产环境全链路应该用 WebFlux）
                    responseFlux.toStream().forEach(chatResponse -> {
                        AssistantMessage output = chatResponse.getResult().getOutput();
                        finalAssistantMsg[0] = output;
                        
                        // 获取当前增量文本
                        String text = output.getText();
                        if (text != null && !text.isEmpty()) {
                            fullResponseText.append(text);
                            try {
                                // 推给前端：事件名为 message，内容为增量文本
                                emitter.send(SseEmitter.event().name("message").data(text));
                            } catch (IOException e) {
                                throw new RuntimeException("SSE 发送失败", e);
                            }
                        }
                    });

                    AssistantMessage assistantMessage = finalAssistantMsg[0];
                    messages.add(assistantMessage);

                    if (assistantMessage.hasToolCalls()) {
                        emitter.send(SseEmitter.event().name("status").data("正在调用工具..."));
                        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                        
                        for (AssistantMessage.ToolCall toolCall : toolCalls) {
                            if ("getCurrentDateTime".equals(toolCall.name())) {
                                String res = dateTimeTools.getCurrentDateTime();
                                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), res));
                                emitter.send(SseEmitter.event().name("status").data("工具执行完毕，准备总结..."));
                            }
                        }
                        messages.add(new ToolResponseMessage(toolResponses, Map.of()));
                    } else {
                        // 没有工具调用了，循环结束
                        break;
                    }
                }

                // 推送完成事件并关闭
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
```

## 流程：

1\.创建SseEmitter发射器

2\.流式处理需要异步操作，需要创建新线程

3\.emitter\.send向http链接发送数据

1. 指定事件名字name,以及待传输的数据data

4\.流式调用大模型\.stream\(\)

```Plain Text
// 这里注意：流式调用大模型用 .stream() 而不是 .call()
                    // 我们会拿到一个 Flux<ChatResponse>，用流的方式处理
                    reactor.core.publisher.Flux<ChatResponse> responseFlux = chatClient.prompt()
                            .messages(messages)
                            .options(options)
                            .stream()
                            .chatResponse();

```

5\.阻塞当前线程 等待这一轮回复完成

6\.获取增量文本推送前端，并将增量文本拼接到下一轮agent loop

7\.推送完成，关闭事件与异步操作

## 语法解析

1\.

```Java
@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

告诉浏览器和 Spring，这个接口返回的数据格式不是普通的 JSON，而是 text/event\-stream（SSE 专用格式）。

2\.SseEmitter

含义：Spring 提供的专门用于发送 SSE 事件的类

```Java
new SseEmitter(300000L)//：创建一个 5 分钟超时的连接。

emitter.send(SseEmitter.event().name("status").data("内容"))//：发送一个事件。name 是事件名（前端可以根据名字做不同处理），data 是实际传输的内容。

emitter.complete()//：表示推送完成，断开连接。

emitter.completeWithError(e)//：发生异常时，带着错误信息断开连接
```

3\.响应式编程

```Java
stream() 
reactor.core.publisher.Flux
```

这是 Spring 的响应式编程（WebFlux 的核心）。\.call\(\) 是同步阻塞的，拿到全量结果；\.stream\(\) 是异步非阻塞的，拿到的是一个 水流（Stream）。

Flux\<ChatResponse\>：你可以把它想象成一根管子，大模型每生成一点点内容，就会往管子里面塞一个 ChatResponse。

\.toStream\(\)\.forEach\(\.\.\.\)：这里我们用了折中方案，把响应式的 Flux 转成了 Java 传统的 Stream。因为 Spring MVC 不是纯响应式的，所以用 \.toStream\(\) 把它“拉”回普通的同步处理流，遍历每个增量块。

4\.新起一个线程处理耗时请求

```Java
new Thread(() -> { ... }).start()
```

Tomcat 的线程池是有限的。如果不新起线程，SSE 连接占着 Tomcat 的线程不放，一旦有十几个用户同时聊天，你的服务就卡死了。新起线程后，Tomcat 线程立刻释放去接待别人，你在这个后台线程里慢慢给前端推数据。



报错修改

> Cannot invoke "org\.springframework\.ai\.chat\.model\.Generation\.getOutput\(\)" because the return value of "org\.springframework\.ai\.chat\.model\.ChatResponse\.getResult\(\)" is null
> 
> 

流式输出的某些数据块（Chunk）中，ChatResponse 的 getResult\(\) 返回了 null。这在流式场景下是正常的：模型在开头或结尾可能会发送一些不含实际文本内容的“空事件”或“控制事件”，你的代码直接调用 \.getOutput\(\) 就触发了空指针

```Java
responseFlux.toStream().forEach(chatResponse -> {
    AssistantMessage output = chatResponse.getResult().getOutput();
    finalAssistantMsg[0] = output;
    // ...
});
```

增加判空

```Java
responseFlux.toStream().forEach(chatResponse -> {
    // 关键：流式响应中某些块可能不含 result，直接跳过
    if (chatResponse.getResult() == null) {
        return; // forEach 的 return 相当于 continue
    }
    
    AssistantMessage output = chatResponse.getResult().getOutput();
    if (output == null) {
        return;
    }
    finalAssistantMsg[0] = output;
    
    String text = output.getText();
    if (text != null && !text.isEmpty()) {
        fullResponseText.append(text);
        try {
            emitter.send(SseEmitter.event().name("message").data(text));
        } catch (IOException e) {
            throw new RuntimeException("SSE 发送失败", e);
        }
    }
});
```

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NjFjOGQzOTdlOWI3OGVhZWE3OGY0NGVkMjc3NGFlM2ZfZWZhMTFiYzYzNDk4ZTdiYzVmMDRjNjBhNDI2MzYzZDZfSUQ6NzcwNjAwNzg1NjgxODI3NzU2MF8xNzk0MjExODU3OjE3OTQyOTgyNTdfVjM)



# Agent接入专属业务工具

本例将接入专属的短链智能运维助手

设计目标：使得大模型能听懂“查一下刚才那条短链的访问量”或“查一下 Redis 的命中率”，并自动调用对应的 Java 方法返回模拟数据，最后用自然语言总结给你

## 新建专属业务工具类

```Java
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

```

在对应的Controller添加对应方法即可

