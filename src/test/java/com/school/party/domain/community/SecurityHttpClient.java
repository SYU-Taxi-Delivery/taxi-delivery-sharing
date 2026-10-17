package com.school.party.domain.community;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Sends real HTTP requests, retaining the cookies returned by the embedded servlet container. */
final class SecurityHttpClient implements AutoCloseable {
    final Map<String, String> cookies = new LinkedHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final int port;
    private final ObjectMapper mapper;

    SecurityHttpClient(int port, ObjectMapper mapper) { this.port = port; this.mapper = mapper; }

    HttpResponse<String> send(String method, String path, String body, Token csrf) throws Exception {
        return send(method, path, body, csrf, null);
    }

    HttpResponse<String> send(String method, String path, String body, Token csrf, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20));
        if (!cookies.isEmpty()) request.header("Cookie", cookies.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
        if (csrf != null) request.header(csrf.header(), csrf.value());
        if (authorization != null) request.header("Authorization", authorization);
        if (body != null) request.header("Content-Type", "application/json");
        var response = HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        for (String value : response.headers().allValues("Set-Cookie")) {
            for (HttpCookie cookie : HttpCookie.parse(value)) {
                if (cookie.getMaxAge() == 0) cookies.remove(cookie.getName());
                else cookies.put(cookie.getName(), cookie.getValue());
            }
        }
        return response;
    }

    Token csrf() throws Exception {
        var response = send("GET", "/api/csrf", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = mapper.readTree(response.body()).path("data");
        assertThat(cookies.get("XSRF-TOKEN")).isNotBlank();
        return new Token(data.path("headerName").asText(), data.path("token").asText(), cookies.get("XSRF-TOKEN"));
    }

    void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).path("error").path("code").asText()).isEqualTo(code);
    }

    // The test process owns the shared transport; each browser owns its independent cookie state.
    @Override public void close() { cookies.clear(); }
    record Token(String header, String value, String rawCookie) { }
}
