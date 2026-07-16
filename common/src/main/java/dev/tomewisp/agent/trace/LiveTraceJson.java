package dev.tomewisp.agent.trace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Set;

public final class LiveTraceJson {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public String encode(LiveAgentTrace trace, Set<String> secrets) {
        String json = gson.toJson(trace);
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                json = json.replace(secret, "[REDACTED]");
            }
        }
        json = json.replaceAll(
                "(?i)(authorization|x-api-key)(\\\\?\"?\\s*[:=]\\s*\\\\?\"?)[^\\\"\\s,}]+",
                "$1$2[REDACTED]");
        return json;
    }
}
