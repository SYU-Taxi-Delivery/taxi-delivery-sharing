package com.school.party.domain.user;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** Local stand-in for Brevo's POST /v3/smtp/email. Records each request and answers with the configured status. */
final class FakeBrevo implements AutoCloseable {
    record Request(String method, String path, String apiKey, String contentType, String body) { }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final CountDownLatch never = new CountDownLatch(1);
    private volatile int status = 201;
    private volatile String responseBody = "{\"messageId\":\"<test@brevo>\"}";
    private volatile boolean silent;

    FakeBrevo() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-brevo");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("api-key"), exchange.getRequestHeaders().getFirst("content-type"), body));
            if (silent) {
                try { never.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                exchange.close();
                return;
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    void answer(int status, String body) { this.status = status; this.responseBody = body; this.silent = false; }

    /** Accept the request and never answer. */
    void goSilent() { this.silent = true; }

    void reset() { requests.clear(); answer(201, "{\"messageId\":\"<test@brevo>\"}"); }

    @Override
    public void close() {
        never.countDown();
        server.stop(0);
    }
}
