// Java SDK — single-file, zero dependencies (java.net.http HttpClient, Java 11+).
// Transactional email API for applications and AI agents.
//
//   SendFlit sf = SendFlit.builder("re_...").build();
//   SendResult r = sf.send(Email.builder()
//       .from("you@yourdomain.com").to("user@example.com")
//       .subject("Confirm").html("<p>…</p>").build());

package com.sendflit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SendFlit {

    public static final String VERSION = "1.0.0";
    private static final String DEFAULT_BASE = "https://api.sendflit.com";

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final int maxRetries;

    private SendFlit(Builder b) {
        if (b.apiKey == null || b.apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        this.apiKey = b.apiKey;
        this.baseUrl = (b.baseUrl == null ? DEFAULT_BASE : b.baseUrl).replaceAll("/+$", "");
        this.timeout = b.timeout == null ? Duration.ofSeconds(15) : b.timeout;
        this.maxRetries = b.maxRetries < 0 ? 2 : b.maxRetries;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public static Builder builder(String apiKey) { return new Builder(apiKey); }

    public static final class Builder {
        private final String apiKey;
        private String baseUrl;
        private Duration timeout;
        private int maxRetries = 2;

        private Builder(String apiKey) { this.apiKey = apiKey; }

        public Builder baseUrl(String u) { this.baseUrl = u; return this; }
        public Builder timeout(Duration t) { this.timeout = t; return this; }
        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        public SendFlit build() { return new SendFlit(this); }
    }

    // ------------------------------------------------------------ email

    public SendResult send(Email email) throws SendFlitException {
        return request("POST", "/v1/email", Json.write(email.toMap()), SendResult.class);
    }

    public List<SendResult> batch(List<Email> emails) throws SendFlitException {
        if (emails == null || emails.isEmpty()) {
            throw new IllegalArgumentException("batch requires at least one email");
        }
        if (emails.size() > 100) {
            throw new IllegalArgumentException("batch accepts at most 100 emails");
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Email e : emails) list.add(e.toMap());
        BatchResult r = request("POST", "/v1/emails/batch",
                Json.write(Map.of("emails", list)), BatchResult.class);
        return r.results;
    }

    public Map<String, Object> listEmails(Integer limit, Integer offset, String status)
            throws SendFlitException {
        StringBuilder q = new StringBuilder();
        if (limit != null) q.append(q.length() == 0 ? "?" : "&").append("limit=").append(limit);
        if (offset != null) q.append(q.length() == 0 ? "?" : "&").append("offset=").append(offset);
        if (status != null) q.append(q.length() == 0 ? "?" : "&").append("status=").append(status);
        return request("GET", "/v1/emails" + q, null, Map.class);
    }

    public Map<String, Object> addDomain(String name) throws SendFlitException {
        return request("POST", "/v1/domains", Json.write(Map.of("name", name)), Map.class);
    }

    public Map<String, Object> listDomains() throws SendFlitException {
        return request("GET", "/v1/domains", null, Map.class);
    }

    public Map<String, Object> verifyDomain(String id) throws SendFlitException {
        return request("POST", "/v1/domains/" + id + "/verify", null, Map.class);
    }

    public Map<String, Object> addContact(String email, String name) throws SendFlitException {
        return request("POST", "/v1/contacts",
                Json.write(name == null ? Map.of("email", email)
                        : Map.of("email", email, "name", name)), Map.class);
    }

    public Map<String, Object> suppress(String email, String reason) throws SendFlitException {
        return request("POST", "/v1/suppressions",
                Json.write(Map.of("email", email, "reason",
                        reason == null ? "unsubscribe" : reason)), Map.class);
    }

    public Map<String, Object> usage() throws SendFlitException {
        return request("GET", "/v1/usage", null, Map.class);
    }

    // ------------------------------------------------------------ transport

    @SuppressWarnings("unchecked")
    private <T> T request(String method, String path, String jsonBody, Class<T> type)
            throws SendFlitException {
        int attempt = 0;
        while (true) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("User-Agent", "sendflit-java/" + VERSION)
                        .timeout(timeout);
                if (jsonBody != null) {
                    rb.header("Content-Type", "application/json")
                      .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
                } else {
                    rb.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                String body = res.body();

                if (res.statusCode() >= 200 && res.statusCode() < 300) {
                    if (type == Map.class) {
                        return (T) Json.read(body);
                    }
                    return Json.read(body, type);
                }
                boolean retryable = res.statusCode() == 429 || res.statusCode() >= 500;
                String retryAfter = res.headers().firstValue("retry-after").orElse(null);
                if (retryable && attempt < maxRetries) {
                    attempt++;
                    long delay = retryAfter != null ? Long.parseLong(retryAfter) * 1000L
                            : 300L * (1L << attempt);
                    Thread.sleep(delay + (long) (Math.random() * 150));
                    continue;
                }
                throw errorFrom(res.statusCode(), body);
            } catch (SendFlitException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SendFlitException("interrupted", 0, null);
            } catch (Exception e) {
                throw new SendFlitException("network error: " + e.getMessage(), 0, null);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static SendFlitException errorFrom(int status, String body) {
        String msg = "SendFlit API error (" + status + ")";
        try {
            Map<String, Object> parsed = (Map<String, Object>) Json.read(body);
            Object detail = parsed.get("detail");
            if (detail instanceof String) msg = (String) detail;
        } catch (Exception ignored) { }
        return new SendFlitException(msg, status, body);
    }

    // ------------------------------------------------------------ webhook

    public static boolean verifyWebhookSignature(String secret, byte[] rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody);
            StringBuilder sb = new StringBuilder("sha256=");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return constantTimeEquals(sb.toString(), signature == null ? "" : signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    // ------------------------------------------------------------ models

    public static final class Email {
        private String from, to, toName, subject, html, text, replyTo, scheduledAt,
                templateId, templateName;
        private List<String> cc, bcc;
        private Map<String, String> headers, variables;
        private final List<Attachment> attachments = new ArrayList<>();

        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private final Email e = new Email();
            public Builder from(String v) { e.from = v; return this; }
            public Builder to(String v) { e.to = v; return this; }
            public Builder subject(String v) { e.subject = v; return this; }
            public Builder html(String v) { e.html = v; return this; }
            public Builder text(String v) { e.text = v; return this; }
            public Builder replyTo(String v) { e.replyTo = v; return this; }
            public Builder cc(List<String> v) { e.cc = v; return this; }
            public Builder bcc(List<String> v) { e.bcc = v; return this; }
            public Builder templateId(String v) { e.templateId = v; return this; }
            public Builder variables(Map<String, String> v) { e.variables = v; return this; }
            public Builder scheduledAt(String iso) { e.scheduledAt = iso; return this; }
            public Builder attach(String filename, byte[] content, String contentType) {
                e.attachments.add(new Attachment(filename,
                        Base64.getEncoder().encodeToString(content),
                        contentType == null ? "application/octet-stream" : contentType));
                return this;
            }
            public Email build() { return e; }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            put(m, "from", from); put(m, "to", to); put(m, "to_name", toName);
            put(m, "subject", subject); put(m, "html", html); put(m, "text", text);
            put(m, "reply_to", replyTo); put(m, "cc", cc); put(m, "bcc", bcc);
            put(m, "scheduled_at", scheduledAt); put(m, "template_id", templateId);
            put(m, "template_name", templateName); put(m, "headers", headers);
            put(m, "variables", variables);
            if (!attachments.isEmpty()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Attachment a : attachments) {
                    list.add(Map.of("filename", a.filename, "content_b64", a.contentB64,
                            "content_type", a.contentType));
                }
                m.put("attachments", list);
            }
            return m;
        }

        private static void put(Map<String, Object> m, String k, Object v) {
            if (v != null) m.put(k, v);
        }
    }

    public record Attachment(String filename, String contentB64, String contentType) { }

    public static final class SendResult {
        public String id, status, to;
    }

    public static final class BatchResult {
        public java.util.List<SendResult> results;
    }

    public static final class SendFlitException extends Exception {
        public final int status;
        public final String body;

        public SendFlitException(String message, int status, String body) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }

    // Minimal JSON (send only what we control; parse via lenient regex-free mapper)
    static final class Json {
        static String write(Map<String, Object> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(str(e.getKey())).append(":").append(val(e.getValue()));
            }
            return sb.append("}").toString();
        }

        private static String val(Object v) {
            if (v == null) return "null";
            if (v instanceof String || v instanceof java.time.Duration) return str(v.toString());
            if (v instanceof Number || v instanceof Boolean) return v.toString();
            if (v instanceof List<?> l) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object o : l) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(val(o));
                }
                return sb.append("]").toString();
            }
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                return write(mm);
            }
            return str(v.toString());
        }

        private static String str(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.append("\"").toString();
        }

        static Object read(String s) { return new Parser(s).parse(); }

        @SuppressWarnings("unchecked")
        static <T> T read(String s, Class<T> type) {
            Object o = read(s);
            if (type == SendResult.class) {
                SendResult r = new SendResult();
                Map<String, Object> m = (Map<String, Object>) o;
                r.id = (String) m.get("id");
                r.status = (String) m.get("status");
                r.to = (String) m.get("to");
                return (T) r;
            }
            if (type == BatchResult.class) {
                BatchResult r = new BatchResult();
                r.results = new ArrayList<>();
                Map<String, Object> m = (Map<String, Object>) o;
                for (Object item : (List<Object>) m.getOrDefault("results", List.of())) {
                    r.results.add(read(Json.write((Map<String, Object>) item), SendResult.class));
                }
                return (T) r;
            }
            return (T) o;
        }

        static final class Parser {
            private final String s;
            private int i = 0;

            Parser(String s) { this.s = s; }

            Object parse() {
                skip();
                if (i >= s.length()) return null;
                char c = s.charAt(i);
                if (c == '{') return object();
                if (c == '[') return array();
                if (c == '"') return string();
                return number();
            }

            private Map<String, Object> object() {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                i++; skip();
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                while (i < s.length()) {
                    skip();
                    String k = string();
                    skip();
                    i++; // :
                    m.put(k, parse());
                    skip();
                    if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                    break;
                }
                i++; // }
                return m;
            }

            private List<Object> array() {
                List<Object> l = new ArrayList<>();
                i++; skip();
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                while (i < s.length()) {
                    l.add(parse());
                    skip();
                    if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                    break;
                }
                i++;
                return l;
            }

            private String string() {
                i++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case 'u' -> {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> sb.append(e);
                        }
                    } else if (c == '"') break;
                    else sb.append(c);
                }
                return sb.toString();
            }

            private Object number() {
                int start = i;
                while (i < s.length() && ",}] \n".indexOf(s.charAt(i)) < 0) i++;
                String tok = s.substring(start, i);
                try { return Long.parseLong(tok); } catch (Exception e1) {
                    try { return Double.parseDouble(tok); } catch (Exception e2) {
                        if ("true".equals(tok)) return Boolean.TRUE;
                        if ("false".equals(tok)) return Boolean.FALSE;
                        return tok;
                    }
                }
            }

            private void skip() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }
        }
    }
}
