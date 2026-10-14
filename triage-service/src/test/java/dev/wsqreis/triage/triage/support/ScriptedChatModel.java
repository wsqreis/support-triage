package dev.wsqreis.triage.triage.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A ChatModel whose answers come from a function, so tests control exactly what "the LLM" says. */
public class ScriptedChatModel implements ChatModel {

    private final Function<Prompt, String> responder;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

    public ScriptedChatModel(Function<Prompt, String> responder) {
        this.responder = responder;
    }

    public static ScriptedChatModel answering(String json) {
        return new ScriptedChatModel(prompt -> json);
    }

    public static ScriptedChatModel failing(RuntimeException error) {
        return new ScriptedChatModel(prompt -> {
            throw error;
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(responder.apply(prompt)))));
    }

    public int calls() {
        return calls.get();
    }

    public List<Prompt> prompts() {
        return prompts;
    }
}
