package com.ws16289.daxi.util.splitter;

import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.ai.transformer.ContentFormatTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SpringDocumentSplitter implements IDocumentSplitter {

    @Autowired
    private TokenTextSplitter tokenTextSplitter;



    @Override
    public List<Document> split(List<Document> documents) {

        return  tokenTextSplitter.apply(documents);
    }
}
