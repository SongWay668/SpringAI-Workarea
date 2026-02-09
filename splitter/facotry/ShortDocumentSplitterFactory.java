package com.ws16289.daxi.util.splitter.facotry;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentBySentenceSplitter;

public class ShortDocumentSplitterFactory extends SplitterFactory{
    @Override
    public DocumentSplitter createState() {
        return new DocumentBySentenceSplitter(200,20);
    }
}
