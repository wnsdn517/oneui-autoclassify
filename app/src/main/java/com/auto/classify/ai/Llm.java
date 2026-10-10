package com.auto.classify.ai;

/** A text generator that needs no API key. Implementations block; call off the main thread. */
public interface Llm {
    String generate(String system, String user) throws Exception;
}
