package com.liskovsoft.smartyoutubetv2.common.prio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prio.PrioData.Channel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Fork: small web server to edit the prio channels on a phone or PC. The address is shown as QR code on the TV.<br/>
 * Runs only while the dialog is open. Access needs the random token from the address.
 */
public class PrioWebEditor {
    private static final String TAG = PrioWebEditor.class.getSimpleName();
    private static final int PORT = 8765;
    private static final int QR_SIZE_PX = 400;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    @SuppressLint("StaticFieldLeak")
    private static PrioWebEditor sInstance;
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ServerSocket mServer;
    private String mToken;

    private PrioWebEditor(Context context) {
        mContext = context;
    }

    public static PrioWebEditor instance(Context context) {
        if (sInstance == null) {
            sInstance = new PrioWebEditor(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * @param activityContext needed for the dialog. Must stay alive while the dialog is shown.
     * @param onClose called after the dialog is closed (e.g. to show the changes)
     */
    public void show(Context activityContext, @Nullable Runnable onClose) {
        String url;

        try {
            url = start();
        } catch (IOException e) {
            MessageHelpers.showLongMessage(mContext, mContext.getString(R.string.prio_web_error, e.getMessage()));
            return;
        }

        LinearLayout layout = new LinearLayout(activityContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = QR_SIZE_PX / 10;
        layout.setPadding(padding, padding, padding, 0);

        Bitmap qrCode = createQrCode(url);
        if (qrCode != null) {
            ImageView image = new ImageView(activityContext);
            image.setImageBitmap(qrCode);
            layout.addView(image, new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX));
        }

        TextView message = new TextView(activityContext);
        message.setText(mContext.getString(R.string.prio_web_message, url));
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        message.setPadding(0, padding / 2, 0, 0);
        layout.addView(message);

        AlertDialog dialog = new AlertDialog.Builder(activityContext, R.style.AppDialog)
                .setTitle(R.string.prio_web_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, which) -> d.dismiss())
                .create();
        dialog.setOnDismissListener(d -> {
            stop();
            if (onClose != null) {
                onClose.run();
            }
        });
        dialog.show();
    }

    private String start() throws IOException {
        stop();

        mToken = createToken();
        mServer = new ServerSocket();
        mServer.setReuseAddress(true);

        try {
            mServer.bind(new InetSocketAddress(PORT));
        } catch (IOException e) {
            mServer.bind(new InetSocketAddress(0)); // port in use
        }

        ServerSocket server = mServer;
        Thread thread = new Thread(() -> acceptLoop(server), TAG);
        thread.setDaemon(true);
        thread.start();

        return "http://" + getIpAddress() + ":" + server.getLocalPort() + "/?token=" + mToken;
    }

    private void stop() {
        if (mServer != null) {
            try {
                mServer.close();
            } catch (IOException e) {
                // ignore
            }
            mServer = null;
        }
    }

    private void acceptLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread thread = new Thread(() -> handle(socket), TAG + "-client");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                // closed
            }
        }
    }

    // region HTTP

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }

            String method = parts[0];
            String target = parts[1];
            int question = target.indexOf('?');
            String path = question != -1 ? target.substring(0, question) : target;
            Map<String, String> query = parseQuery(question != -1 ? target.substring(question + 1) : "");

            if (!mToken.equals(query.get("token"))) {
                send(out, 403, "text/plain", "Forbidden");
            } else if ("GET".equals(method) && "/".equals(path)) {
                send(out, 200, "text/html; charset=utf-8", readRaw(R.raw.prio_editor));
            } else if ("GET".equals(method) && "/api/channels".equals(path)) {
                send(out, 200, "application/json; charset=utf-8", runOnMain(this::getChannelsJson));
            } else if ("POST".equals(method) && "/api/channels".equals(path) && contentLength <= MAX_BODY_BYTES) {
                String body = new String(readBytes(in, contentLength), "UTF-8");
                runOnMain(() -> applyChannelsJson(body));
                send(out, 200, "application/json; charset=utf-8", runOnMain(this::getChannelsJson));
            } else {
                send(out, 404, "text/plain", "Not found");
            }
        } catch (Exception e) {
            Log.e(TAG, "Request error: %s", e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private static void send(OutputStream out, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Not Found";
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes("ISO-8859-1"));
        out.write(bytes);
        out.flush();
    }

    @Nullable
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;

        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }

        return b == -1 && line.size() == 0 ? null : line.toString("ISO-8859-1");
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int read = 0;

        while (read < length) {
            int count = in.read(result, read, length - read);
            if (count == -1) {
                break;
            }
            read += count;
        }

        return result;
    }

    private static Map<String, String> parseQuery(String query) throws IOException {
        Map<String, String> result = new HashMap<>();

        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                result.put(URLDecoder.decode(pair.substring(0, equals), "UTF-8"), URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
            }
        }

        return result;
    }

    private String readRaw(int resId) throws IOException {
        InputStream in = mContext.getResources().openRawResource(resId);

        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                result.write(buffer, 0, count);
            }
            return result.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /**
     * Prio data isn't thread safe. Access it on the main thread only.
     */
    private <T> T runOnMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        mHandler.post(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    // endregion

    // region Data

    private String getChannelsJson() throws JSONException {
        PrioService service = PrioService.instance(mContext);

        JSONArray tiers = new JSONArray();
        for (int tier : Channel.TIERS) {
            tiers.put(new JSONObject().put("id", tier).put("name", PrioMenu.getTierName(mContext, tier)));
        }

        JSONArray channels = new JSONArray();
        for (Channel channel : PrioMenu.getSortedChannels(service)) {
            channels.put(new JSONObject()
                    .put("id", channel.channelId)
                    .put("name", PrioMenu.getName(channel))
                    .put("tier", channel.tier)
                    .put("img", channel.cardImageUrl != null ? channel.cardImageUrl : ""));
        }

        return new JSONObject()
                .put("title", mContext.getString(R.string.prio_manage_channels))
                .put("labels", new JSONObject()
                        .put("search", mContext.getString(R.string.prio_web_search))
                        .put("save", mContext.getString(R.string.prio_web_save))
                        .put("saved", mContext.getString(R.string.prio_web_saved))
                        .put("changes", mContext.getString(R.string.prio_web_changes))
                        .put("channels", mContext.getString(R.string.prio_web_channels)))
                .put("tiers", tiers)
                .put("channels", channels)
                .toString();
    }

    /**
     * Body: [{"id": channelId, "tier": 0..2}] in the new order. Channels that are missing get removed.
     */
    private Void applyChannelsJson(String body) throws JSONException {
        JSONArray channels = new JSONArray(body);
        List<String> channelIds = new ArrayList<>();
        Map<String, Integer> tiers = new HashMap<>();

        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            int tier = channel.getInt("tier");
            channelIds.add(channel.getString("id"));
            tiers.put(channel.getString("id"), Math.max(Channel.TIER_HIGH, Math.min(tier, Channel.TIER_LOW)));
        }

        PrioService.instance(mContext).setChannels(channelIds, tiers);
        MessageHelpers.showMessage(mContext, R.string.prio_web_saved);

        return null;
    }

    // endregion

    private static String createToken() {
        String chars = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < 10; i++) {
            result.append(chars.charAt(random.nextInt(chars.length())));
        }

        return result.toString();
    }

    /**
     * Local network address (Wi-Fi or Ethernet)
     */
    private static String getIpAddress() {
        String fallback = "127.0.0.1";

        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());

            for (NetworkInterface networkInterface : interfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        if (address.isSiteLocalAddress()) {
                            return address.getHostAddress();
                        }
                        fallback = address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Can't get ip address: %s", e.getMessage());
        }

        return fallback;
    }

    @Nullable
    private static Bitmap createQrCode(String text) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            Log.e(TAG, "Can't create QR code: %s", e.getMessage());
            return null;
        }
    }
}
