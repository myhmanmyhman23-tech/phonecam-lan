package tech.phonecam.lan;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.*;

/** Small bounded HTTP MJPEG server. No web page, recording or remote controls. */
public final class MjpegServer implements AutoCloseable {
    private final ServerSocket listener;
    private final String token;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService clients = new ThreadPoolExecutor(0, 4, 10, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> new Thread(r, "phonecam-client"));
    private volatile boolean running = true;
    private byte[] frame;
    private long sequence;
    public MjpegServer(String bind, int port, String token) throws IOException {
        if (token == null || !token.matches("[a-f0-9]{48}")) throw new IllegalArgumentException("Invalid token");
        this.token = token;
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(InetAddress.getByName(bind), port), 4); }
        catch (IOException e) { listener.close(); clients.shutdownNow(); throw e; }
        new Thread(this::accept, "phonecam-listener").start();
    }
    public int port() { return listener.getLocalPort(); }
    public synchronized void publish(byte[] jpeg) {
        frame = jpeg; sequence++; notifyAll();
    }
    private void accept() {
        while (running) {
            try {
                Socket socket = listener.accept(); sockets.add(socket);
                if (!running) { sockets.remove(socket); socket.close(); break; }
                socket.setSoTimeout(4000); socket.setTcpNoDelay(true);
                try { clients.execute(() -> serve(socket)); }
                catch (RejectedExecutionException e) { sockets.remove(socket); socket.close(); }
            } catch (IOException e) { if (running) close(); }
        }
    }
    private String line(InputStream in) throws IOException {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        for (int i = 0; i < 2048; i++) {
            int b = in.read(); if (b < 0) throw new EOFException();
            if (b == '\n') return value.toString("US-ASCII").trim();
            value.write(b);
        }
        throw new IOException("Header too long");
    }
    private void serve(Socket socket) {
        try (Socket peer = socket) {
            InputStream in = peer.getInputStream(); OutputStream out = peer.getOutputStream();
            String request = line(in);
            boolean end = false;
            for (int i = 0; i < 32; i++) if (line(in).isEmpty()) { end = true; break; }
            if (!end) return;
            String[] parts = request.split(" ");
            if (parts.length != 3 || !parts[0].equals("GET")) { response(out, "400 Bad Request"); return; }
            String expected = "/stream?token=" + token;
            if (!MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) {
                response(out, "403 Forbidden"); return;
            }
            write(out, "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=phonecam\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.flush();
            long previous = -1;
            while (running) {
                byte[] jpeg;
                synchronized (this) {
                    while (running && (frame == null || previous == sequence)) wait(1000);
                    if (!running) break;
                    jpeg = frame; previous = sequence;
                }
                write(out, "--phonecam\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n");
                out.write(jpeg); write(out, "\r\n"); out.flush();
            }
        } catch (IOException ignored) {
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { sockets.remove(socket); }
    }
    private static void write(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.US_ASCII)); }
    private static void response(OutputStream out, String code) throws IOException {
        write(out, "HTTP/1.1 " + code + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); out.flush();
    }
    @Override public void close() {
        running = false;
        synchronized (this) { notifyAll(); }
        try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
    }
}
