package com.ws16289.daxi.util.splitter.facotry;

import java.util.HashMap;
import java.util.Map;

public class SplitterFactoryProvider {

//    public static SplitterFactory getFactory(SplitterType splitterType) {
//        return switch (splitterType) {
//            case CONTRACT -> new ContractDocumentSplitter();
//            case LONG -> new LongDocumentSplitter();
//            case SHORT -> new ShortDocumentSplitter();
//            case Paper -> new PaperDocumentSplitter();
//            default ->  new CommonDocumentSplitter();
//        };
//    }

    private static final Map<SplitterType,SplitterFactory> factoryMap = new HashMap<>();
    static {
        // 自动注册所有工厂
        registerFactory(SplitterType.CONTRACT, new ContractDocumentSplitterFactory());
        registerFactory(SplitterType.LONG, new LongDocumentSplitterFactory());
        registerFactory(SplitterType.SHORT, new ShortDocumentSplitterFactory());
        registerFactory(SplitterType.PAPER, new PaperDocumentSplitterFactory());
        registerFactory(SplitterType.COMMON, new CommonDocumentSplitterFactory());
    }

    public static void registerFactory(SplitterType splitterType, SplitterFactory factory) {
        factoryMap.put(splitterType,factory);
    }

    public static SplitterFactory getFactory(SplitterType type){

        SplitterFactory factory = factoryMap.get(type);
        if(null == factory){
            throw new IllegalArgumentException("Unsupported log type: " + type);
        }
        return factory;
    }
}
