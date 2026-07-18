package dev.tomewisp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.tomewisp.model.CancellationSignal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class JdkHttpTransportTest {
    @Test
    void usesJdkAsyncExchangeAndVirtualResponseDecoderWithoutFollowingRedirects()
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            byte[] body = "target".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JdkHttpTransport transport = new JdkHttpTransport(new HttpTransportPolicy(
                    Duration.ofSeconds(2), "test-http-decoder"));
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/redirect?token=must-not-render-query");

            HttpExchangeRequest request = HttpExchangeRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .header("Authorization", "Bearer must-not-render")
                    .get()
                    .build();
            assertTrue(!request.toString().contains("must-not-render"));

            String result = transport.execute(
                    request,
                    new CancellationSignal(),
                    (status, headers, body) -> {
                        assertTrue(Thread.currentThread().isVirtual());
                        assertEquals("test-http-decoder", Thread.currentThread().getName());
                        return status + ":" + new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    }).join();

            assertEquals("302:", result);
        } finally {
            server.stop(0);
        }
    }
}
