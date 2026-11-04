package com.example.jchatminddemo.service;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
//import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
//import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeBaseService {

    private final VectorStore vectorStore;

    public KnowledgeBaseService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void init() {
        System.out.println(">>> [RAG] 正在读取本地 Markdown 文档...");

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

            System.out.println(">>> [RAG] 文档读取完毕，共切分为 " + splitDocuments.size() + " 个小块。");

            // 4. 存入向量库
            vectorStore.add(splitDocuments);

            System.out.println(">>> [RAG] 知识库初始化完成！");
        } catch (Exception e) {
            System.err.println(">>> [RAG] 知识库初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public VectorStore getVectorStore() {
        return this.vectorStore;
    }
}