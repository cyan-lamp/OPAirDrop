package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** HTTP framing checks for the receive path; runs on a plain JVM. */
public final class HttpTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        requestLineAndHeaders();
        repeatedHeadersMerge();
        strayCrlfTolerated();
        contentLengthBodyIsExact();
        chunkedBodyDechunks();
        chunkedBodySingleByteReads();
        chunkedWinsOverContentLength();
        expectsContinueDetected();
        keepAliveRules();
        missingLengthMeansEmptyBody();
        truncatedBodyRejected();
        bodyLimitRejected();
        badRequestLineRejected();
        truncatedHeadRejected();
        cleanEndOfStreamReturnsNull();
        responsesAreWellFormed();
        System.out.println("HttpTest OK (" + checks + " checks)");
    }

    /** The exact response bytes matter: a peer waits on the 100 before sending. */
    private static void responsesAreWellFormed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendContinue(out);
        eq("HTTP/1.1 100 Continue\r\n\r\n",
            new String(out.toByteArray(), StandardCharsets.ISO_8859_1), "bare continue");

        out.reset();
        HttpResponse.send(out, "plist".getBytes(StandardCharsets.US_ASCII), true);
        Map<String, String> headers = HttpResponse.parseHead(out.toByteArray());
        String text = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        check(text.startsWith("HTTP/1.1 200 OK\r\n"), "status line");
        eq("AirDrop/1.0", headers.get("server"), "server header");
        eq("application/octet-stream", headers.get("content-type"), "content type");
        eq("5", headers.get("content-length"), "content length");
        eq("keep-alive", headers.get("connection"), "keep-alive");
        check(text.endsWith("\r\n\r\nplist"), "body follows the blank line");

        out.reset();
        HttpResponse.sendEmpty(out, 406, false);
        Map<String, String> empty = HttpResponse.parseHead(out.toByteArray());
        eq("0", empty.get("content-length"), "empty body length");
        eq("close", empty.get("connection"), "close on rejection");

        eq("Not Found", HttpResponse.reason(404), "reason phrase");
        eq("Payload Too Large", HttpResponse.reason(413), "reason phrase for 413");
    }

    private static void requestLineAndHeaders() throws Exception {
        String head = "POST /Ask HTTP/1.1\r\n"
            + "Host: 169.254.9.14:8770\r\n"
            + "User-Agent: AirDrop/1.0\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "Accept-Encoding: br, gzip, deflate\r\n"
            + "Content-Length: 5\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n";
        HttpRequest request = read(head + "hello");
        eq("POST", request.method, "method");
        eq("/Ask", request.path, "path");
        eq("HTTP/1.1", request.version, "version");
        eq("AirDrop/1.0", request.header("user-agent"), "lowercase lookup");
        eq("AirDrop/1.0", request.header("User-Agent"), "mixed-case lookup");
        eq("br, gzip, deflate", request.header("accept-encoding"), "value with commas");
        eq(5L, request.contentLength(), "content length");
        eq(null, request.header("x-missing"), "absent header");
    }

    /** Two headers of the same name arrive as one comma-joined value. */
    private static void repeatedHeadersMerge() throws Exception {
        String head = "GET /Discover HTTP/1.1\r\n"
            + "Connection: keep-alive\r\n"
            + "Connection: close\r\n"
            + "\r\n";
        HttpRequest request = read(head);
        eq("keep-alive, close", request.header("connection"), "merged value");
        check(!request.keepAlive(), "close wins in merged value");
    }

    /** Senders may leave an empty line between a finished body and the next request. */
    private static void strayCrlfTolerated() throws Exception {
        HttpRequest request = read("\r\n\r\nGET /Discover HTTP/1.1\r\n\r\n");
        check(request != null, "request after stray CRLF");
        eq("/Discover", request.path, "path after stray CRLF");
    }

    private static void contentLengthBodyIsExact() throws Exception {
        String head = "POST /Discover HTTP/1.1\r\nContent-Length: 4\r\n\r\n";
        InputStream in = stream(head + "plstTRAILING");
        HttpRequest request = HttpRequest.read(in);
        eq("plst", new String(request.readBody(in, 1024), StandardCharsets.US_ASCII), "bounded body");
    }

    private static void chunkedBodyDechunks() throws Exception {
        String head = "POST /Upload HTTP/1.1\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "Content-Type: application/x-cpio\r\n"
            + "\r\n";
        String body = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n";
        InputStream in = stream(head + body);
        HttpRequest request = HttpRequest.read(in);
        check(request.isChunked(), "chunked detected");
        eq("Wikipedia", new String(readAll(request.body(in)), StandardCharsets.US_ASCII), "chunked body");
    }

    /** The single-byte path must respect chunk boundaries too. */
    private static void chunkedBodySingleByteReads() throws Exception {
        String head = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n";
        String body = "3\r\nabc\r\n1\r\nd\r\n0\r\n\r\n";
        InputStream in = stream(head + body);
        HttpRequest request = HttpRequest.read(in);
        InputStream stream = request.body(in);
        StringBuilder text = new StringBuilder();
        int b;
        while ((b = stream.read()) >= 0) text.append((char) b);
        eq("abcd", text.toString(), "byte-at-a-time body");
    }

    /** A chunked body ignores a bogus Content-Length rather than truncating. */
    private static void chunkedWinsOverContentLength() throws Exception {
        String head = "POST /Upload HTTP/1.1\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "Content-Length: 2\r\n"
            + "\r\n";
        InputStream in = stream(head + "6\r\nabcdef\r\n0\r\n\r\n");
        HttpRequest request = HttpRequest.read(in);
        eq("abcdef", new String(readAll(request.body(in)), StandardCharsets.US_ASCII), "chunked over length");
    }

    private static void expectsContinueDetected() throws Exception {
        String head = "POST /Upload HTTP/1.1\r\n"
            + "Expect: 100-continue\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "\r\n";
        HttpRequest request = read(head);
        check(request.expectsContinue(), "100-continue detected");

        HttpRequest plain = read("POST /Ask HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
        check(!plain.expectsContinue(), "no expectation on /Ask");
    }

    private static void keepAliveRules() throws Exception {
        check(!read("POST /Ask HTTP/1.0\r\n\r\n").keepAlive(), "1.0 defaults to close");
        check(read("POST /Ask HTTP/1.1\r\n\r\n").keepAlive(), "1.1 defaults to keep-alive");
        check(!read("POST /Ask HTTP/1.1\r\nConnection: close\r\n\r\n").keepAlive(), "explicit close");
        check(read("POST /Ask HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").keepAlive(), "1.0 opt-in");
    }

    /** Apple sends no length on some control requests; the body is then empty. */
    private static void missingLengthMeansEmptyBody() throws Exception {
        InputStream in = stream("POST /Discover HTTP/1.1\r\nHost: x\r\n\r\n");
        HttpRequest request = HttpRequest.read(in);
        eq(-1L, request.contentLength(), "no length");
        eq(0, request.readBody(in, 1024).length, "empty body");
    }

    private static void truncatedBodyRejected() throws Exception {
        InputStream in = stream("POST /Ask HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc");
        HttpRequest request = HttpRequest.read(in);
        try {
            request.readBody(in, 1024);
            throw new AssertionError("failed: short body accepted");
        } catch (IOException expected) {
            checks++;
            check(expected.getMessage().contains("truncated"), "truncation reported");
        }
    }

    private static void bodyLimitRejected() throws Exception {
        InputStream in = stream("POST /Ask HTTP/1.1\r\nContent-Length: 100\r\n\r\n" + repeat('x', 100));
        HttpRequest request = HttpRequest.read(in);
        try {
            request.readBody(in, 50);
            throw new AssertionError("failed: oversized body accepted");
        } catch (IOException expected) {
            checks++;
            check(expected.getMessage().contains("exceeds"), "limit reported");
        }
    }

    private static void badRequestLineRejected() throws Exception {
        try {
            HttpRequest.read(stream("GARBAGE\r\n\r\n"));
            throw new AssertionError("failed: malformed request line accepted");
        } catch (IOException expected) {
            checks++;
            check(expected.getMessage().contains("bad request line"), "message");
        }
    }

    private static void truncatedHeadRejected() throws Exception {
        try {
            HttpRequest.read(stream("GET /Discover HTTP/1.1\r\nHost: x\r\n"));
            throw new AssertionError("failed: truncated head accepted");
        } catch (IOException expected) {
            checks++;
            check(expected.getMessage().contains("headers truncated"), "message");
        }
    }

    private static void cleanEndOfStreamReturnsNull() throws Exception {
        eq(null, HttpRequest.read(stream("")), "empty stream");
        eq(null, HttpRequest.read(stream("\r\n")), "blank lines only");
    }

    private static HttpRequest read(String raw) throws IOException {
        // Body bytes are irrelevant here, so the same stream is discarded.
        return HttpRequest.read(stream(raw));
    }

    private static InputStream stream(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer, 0, buffer.length)) >= 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("failed: " + what);
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private HttpTest() {
    }
}
