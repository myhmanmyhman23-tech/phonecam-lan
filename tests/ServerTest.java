package tech.phonecam.lan;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.concurrent.TimeUnit;

public class ServerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static Socket request(int port, String path) throws Exception {
        Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(3000);
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        return socket;
    }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "jpeg", encoded);
        byte[] jpeg = encoded.toByteArray();
        try (MjpegServer server = new MjpegServer("127.0.0.1", 0, TOKEN)) {
            try (Socket denied = request(server.port(), "/stream?token=wrong")) {
                String reply = new String(denied.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                check(reply.startsWith("HTTP/1.1 403"), "Unauthenticated access must fail");
            }
            server.publish(jpeg);
            try (Socket stream = request(server.port(), "/stream?token=" + TOKEN)) {
                InputStream in = stream.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                int boundaries = 0;
                while (boundaries < 2) {
                    header.write(in.read());
                    String text = header.toString("US-ASCII");
                    boundaries = text.split("\r\n\r\n", -1).length - 1;
                    check(header.size() < 2048, "Bounded response header");
                }
                check(header.toString("US-ASCII").contains("Content-Length: " + jpeg.length), "Correct part length");
                check(java.util.Arrays.equals(in.readNBytes(jpeg.length), jpeg), "Exact JPEG bytes");
            }
            if (args.length > 0 && args[0].equals("ffmpeg")) {
                Process decoder = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "mpjpeg", "-i",
                    "http://127.0.0.1:" + server.port() + "/stream?token=" + TOKEN,
                    "-frames:v", "1", "-f", "null", "-").inheritIO().start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (decoder.isAlive() && System.nanoTime() < deadline) { server.publish(jpeg); Thread.sleep(100); }
                if (decoder.isAlive()) { decoder.destroyForcibly(); throw new AssertionError("FFmpeg timeout"); }
                check(decoder.exitValue() == 0, "FFmpeg must decode the stream");
            }
            Socket idle = new Socket("127.0.0.1", server.port());
            Thread.sleep(100); server.close();
            idle.setSoTimeout(2000); check(idle.getInputStream().read() == -1, "Stop closes idle clients"); idle.close();
        }
        System.out.println("PASS: authentication, multipart JPEG, shutdown" + (args.length > 0 ? ", FFmpeg decoding" : ""));
    }
}
