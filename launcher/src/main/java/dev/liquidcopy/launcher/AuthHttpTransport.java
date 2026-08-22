package dev.liquidcopy.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small HTTP boundary used by the authentication pipeline. */
public interface AuthHttpTransport {
    Response send(Request request) throws IOException, InterruptedException;

    record Request(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) {
        public Request {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(uri, "uri");
            headers = Map.copyOf(headers);
            body = body.clone();
            Objects.requireNonNull(timeout, "timeout");
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
        public Response {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    static AuthHttpTransport jdk() {
        final int maximumBodyBytes = 2 * 1024 * 1024 + 1;
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        return request -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
            request.headers().forEach(builder::header);
            if ("GET".equals(request.method())) {
                builder.GET();
            } else {
                builder.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
            }
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = input.readNBytes(maximumBodyBytes);
            }
            return new Response(response.statusCode(), new LinkedHashMap<>(response.headers().map()), body);
        };
    }
}
