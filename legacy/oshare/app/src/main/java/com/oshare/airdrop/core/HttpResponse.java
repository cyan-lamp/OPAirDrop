package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The handful of responses the AirDrop endpoints emit.
 *
 * <p>Three shapes exist in practice, and the exact bytes matter because the peer
 * is waiting on them:
 *
 * <ul>
 *   <li>{@code 100 Continue} — must be flushed before an {@code /Upload} body
 *       arrives, otherwise the sender stalls until its timeout;</li>
 *   <li>{@code 200} with a plist body — {@code /Discover} and {@code /Ask};</li>
 *   <li>{@code 200} with {@code Content-Length: 0} and {@code Connection: close} —
 *       {@code /Upload}, which ends the exchange.</li>
 * </ul>
 */
public final class HttpResponse {
    public static final String SERVER = "AirDrop/1.0";

    private HttpResponse() {
    }

    public static String reason(int status) {
        switch (status) {
            case 100: return "Continue";
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 406: return "Not Acceptable";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            default: return status < 400 ? "OK" : "Error";
        }
    }

    /**
     * Answers {@code Expect: 100-continue}. RFC 9110 keeps 1xx responses free of
     * body framing, so this is a bare status line and an empty line.
     */
    public static void sendContinue(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 100 " + reason(100) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Sends a 200 with a binary plist body. */
    public static void send(OutputStream out, byte[] body, boolean keepAlive) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", AirDropProtocol.OCTET_STREAM);
        send(out, 200, headers, body, keepAlive);
    }

    /** Sends a response without a body, used for accept and error paths. */
    public static void sendEmpty(OutputStream out, int status, boolean keepAlive) throws IOException {
        send(out, status, new LinkedHashMap<>(), new byte[0], keepAlive);
    }

    /** Full control, for the upload answer that mirrors cpio's content type. */
    public static void send(OutputStream out, int status, Map<String, String> headers,
                            byte[] body, boolean keepAlive) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        ByteArrayOutputStream head = new ByteArrayOutputStream(160);
        ascii(head, "HTTP/1.1 " + status + " " + reason(status) + "\r\n");
        ascii(head, "Server: " + SERVER + "\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            ascii(head, header.getKey() + ": " + header.getValue() + "\r\n");
        }
        // A 1xx or 204 carries no framing, so Content-Length is omitted there.
        if (status != 100 && status != 204) {
            ascii(head, "Content-Length: " + payload.length + "\r\n");
        }
        ascii(head, "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
        ascii(head, "\r\n");

        out.write(head.toByteArray());
        if (payload.length > 0) out.write(payload);
        out.flush();
    }

    /** Header value lookup helper for tests: parses the head it just wrote. */
    static Map<String, String> parseHead(byte[] response) {
        String text = new String(response, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                lines[i].substring(colon + 1).trim());
        }
        return headers;
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        out.write(raw, 0, raw.length);
    }
}
