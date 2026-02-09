package com.ws16289.daxi.util.splitter.facotry;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;

public class CommonDocumentSplitterFactory extends SplitterFactory{
    @Override
    public DocumentSplitter createState() {
        return DocumentSplitters.recursive(500,50);
    }
}
