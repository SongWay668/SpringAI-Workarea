package com.ws16289.daxi.util.splitter.facotry;

import dev.langchain4j.data.document.DocumentSplitter;

public abstract class SplitterFactory {
    public abstract DocumentSplitter createState();
}
