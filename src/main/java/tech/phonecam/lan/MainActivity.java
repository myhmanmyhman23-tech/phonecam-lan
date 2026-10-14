package tech.phonecam.lan;

import android.Manifest;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.*;
import android.view.*;
import android.widget.*;
import java.net.Inet4Address;
import java.security.SecureRandom;

public class MainActivity extends Activity {
    private TextView status, address;
    private String token;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(CameraService.status);
            address.setText(url());
            ui.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getPreferences(0).getString("token", "");
        if (token.isEmpty()) {
            byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
            StringBuilder value = new StringBuilder();
            for (byte b : random) value.append(String.format("%02x", b & 255));
            token = value.toString();
            getPreferences(0).edit().putString("token", token).apply();
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(32, 48, 32, 24);
        box.setBackgroundColor(Color.rgb(240, 244, 248));
        setContentView(box);
        TextView title = new TextView(this); title.setText("PhoneCam LAN"); title.setTextSize(28); box.addView(title);
        TextView help = new TextView(this);
        help.setText("Телефон и ПК — в одном Wi-Fi. Нажми «Начать», дождись статуса камеры, затем блокируй экран.\n\nВ OBS: Медиаисточник → отключить «Локальный файл» → вставить ссылку → формат mpjpeg. Затем «Запустить виртуальную камеру».\n\nСсылка даёт доступ к видео. Не публикуй её. HTTP предназначен только для доверенного домашнего Wi-Fi.");
        help.setTextSize(16); box.addView(help);
        status = new TextView(this); status.setTextSize(18); box.addView(status);
        address = new TextView(this); address.setTextIsSelectable(true); box.addView(address);
        Button copy = new Button(this); copy.setText("Скопировать ссылку OBS"); box.addView(copy);
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PhoneCam", url()));
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show();
        });
        Button start = new Button(this); start.setText("Начать трансляцию"); box.addView(start);
        start.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1); return;
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            if (wifiAddress() == null) { Toast.makeText(this, "Подключи телефон к Wi-Fi", Toast.LENGTH_LONG).show(); return; }
            try { startForegroundService(new Intent(this, CameraService.class).putExtra("token", token).putExtra("bind", wifiAddress())); }
            catch (RuntimeException e) { status.setText("Не удалось запустить: " + e.getMessage()); }
        });
        Button stop = new Button(this); stop.setText("Остановить"); box.addView(stop);
        stop.setOnClickListener(v -> stopService(new Intent(this, CameraService.class)));
        TextView note = new TextView(this);
        note.setText("Первая версия: задняя камера, около 640×480, до 10 кадров/с, без звука. На Redmi/MIUI может понадобиться режим батареи «Без ограничений». После ошибки камеры разблокируй телефон и запусти снова."); box.addView(note);
    }
    private String wifiAddress() {
        ConnectivityManager manager = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties links = manager.getLinkProperties(network);
            if (links == null) continue;
            for (LinkAddress a : links.getLinkAddresses())
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }
    private String url() { String ip = wifiAddress(); return ip == null ? "Нет Wi-Fi" : "http://" + ip + ":8080/stream?token=" + token; }
    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }
}
