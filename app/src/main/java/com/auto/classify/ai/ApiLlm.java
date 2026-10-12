package com.auto.classify.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An AI service with the user's own API key: Google AI Studio (Gemini) or any OpenAI-compatible
 * endpoint (OpenRouter, Groq, …). Keys live in a private preferences file, not the
 * world-readable one the launcher hook reads.
 */
public final class ApiLlm implements Llm {

    public static final String PREFS = "secrets";
    public static final String KEY_PROVIDER = "api_provider"; // gemini | openai
    public static final String KEY_KEY = "api_key";
    public static final String KEY_MODEL = "api_model";
    public static final String KEY_BASE = "api_base";
    /** Which service's key is in use: "gemini", a preset's address, or "custom". */
    /** Let the model search the web (Gemini grounding, OpenRouter's web plugin). */
    public static final String KEY_WEB = "api_web";
    public static final String KEY_SLOT = "api_slot";
    /** Keys are kept per service ("api_key.gemini", …), so switching never sends one service's key to another. */
    public static final String KEY_PREFIX = "api_key.";
    /** The Gemini model used until the user picks another. */
    public static final String DEFAULT_GEMINI = "gemini-flash-lite-latest";

    public static final String GEMINI = "gemini";
    public static final String OPENAI = "openai";
    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final String provider, key, base;
    private String model;
    private final SharedPreferences prefs;
    /** On when the model may search the web; only set for services that support it. */
    public boolean web;
    /** Gets the search queries the model ran, as they happen (Gemini reports them). */
    public java.util.function.Consumer<String> onSearch;
    /** Tokens of the last call (0 when the service didn't say). */
    public int lastIn, lastOut;

    public ApiLlm(String provider, String key, String model, String base, SharedPreferences prefs) {
        this.provider = provider;
        this.key = key == null ? "" : key.trim();
        this.model = model == null ? "" : model.trim();
        if (this.model.isEmpty() && GEMINI.equals(provider)) this.model = DEFAULT_GEMINI;
        this.base = base == null ? "" : base.trim();
        this.prefs = prefs;
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether this service can search the web for the model: Gemini, or OpenRouter. */
    public static boolean supportsWeb(String provider, String base) {
        if (GEMINI.equals(provider)) return true;
        return base == null || base.trim().isEmpty() || base.contains("openrouter.ai");
    }

    /** The key stored for one service; an old single key counts until the first per-service save. */
    public static String keyFor(SharedPreferences p, String slot) {
        String v = p.getString(KEY_PREFIX + slot, null);
        if (v != null) return v;
        return p.contains(KEY_SLOT) ? "" : p.getString(KEY_KEY, "");
    }

    /** The configured service, or null when no key is set. */
    public static ApiLlm fromPrefs(Context context) {
        SharedPreferences p = prefs(context);
        String provider = p.getString(KEY_PROVIDER, GEMINI);
        String key = keyFor(p, p.getString(KEY_SLOT, GEMINI.equals(provider) ? "gemini" : "custom"));
        if (key == null || key.trim().isEmpty()) return null;
        ApiLlm llm = new ApiLlm(provider, key, p.getString(KEY_MODEL, ""), p.getString(KEY_BASE, ""), p);
        llm.web = p.getBoolean(KEY_WEB, false) && supportsWeb(provider, p.getString(KEY_BASE, ""));
        return llm;
    }

    public String model() {
        return model;
    }

    @Override
    public String generate(String system, String user) throws Exception {
        return generate(system, user, false);
    }

    /** @param json ask for a JSON object answer (Gemini: JSON mode; others: plain request) */
    public String generate(String system, String user, boolean json) throws Exception {
        return generate(system, user, json, null);
    }

    /** @param onText when set, the answer is streamed and this gets the text so far after each piece */
    public String generate(String system, String user, boolean json, java.util.function.Consumer<String> onText) throws Exception {
        if (key.isEmpty()) throw new IllegalStateException("API 키가 없어요");
        if (web) system += "\nYou may search the web to identify apps you don't recognise; the answer format stays the same.";
        if (model.isEmpty()) pickModel();
        // Busy or flaky service (503, 500, 429, timeouts): wait and try again a few times.
        long[] waits = {5000, 15000, 30000, 60000};
        for (int attempt = 0; ; attempt++) {
            try {
                return once(system, user, json, onText);
            } catch (Exception e) {
                boolean transient_ = e instanceof java.io.IOException
                        || e instanceof Http.HttpError && (((Http.HttpError) e).code >= 500 || ((Http.HttpError) e).code == 429);
                if (!transient_ || attempt >= Math.min(waits.length, maxRetries)) throw e;
                if (onRetry != null) onRetry.accept((attempt + 2) + "/" + (waits.length + 1));
                Thread.sleep(waits[attempt]);
            }
        }
    }

    /** Retries after a busy/rate-limited answer; background jobs lower this so they never hang for minutes. */
    public int maxRetries = 4;

    /** Called before each retry with "2/4", "3/4", … */
    public java.util.function.Consumer<String> onRetry;

    private String once(String system, String user, boolean json, java.util.function.Consumer<String> onText) throws Exception {
        try {
            return onText == null ? call(system, user, json) : stream(system, user, json, onText);
        } catch (Http.HttpError e) {
            // A retired or mistyped model name: switch to one the service lists, once.
            if (e.code != 404) throw e;
            String before = model;
            pickModel();
            if (model.equals(before)) throw e;
            return onText == null ? call(system, user, json) : stream(system, user, json, onText);
        }
    }

    private JSONObject geminiBody(String system, String user, boolean json) throws Exception {
        JSONObject config = new JSONObject().put("temperature", 0.2);
        // Search grounding can't be combined with JSON mode; the answer is read out of the text instead.
        if (json && !web) config.put("responseMimeType", "application/json");
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", user)))))
                .put("generationConfig", config);
        if (web) body.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
        return body;
    }

    private String stream(String system, String user, boolean json, java.util.function.Consumer<String> onText) throws Exception {
        lastIn = lastOut = 0;
        StringBuilder sb = new StringBuilder();
        if (GEMINI.equals(provider)) {
            Http.stream(GEMINI_BASE + "/models/" + model + ":streamGenerateContent?alt=sse",
                    geminiBody(system, user, json).toString(), "x-goog-api-key", key, data -> {
                        try {
                            JSONObject o = new JSONObject(data);
                            JSONObject usage = o.optJSONObject("usageMetadata");
                            if (usage != null) {
                                lastIn = usage.optInt("promptTokenCount");
                                lastOut = usage.optInt("candidatesTokenCount") + usage.optInt("thoughtsTokenCount");
                            }
                            JSONArray cands = o.optJSONArray("candidates");
                            JSONObject grounding = cands == null || cands.length() == 0 ? null : cands.getJSONObject(0).optJSONObject("groundingMetadata");
                            JSONArray queries = grounding == null ? null : grounding.optJSONArray("webSearchQueries");
                            if (queries != null && queries.length() > 0 && onSearch != null) {
                                List<String> q = new ArrayList<>();
                                for (int i = 0; i < queries.length(); i++) q.add(queries.optString(i));
                                onSearch.accept(String.join(", ", q));
                            }
                            JSONObject content = cands == null || cands.length() == 0 ? null : cands.getJSONObject(0).optJSONObject("content");
                            JSONArray parts = content == null ? null : content.optJSONArray("parts");
                            for (int i = 0; parts != null && i < parts.length(); i++) {
                                JSONObject part = parts.getJSONObject(i);
                                if (!part.optBoolean("thought")) sb.append(part.optString("text"));
                            }
                            onText.accept(sb.toString());
                        } catch (Exception ignored) {
                        }
                    });
        } else {
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", system))
                    .put(new JSONObject().put("role", "user").put("content", user));
            JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("temperature", 0.2)
                    .put("stream", true).put("stream_options", new JSONObject().put("include_usage", true));
            if (web) body.put("plugins", new JSONArray().put(new JSONObject().put("id", "web").put("max_results", 3)));
            Http.stream(openAiBase() + "/chat/completions", body.toString(), "Authorization", "Bearer " + key, data -> {
                try {
                    JSONObject o = new JSONObject(data);
                    JSONObject usage = o.optJSONObject("usage");
                    if (usage != null) {
                        lastIn = usage.optInt("prompt_tokens");
                        lastOut = usage.optInt("completion_tokens");
                    }
                    JSONArray choices = o.optJSONArray("choices");
                    JSONObject delta = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("delta");
                    if (delta != null && !delta.isNull("content")) {
                        sb.append(delta.optString("content"));
                        onText.accept(sb.toString());
                    }
                } catch (Exception ignored) {
                }
            });
        }
        if (sb.length() == 0) throw new IllegalStateException("빈 응답");
        return sb.toString();
    }

    private void pickModel() throws Exception {
        String best = bestModel(listModels());
        if (best == null) throw new IllegalStateException("쓸 수 있는 모델이 없어요");
        model = best;
        if (prefs != null) prefs.edit().putString(KEY_MODEL, model).apply();
    }

    private String call(String system, String user, boolean json) throws Exception {
        lastIn = lastOut = 0;
        if (GEMINI.equals(provider)) {
            JSONObject body = geminiBody(system, user, json);
            JSONObject out = new JSONObject(Http.post(GEMINI_BASE + "/models/" + model + ":generateContent",
                    body.toString(), "x-goog-api-key", key));
            JSONObject usage = out.optJSONObject("usageMetadata");
            if (usage != null) {
                lastIn = usage.optInt("promptTokenCount");
                lastOut = usage.optInt("candidatesTokenCount") + usage.optInt("thoughtsTokenCount");
            }
            JSONArray cands = out.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) throw new IllegalStateException("빈 응답 (안전 필터 등)");
            JSONObject content = cands.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (!part.optBoolean("thought")) sb.append(part.optString("text"));
            }
            return sb.toString();
        }
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user));
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("temperature", 0.2);
        if (web) body.put("plugins", new JSONArray().put(new JSONObject().put("id", "web").put("max_results", 3)));
        String raw = Http.post(openAiBase() + "/chat/completions", body.toString(), "Authorization", "Bearer " + key);
        JSONObject usage = raw.trim().startsWith("{") ? new JSONObject(raw).optJSONObject("usage") : null;
        if (usage != null) {
            lastIn = usage.optInt("prompt_tokens");
            lastOut = usage.optInt("completion_tokens");
        }
        return Http.chatText(raw);
    }

    private String openAiBase() {
        String url = base.isEmpty() ? "https://openrouter.ai/api/v1" : base;
        return url.replaceAll("/chat/completions/?$", "").replaceAll("/+$", "");
    }

    /** Text models the key can use, as the service names them. */
    public List<String> listModels() throws Exception {
        List<String> out = new ArrayList<>();
        if (GEMINI.equals(provider)) {
            String page = null;
            do {
                String url = GEMINI_BASE + "/models?pageSize=200" + (page == null ? "" : "&pageToken=" + page);
                JSONObject o = new JSONObject(Http.get(url, "x-goog-api-key", key));
                JSONArray models = o.optJSONArray("models");
                for (int i = 0; models != null && i < models.length(); i++) {
                    JSONObject m = models.getJSONObject(i);
                    JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                    if (methods == null || !methods.toString().contains("\"generateContent\"")) continue;
                    String name = m.optString("name").replaceFirst("^models/", "");
                    if (name.startsWith("gemini")) out.add(name);
                }
                page = o.optString("nextPageToken", null);
                if (page != null && page.isEmpty()) page = null;
            } while (page != null);
        } else {
            JSONObject o = new JSONObject(Http.get(openAiBase() + "/models", "Authorization", "Bearer " + key));
            JSONArray data = o.optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) out.add(data.getJSONObject(i).optString("id"));
        }
        return out;
    }

    private static final Pattern GEMINI_VERSION = Pattern.compile("^gemini-(\\d+(?:\\.\\d+)?)-flash$");

    /**
     * The newest stable "gemini-X-flash" (fast, cheap, generous free tier); else any flash, else
     * the first model.
     */
    public static String bestModel(List<String> models) {
        String best = null;
        double bestV = -1;
        for (String m : models) {
            Matcher v = GEMINI_VERSION.matcher(m);
            if (v.matches() && Double.parseDouble(v.group(1)) > bestV) {
                bestV = Double.parseDouble(v.group(1));
                best = m;
            }
        }
        if (best != null) return best;
        if (models.contains("gemini-flash-latest")) return "gemini-flash-latest";
        for (String m : models) {
            String l = m.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("flash") && !l.contains("image") && !l.contains("tts") && !l.contains("live")) return m;
        }
        return models.isEmpty() ? null : models.get(0);
    }
}
