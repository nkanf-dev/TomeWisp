package dev.tomewisp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.tomewisp.model.CancellationSignal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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

    @Test
    void totalTimeoutClosesAResponseWhoseBodyNeverCompletes() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch decoderStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            JdkHttpTransport transport = new JdkHttpTransport(new HttpTransportPolicy(
                    Duration.ofSeconds(2), "test-http-timeout"));
            HttpExchangeRequest request = HttpExchangeRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/stalled"))
                    .timeout(Duration.ofMillis(150))
                    .get()
                    .build();
            long started = System.nanoTime();
            var result = transport.execute(
                    request,
                    new CancellationSignal(),
                    (status, headers, body) -> {
                        decoderStarted.countDown();
                        return body.readAllBytes().length;
                    });

            assertTrue(headersSent.await(1, TimeUnit.SECONDS));
            assertTrue(decoderStarted.await(1, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMillis >= 75, "watchdog fired before the configured budget");
            assertTrue(elapsedMillis < 1500, "watchdog did not bound the open response body");
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    @Test
    void explicitCancellationWinsBeforeTheTotalTimeout() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch decoderStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cancel", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            JdkHttpTransport transport = new JdkHttpTransport(new HttpTransportPolicy(
                    Duration.ofSeconds(2), "test-http-cancel"));
            HttpExchangeRequest request = HttpExchangeRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/cancel"))
                    .timeout(Duration.ofSeconds(2))
                    .get()
                    .build();
            CancellationSignal cancellation = new CancellationSignal();
            var result = transport.execute(
                    request, cancellation, (status, headers, body) -> {
                        decoderStarted.countDown();
                        return body.readAllBytes().length;
                    });

            assertTrue(headersSent.await(1, TimeUnit.SECONDS));
            assertTrue(decoderStarted.await(1, TimeUnit.SECONDS));
            cancellation.cancel();
            assertThrows(CancellationException.class, result::join);
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }
}
