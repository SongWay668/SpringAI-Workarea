package com.ws16289.daxi.util.splitter;

import com.ws16289.daxi.util.DocumentConverter;
import com.ws16289.daxi.util.splitter.facotry.SplitterFactory;
import com.ws16289.daxi.util.splitter.facotry.SplitterFactoryProvider;
import com.ws16289.daxi.util.splitter.facotry.SplitterType;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 抽象文档分割器基类，封装公共逻辑
 */
@Component
public abstract class AbstractDocumentSplitter implements IDocumentSplitter {

    @Autowired
    protected DocumentConverter documentConverter;

    /**
     * 获取分割器类型，由子类实现
     */
    protected abstract SplitterType getSplitterType();

    /**
     * 创建自定义分割器，子类可以选择覆盖此方法
     */
    protected DocumentSplitter createDocumentSplitter() {
        SplitterFactory factory = SplitterFactoryProvider.getFactory(getSplitterType());
        return factory.createState();
    }

    @Override
    public List<Document> split(List<Document> documents) {
        DocumentSplitter documentSplitter = createDocumentSplitter();
        List<TextSegment> textSegments = documentSplitter.splitAll(
                documentConverter.convertListToLangChain4j(documents)
        );

        return documentConverter.convertTextSegmentsToSpringAI(textSegments);
    }
}
