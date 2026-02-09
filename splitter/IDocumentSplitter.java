package com.ws16289.daxi.util.splitter;

import org.springframework.ai.document.Document;

import java.util.List;


public interface IDocumentSplitter {

    public List<Document> split(List<Document> documents);
}
