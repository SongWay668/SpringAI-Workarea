package com.ws16289.daxi.util.splitter.facotry;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentBySentenceSplitter;

public class PaperDocumentSplitterFactory extends SplitterFactory{
    @Override
    public DocumentSplitter createState() {
        return new DocumentByParagraphSplitter(500,50);
    }
}
