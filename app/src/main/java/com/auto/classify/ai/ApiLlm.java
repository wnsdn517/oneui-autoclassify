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
    /** Let the AI look things up with tools (layout, folder contents, usage, app info). */
    public static final String KEY_TOOLS = "api_tools";
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
    /** What the model may call while it works; null = no tools. */
    public interface Tools {
        /** Function declarations: [{name, description, parameters (JSON schema)}]. */
        JSONArray declarations();

        String call(String name, JSONObject args) throws Exception;
    }

    public Tools tools;
    /** Gets each tool as the model calls it: its name and what it looks up (argument values, may be empty). */
    public java.util.function.BiConsumer<String, String> onTool;
    private static final int MAX_TOOL_STEPS = 6;
    /** Notes shown beside models in the picker (search support, free), filled by listModels(). */
    public final java.util.Map<String, String> notes = new java.util.LinkedHashMap<>();
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
        llm.web = p.getBoolean(KEY_WEB, false) && supportsWeb(provider, p.getString(KEY_BASE, ""))
                && (!GEMINI.equals(provider) || llm.model.isEmpty() || geminiWeb(llm.model));
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
        webFailed = null;
        if (web && tools == null) system += WEB_HINT;
        if (tools != null) {
            system += "\nYou can call tools to check facts instead of guessing: the real apps-screen layout, what is inside a folder, "
                    + "how long each app was used, details of an app" + (web ? ", and web_search" : "") + ". "
                    + "Call them whenever the request depends on such facts (e.g. ordering by usage, 'my most used apps', "
                    + "checking what a folder really holds) and to verify your plan, then give the final answer in the required format.";
        }
        if (model.isEmpty()) pickModel();
        final String sys = system;
        // A tool conversation retries each request on its own (a restart would repeat every earlier step).
        if (tools != null) return once(sys, user, json, onText);
        return retrying(() -> once(sys, user, json, onText));
    }

    private static final String WEB_HINT = "\nYou may search the web to identify apps you don't recognise; the answer format stays the same.";
    /** Set when the last request had to go without the web search it asked for. */
    public String webFailed;

    private static long lastRequestAt;

    /** Free tiers allow only a few requests per minute: keep a little space between requests. */
    private void spaceOut() throws InterruptedException {
        if (!GEMINI.equals(provider)) return;
        long wait;
        synchronized (ApiLlm.class) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, lastRequestAt + 2000);
            lastRequestAt = slot;
            wait = slot - now;
        }
        if (wait > 0) Thread.sleep(wait);
    }

    /**
     * Runs one request, retrying only what Google says is temporary: 429 (after the delay it
     * names; never when the daily quota is gone) and 5xx/network errors (growing delay with jitter).
     */
    private <T> T retrying(java.util.concurrent.Callable<T> call) throws Exception {
        for (int attempt = 0; ; attempt++) {
            spaceOut();
            try {
                return call.call();
            } catch (Exception e) {
                long wait = -1;
                String why = "";
                if (e instanceof Http.HttpError) {
                    Http.HttpError h = (Http.HttpError) e;
                    if (h.code == 429 && !h.daily) {
                        wait = h.retryAfterMs > 0 ? h.retryAfterMs + 1000 : 15_000L * (attempt + 1);
                        why = "한도 초과";
                    } else if (h.code >= 500) {
                        wait = 3000L << attempt;
                        why = "서버 오류(" + h.code + ")";
                    }
                } else if (e instanceof java.io.IOException) {
                    wait = 3000L << attempt;
                    why = "연결 오류";
                }
                if (wait < 0 || attempt >= maxRetries || wait > 90_000) throw e;
                wait += (long) (Math.random() * 1000);
                if (onRetry != null) onRetry.accept((attempt + 2) + "/" + (maxRetries + 1) + " · " + why + ", " + Math.round(wait / 1000.0) + "초 뒤");
                Thread.sleep(wait);
            }
        }
    }

    /** Retries after a busy/rate-limited answer; background jobs lower this so they never hang for minutes. */
    public int maxRetries = 3;

    /** Called before each retry with "2/4", "3/4", … */
    public java.util.function.Consumer<String> onRetry;

    private String once(String system, String user, boolean json, java.util.function.Consumer<String> onText) throws Exception {
        if (tools != null) {
            try {
                return withTools(system, user);
            } catch (Http.HttpError e) {
                if (e.code != 404) throw e;
                String before = model;
                pickModel();
                if (model.equals(before)) throw e;
                return withTools(system, user);
            }
        }
        try {
            return onText == null ? call(system, user, json) : stream(system, user, json, onText);
        } catch (Http.HttpError e) {
            // The service refused web search (model without search, no credit for it): answer without it.
            if (web && (e.code == 400 || e.code == 402 || e.code == 403)) {
                web = false;
                webFailed = "웹 검색을 쓸 수 없어 검색 없이 처리했어요 (" + e.code + ")";
                return once(system.replace(WEB_HINT, ""), user, json, onText);
            }
            // A retired or mistyped model name: switch to one the service lists, once.
            if (e.code != 404) throw e;
            String before = model;
            pickModel();
            if (model.equals(before)) throw e;
            return onText == null ? call(system, user, json) : stream(system, user, json, onText);
        }
    }

    /** The argument values of one tool call, short, for showing what the AI is looking up. */
    private static String detail(JSONObject args) {
        if (args == null) return "";
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String v = String.valueOf(args.opt(keys.next())).trim();
            if (v.isEmpty() || v.equals("null")) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(v);
        }
        return sb.length() > 40 ? sb.substring(0, 40) + "…" : sb.toString();
    }

    private String runTool(String name, JSONObject args) {
        if (onTool != null) onTool.accept(name, detail(args));
        String r;
        try {
            r = tools.call(name, args == null ? new JSONObject() : args);
        } catch (Exception e) {
            r = "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
        return r.length() > 12000 ? r.substring(0, 12000) + "…(truncated)" : r;
    }

    /** Runs the request as a tool-calling conversation until the model answers in text. */
    private String withTools(String system, String user) throws Exception {
        lastIn = lastOut = 0;
        JSONArray decls = tools.declarations();
        if (GEMINI.equals(provider)) {
            JSONArray contents = new JSONArray().put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", user))));
            for (int step = 0; step <= MAX_TOOL_STEPS; step++) {
                JSONObject body = new JSONObject()
                        .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                        .put("contents", contents)
                        .put("generationConfig", new JSONObject().put("temperature", 0.2));
                // The last round has no tools, so the model has to answer.
                if (step < MAX_TOOL_STEPS) body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decls)));
                final String payload = body.toString();
                JSONObject out = new JSONObject(retrying(() -> Http.post(GEMINI_BASE + "/models/" + model + ":generateContent",
                        payload, "x-goog-api-key", key)));
                JSONObject usage = out.optJSONObject("usageMetadata");
                if (usage != null) {
                    lastIn += usage.optInt("promptTokenCount");
                    lastOut += usage.optInt("candidatesTokenCount") + usage.optInt("thoughtsTokenCount");
                }
                JSONArray cands = out.optJSONArray("candidates");
                if (cands == null || cands.length() == 0) throw new IllegalStateException("빈 응답 (안전 필터 등)");
                JSONObject content = cands.getJSONObject(0).optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                JSONArray responses = new JSONArray();
                StringBuilder text = new StringBuilder();
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    JSONObject call = part.optJSONObject("functionCall");
                    if (call != null) {
                        String name = call.optString("name");
                        String result = runTool(name, call.optJSONObject("args"));
                        JSONObject reply = new JSONObject().put("name", name).put("response", new JSONObject().put("result", result));
                        // Gemini 3 numbers each call and wants the result to carry the same id.
                        if (call.has("id")) reply.put("id", call.optString("id"));
                        responses.put(new JSONObject().put("functionResponse", reply));
                    } else if (!part.optBoolean("thought")) {
                        text.append(part.optString("text"));
                    }
                }
                if (responses.length() == 0) {
                    if (text.length() == 0) {
                        String reason = cands.getJSONObject(0).optString("finishReason");
                        throw new IllegalStateException("빈 응답" + (reason.isEmpty() ? "" : " (" + reason + ")"));
                    }
                    return text.toString();
                }
                // Keep the model's turn exactly as it came (Gemini needs its thought signatures back).
                contents.put(content);
                contents.put(new JSONObject().put("role", "user").put("parts", responses));
            }
            throw new IllegalStateException("AI가 도구를 너무 많이 써서 멈췄어요");
        }
        JSONArray defs = new JSONArray();
        for (int i = 0; i < decls.length(); i++) {
            JSONObject d = decls.getJSONObject(i);
            defs.put(new JSONObject().put("type", "function").put("function", d));
        }
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user));
        for (int step = 0; step <= MAX_TOOL_STEPS; step++) {
            JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("temperature", 0.2);
            if (step < MAX_TOOL_STEPS) body.put("tools", defs).put("tool_choice", "auto");
            final String payload = body.toString();
            String raw = retrying(() -> Http.post(openAiBase() + "/chat/completions", payload, "Authorization", "Bearer " + key));
            JSONObject o = new JSONObject(raw);
            JSONObject usage = o.optJSONObject("usage");
            if (usage != null) {
                lastIn += usage.optInt("prompt_tokens");
                lastOut += usage.optInt("completion_tokens");
            }
            JSONArray choices = o.optJSONArray("choices");
            JSONObject msg = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("message");
            JSONArray calls = msg == null ? null : msg.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) return Http.chatText(raw);
            messages.put(msg);
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.getJSONObject(i);
                JSONObject fn = c.optJSONObject("function");
                if (fn == null) continue;
                JSONObject args;
                try {
                    args = new JSONObject(fn.optString("arguments", "{}"));
                } catch (Exception e) {
                    args = new JSONObject();
                }
                messages.put(new JSONObject().put("role", "tool").put("tool_call_id", c.optString("id"))
                        .put("content", runTool(fn.optString("name"), args)));
            }
        }
        throw new IllegalStateException("AI가 도구를 너무 많이 써서 멈췄어요");
    }

    /** A grounded web search for one question; used as a tool. */
    public String webSearch(String query) throws Exception {
        if (!supportsWeb(provider, base)) throw new IllegalStateException("이 서비스는 웹 검색을 지원하지 않아요");
        ApiLlm s = new ApiLlm(provider, key, model, base, null);
        s.web = true;
        s.onSearch = onSearch;
        return s.generate("Search the web and answer briefly and factually in Korean; key facts only.", query);
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
                            reportSearches(cands == null || cands.length() == 0 ? null : cands.getJSONObject(0).optJSONObject("groundingMetadata"));
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

    /** Passes the queries Gemini searched (grounding metadata) to onSearch. */
    private void reportSearches(JSONObject grounding) {
        JSONArray queries = grounding == null ? null : grounding.optJSONArray("webSearchQueries");
        if (queries == null || queries.length() == 0 || onSearch == null) return;
        List<String> q = new ArrayList<>();
        for (int i = 0; i < queries.length(); i++) q.add(queries.optString(i));
        onSearch.accept(String.join(", ", q));
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
            reportSearches(cands.getJSONObject(0).optJSONObject("groundingMetadata"));
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
        notes.clear();
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
                    if (!name.startsWith("gemini") || !isTextModel(name)) continue;
                    out.add(name);
                    // Gemini text models do function calling. Images are never sent, so no image chip.
                    tag(name, NOTE_TOOLS);
                    if (geminiWeb(name)) tag(name, NOTE_SEARCH);
                }
                page = o.optString("nextPageToken", null);
                if (page != null && page.isEmpty()) page = null;
            } while (page != null);
        } else {
            JSONObject o = new JSONObject(Http.get(openAiBase() + "/models", "Authorization", "Bearer " + key));
            JSONArray data = o.optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject m = data.getJSONObject(i);
                String id = m.optString("id");
                if (id.isEmpty() || !isTextModel(id)) continue;
                JSONObject arch = m.optJSONObject("architecture");
                JSONArray outputs = arch == null ? null : arch.optJSONArray("output_modalities");
                if (outputs != null && !(outputs.length() == 1 && "text".equals(outputs.optString(0)))) continue; // images, audio…
                out.add(id);
                JSONObject pricing = m.optJSONObject("pricing");
                boolean free = id.endsWith(":free")
                        || pricing != null && "0".equals(pricing.optString("prompt")) && "0".equals(pricing.optString("completion"));
                if (free) tag(id, NOTE_FREE);
                // Models with the service's own search; the others would need OpenRouter's paid web plugin.
                if (pricing != null && pricing.has("web_search") && !"0".equals(pricing.optString("web_search"))) {
                    tag(id, NOTE_SEARCH);
                }
                JSONArray params = m.optJSONArray("supported_parameters");
                if (params != null && params.toString().contains("\"tools\"")) tag(id, NOTE_TOOLS);
            }
        }
        return out;
    }

    /** Models that write text: not image, speech, audio, embedding or moderation models. */
    static boolean isTextModel(String id) {
        String l = id.toLowerCase(java.util.Locale.ROOT);
        for (String bad : new String[]{"image", "imagen", "veo", "tts", "audio", "live", "embed", "robotics", "computer-use",
                "aqa", "whisper", "transcribe", "speech", "rerank", "moderation", "guard", "dall-e", "sdxl", "flux", "stable-diffusion",
                "banana", "omni"}) {
            if (l.contains(bad)) return false;
        }
        return true;
    }

    private static final Pattern GEMINI_NAME_VERSION = Pattern.compile("^gemini-(\\d+(?:\\.\\d+)?)");

    /** Capability tokens in notes, separated by spaces. The picker shows each as a chip. */
    public static final String NOTE_SEARCH = "검색";
    public static final String NOTE_IMAGE = "이미지";
    public static final String NOTE_TOOLS = "도구";
    public static final String NOTE_FREE = "무료";

    /** Adds one capability token to a model's note, once. */
    private void tag(String id, String token) {
        String old = notes.getOrDefault(id, "");
        if (!java.util.Arrays.asList(old.split(" ")).contains(token)) notes.put(id, old.isEmpty() ? token : old + " " + token);
    }

    /** Gemini 2.x and later (and the -latest aliases) can ground answers with Google Search; 2.0 Flash-Lite can't. */
    public static boolean geminiWeb(String id) {
        if (id.contains("2.0-flash-lite")) return false;
        if (id.endsWith("-latest")) return true;
        Matcher v = GEMINI_NAME_VERSION.matcher(id);
        return v.find() && Double.parseDouble(v.group(1)) >= 2.0;
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
