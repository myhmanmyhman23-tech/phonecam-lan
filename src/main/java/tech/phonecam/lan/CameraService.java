package tech.phonecam.lan;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;

public class CameraService extends Service {
    public static volatile String status = "Трансляция остановлена";
    public static volatile boolean running;
    private HandlerThread worker;
    private Handler handler;
    private MjpegServer server;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private PowerManager.WakeLock wake;
    private volatile boolean stopped;
    private int orientation;
    private boolean frontCamera;
    private long lastFrame;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (worker != null) return START_NOT_STICKY;
        try {
            running = true;
            frontCamera = intent.getBooleanExtra("front", true);
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("camera", "Трансляция камеры", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CameraService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "camera")
                .setContentTitle("PhoneCam: камера включена")
                .setContentText("Передача по Wi-Fi. Нажми «Остановить» для отключения.")
                .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            else startForeground(1, notification);
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Нет разрешения камеры");
            status = "Подключение камеры…";
            wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonecam:stream");
            wake.acquire(4 * 60 * 60 * 1000L);
            worker = new HandlerThread("phonecam-camera"); worker.start(); handler = new Handler(worker.getLooper());
            server = new MjpegServer(intent.getStringExtra("bind"), 8080, intent.getStringExtra("token"));
            handler.post(() -> { try { openCamera(); } catch (Exception e) { fail(e.getMessage()); } });
            handler.postDelayed(() -> { if (!stopped) fail("Сеанс завершён через 4 часа. Запусти заново."); }, 4 * 60 * 60 * 1000L);
        } catch (Exception e) { fail(e.getMessage()); }
        return START_NOT_STICKY;
    }
    private void openCamera() throws CameraAccessException {
        if (stopped) return;
        CameraManager manager = getSystemService(CameraManager.class);
        String selected = null;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            int wanted = frontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            if (facing != null && facing == wanted) { selected = id; break; }
        }
        if (selected == null) throw new IllegalStateException((frontCamera ? "Передняя" : "Задняя") + " камера не найдена");
        CameraCharacteristics info = manager.getCameraCharacteristics(selected);
        int[] focusModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        boolean continuousFocus = focusModes != null && Arrays.stream(focusModes).anyMatch(mode -> mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        Integer angle = info.get(CameraCharacteristics.SENSOR_ORIENTATION); orientation = angle == null ? 0 : angle;
        Size[] sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("Нет формата YUV");
        Size size = Arrays.stream(sizes).min(Comparator.comparingLong(s -> Math.abs((long)s.getWidth() * s.getHeight() - 640L * 480))).get();
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image == null || stopped) return;
                long now = SystemClock.elapsedRealtime(); if (now - lastFrame < 100) return; lastFrame = now;
                byte[] nv21 = nv21(image);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null)
                    .compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 70, buffer);
                byte[] jpeg = buffer.toByteArray();
                if (orientation != 0) {
                    Bitmap original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    Matrix matrix = new Matrix(); matrix.postRotate(orientation);
                    Bitmap rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
                    buffer.reset(); rotated.compress(Bitmap.CompressFormat.JPEG, 70, buffer); jpeg = buffer.toByteArray();
                    if (rotated != original) rotated.recycle(); original.recycle();
                }
                server.publish(jpeg); status = (frontCamera ? "Передняя" : "Задняя") + " камера работает · " + size.getWidth() + "×" + size.getHeight() + " · до 10 кадров/с";
            } catch (Exception e) { if (!stopped) fail("Ошибка кадра: " + e.getMessage()); }
        }, handler);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Разрешение камеры отозвано");
        manager.openCamera(selected, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice device) {
                if (stopped) { device.close(); return; }
                camera = device;
                try {
                    device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession capture) {
                            if (stopped) { capture.close(); return; }
                            session = capture;
                            try {
                                CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(reader.getSurface());
                                if (continuousFocus) request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                capture.setRepeatingRequest(request.build(), null, handler);
                            } catch (Exception e) { fail(e.getMessage()); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession capture) { capture.close(); fail("Камера не смогла начать захват"); }
                    }, handler);
                } catch (Exception e) { fail(e.getMessage()); }
            }
            @Override public void onDisconnected(CameraDevice device) { device.close(); if (!stopped) fail("Камера отключена системой"); }
            @Override public void onError(CameraDevice device, int error) { device.close(); if (!stopped) fail("Ошибка камеры " + error); }
        }, handler);
    }
    private static byte[] nv21(Image image) {
        int w = image.getWidth(), h = image.getHeight(); byte[] result = new byte[w * h * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        for (int p = 0; p < 3; p++) {
            ByteBuffer data = planes[p].getBuffer(); int base = data.position();
            int width = p == 0 ? w : w / 2, height = p == 0 ? h : h / 2;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int target = p == 0 ? y * w + x : w * h + 2 * (y * width + x) + (p == 1 ? 1 : 0);
                result[target] = data.get(base + y * planes[p].getRowStride() + x * planes[p].getPixelStride());
            }
        }
        return result;
    }
    private void fail(String reason) { status = "Остановлено: " + reason; new Handler(Looper.getMainLooper()).post(this::stopSelf); }
    @Override public void onDestroy() {
        stopped = true;
        running = false;
        if (server != null) server.close();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(() -> {
                if (session != null) session.close();
                if (camera != null) camera.close();
                if (reader != null) reader.close();
                worker.quitSafely();
            });
        }
        if (wake != null && wake.isHeld()) wake.release();
        if (!status.startsWith("Остановлено:")) status = "Трансляция остановлена";
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
