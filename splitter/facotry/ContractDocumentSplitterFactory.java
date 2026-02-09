package com.ws16289.daxi.util.splitter.facotry;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;

public class ContractDocumentSplitterFactory extends SplitterFactory{
    @Override
    public DocumentSplitter createState() {
        return new DocumentByParagraphSplitter(400,40);
    }
}
