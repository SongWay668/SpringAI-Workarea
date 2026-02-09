package com.ws16289.daxi.util.splitter.facotry;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;

public class LongDocumentSplitterFactory extends SplitterFactory{
    @Override
    public DocumentSplitter createState() {
        return new DocumentByParagraphSplitter(1000,100);
    }
}
