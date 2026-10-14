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
import java.util.ArrayList;

public class MainActivity extends Activity {
    private TextView status, address;
    private RadioButton front, rear;
    private Button start;
    private boolean pendingStart;
    private String token;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(CameraService.status);
            address.setText(url());
            front.setEnabled(!CameraService.running);
            rear.setEnabled(!CameraService.running);
            start.setEnabled(!CameraService.running);
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
        ScrollView scroll = new ScrollView(this); scroll.addView(box); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        scroll.requestApplyInsets();
        TextView title = new TextView(this); title.setText("PhoneCam LAN"); title.setTextSize(28); box.addView(title);
        TextView help = new TextView(this);
        help.setText("Телефон и ПК — в одном Wi-Fi. Нажми «Начать», дождись статуса камеры, затем блокируй экран.\n\nВ OBS: Медиаисточник → отключить «Локальный файл» → вставить ссылку → формат mpjpeg. Затем «Запустить виртуальную камеру».\n\nСсылка даёт доступ к видео. Не публикуй её. HTTP предназначен только для доверенного домашнего Wi-Fi.");
        help.setTextSize(16); box.addView(help);
        status = new TextView(this); status.setTextSize(18); box.addView(status);
        TextView cameraHelp = new TextView(this);
        cameraHelp.setText("Выбери камеру. Для смены во время трансляции: останови, выбери другую и снова нажми «Начать». Ссылка OBS не изменится.");
        box.addView(cameraHelp);
        RadioGroup cameras = new RadioGroup(this); cameras.setOrientation(RadioGroup.HORIZONTAL);
        front = new RadioButton(this); front.setId(View.generateViewId()); front.setText("Передняя"); cameras.addView(front);
        rear = new RadioButton(this); rear.setId(View.generateViewId()); rear.setText("Задняя"); cameras.addView(rear);
        cameras.check(getPreferences(0).getBoolean("front", true) ? front.getId() : rear.getId());
        cameras.setOnCheckedChangeListener((group, checked) -> getPreferences(0).edit().putBoolean("front", checked == front.getId()).apply());
        box.addView(cameras);
        address = new TextView(this); address.setTextIsSelectable(true); box.addView(address);
        Button copy = new Button(this); copy.setText("Скопировать ссылку OBS"); box.addView(copy);
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PhoneCam", url()));
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show();
        });
        start = new Button(this); start.setText("Начать трансляцию"); box.addView(start);
        start.setOnClickListener(v -> beginStream());
        Button stop = new Button(this); stop.setText("Остановить"); box.addView(stop);
        stop.setOnClickListener(v -> stopService(new Intent(this, CameraService.class)));
        TextView note = new TextView(this);
        note.setText("Передняя или задняя камера, около 640×480, до 10 кадров/с, без звука. На Redmi/MIUI может понадобиться режим батареи «Без ограничений». После ошибки камеры разблокируй телефон и запусти снова."); box.addView(note);
    }
    private void beginStream() {
        ArrayList<String> required = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.ACCESS_LOCAL_NETWORK);
        if (!required.isEmpty()) {
            pendingStart = true;
            requestPermissions(required.toArray(new String[0]), 1); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(0).getBoolean("notificationAsked", false)) {
            getPreferences(0).edit().putBoolean("notificationAsked", true).apply();
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2); return;
        }
        String bind = wifiAddress();
        if (bind == null) { Toast.makeText(this, "Подключи телефон к Wi-Fi", Toast.LENGTH_LONG).show(); return; }
        try {
            startForegroundService(new Intent(this, CameraService.class).putExtra("token", token).putExtra("bind", bind).putExtra("front", front.isChecked()));
        } catch (RuntimeException e) { CameraService.status = "Не удалось запустить: " + e.getMessage(); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if ((request == 1 || request == 2) && pendingStart) {
            pendingStart = false;
            boolean granted = results.length > 0;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (request == 2 || granted) beginStream();
            else CameraService.status = "Нужно разрешить камеру и доступ к локальной сети (Android 17+) в настройках приложения";
        }
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
