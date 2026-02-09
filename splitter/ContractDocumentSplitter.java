package com.ws16289.daxi.util.splitter;

import com.ws16289.daxi.util.splitter.facotry.SplitterType;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import org.springframework.stereotype.Component;

@Component
public class ContractDocumentSplitter extends AbstractDocumentSplitter {

    @Override
    protected SplitterType getSplitterType() {
        return SplitterType.CONTRACT;
    }

}
