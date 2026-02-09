package com.ws16289.daxi.util.splitter;

import com.ws16289.daxi.util.splitter.facotry.SplitterType;
import org.springframework.stereotype.Component;

@Component
public class CommonDocumentSplitter extends AbstractDocumentSplitter {

    @Override
    protected SplitterType getSplitterType() {
        return SplitterType.COMMON;
    }
}
