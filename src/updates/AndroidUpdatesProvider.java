package org.influence.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same-package, same-certificate APK replacement. Never touches player data. */
public final class AndroidUpdatesProvider extends ContentProvider
        implements Application.ActivityLifecycleCallbacks {
    private static AndroidUpdatesProvider active;
    public interface InstallCallback { void complete(String error); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private Activity foreground;
    private String base;
    private boolean checked;
    private boolean permissionPending;
    private boolean automatic = true;
    private File ready;

    @Override public boolean onCreate() {
        active = this;
        try {
            JSONObject config;
            try (InputStream input = getContext().getAssets().open("influence-updates.json")) {
                config = new JSONObject(new String(read(input, 8192), "UTF-8"));
            }
            String channel = config.has("channels")
                    ? config.getJSONObject("channels").getString(getContext().getPackageName())
                    : config.getString("channel");
            automatic = config.optBoolean("autoCheck", true);
            if (!channel.matches("[a-z0-9/-]+")) throw new IOException("Invalid channel");
            base = "http://teddy-personal.cloud:22830/files/" + channel + "/";
            ((Application) getContext().getApplicationContext()).registerActivityLifecycleCallbacks(this);
        } catch (Exception error) {
            android.util.Log.w("InfluenceUpdates", "Updater configuration unavailable", error);
        }
        return true;
    }

    /** Flutter hands off only a verified APK in the provider's fixed cache path. */
    public static void installVerified(long expectedVersion, String expectedHash, InstallCallback callback) {
        AndroidUpdatesProvider provider = active;
        if (provider == null) { callback.complete("安装模块尚未就绪"); return; }
        provider.worker.execute(() -> {
            try {
                File apk = new File(provider.getContext().getCacheDir(), "influence-updates/update.apk");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new FileInputStream(apk)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                PackageInfo installed = provider.info(null), candidate = provider.info(apk);
                Set<String> current = signers(installed);
                if (!hex(digest.digest()).equalsIgnoreCase(expectedHash)
                        || !candidate.packageName.equals(installed.packageName)
                        || version(candidate) != expectedVersion || version(candidate) <= version(installed)
                        || current.isEmpty() || !current.equals(signers(candidate))) throw new IOException("安装包校验失败");
                provider.ready = apk;
                provider.main.post(() -> { provider.install(); callback.complete(null); });
            } catch (Exception error) {
                provider.main.post(() -> callback.complete("安装包或签名校验失败"));
            }
        });
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Response too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private HttpURLConnection connect(String address) throws Exception {
        URL url = new URL(address);
        URL allowed = new URL(base);
        if (!url.getProtocol().equals(allowed.getProtocol()) || !url.getHost().equals(allowed.getHost())
                || url.getPort() != allowed.getPort() || !url.getPath().startsWith(allowed.getPath())
                || url.getQuery() != null || url.getUserInfo() != null
                || url.getPath().contains("..")) throw new IOException("Unexpected update address");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (connection.getResponseCode() != 200) {
            connection.disconnect();
            throw new IOException("Update server unavailable");
        }
        return connection;
    }

    @SuppressWarnings("deprecation")
    private PackageInfo info(File apk) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageManager manager = getContext().getPackageManager();
        PackageInfo result = apk == null ? manager.getPackageInfo(getContext().getPackageName(), flags)
                : manager.getPackageArchiveInfo(apk.getPath(), flags);
        if (result == null) throw new IOException("Invalid APK");
        return result;
    }

    @SuppressWarnings("deprecation")
    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] certificates = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
                : info.signatures;
        Set<String> result = new HashSet<>();
        if (certificates != null) for (Signature certificate : certificates) {
            result.add(hex(MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())));
        }
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private boolean visible(Activity activity) {
        return activity != null && activity == foreground && !activity.isFinishing() && !activity.isDestroyed();
    }

    private JSONObject policy() {
        String channel = base.substring(base.indexOf("/files/") + 7, base.length() - 1);
        String id = channel.equals("ailiaili/phone") ? "ailiaili-phone"
                : channel.equals("ailiaili/pad") ? "ailiaili-pad" : channel + "-android";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://teddy-personal.cloud:22830/updates/" + id + ".json").openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return new JSONObject();
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(read(input, 16384), "UTF-8"));
            }
        } catch (Exception ignored) { return new JSONObject(); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private String productSlug() {
        String channel = base.substring(base.indexOf("/files/") + 7);
        return channel.split("/")[0];
    }

    private void website(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://teddy-personal.cloud:22830/projects/" + productSlug())));
        } catch (Exception ignored) {
            new AlertDialog.Builder(activity).setMessage("请使用浏览器访问 teddy-personal.cloud:22830 下载新版。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    private void check() {
        if (!busy.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                HttpURLConnection connection = connect(base + "android.json");
                JSONObject release;
                try (InputStream input = connection.getInputStream()) {
                    release = new JSONObject(new String(read(input, 65536), "UTF-8"));
                } finally { connection.disconnect(); }
                if (!release.getString("packageName").equals(getContext().getPackageName())
                        || release.getLong("versionCode") <= version(info(null))) return;
                JSONObject policy = policy();
                if (!policy.optBoolean("enabled", true)) return;
                String mode = policy.optString("mode", "auto");
                long bytes = release.optLong("bytes", 0);
                boolean useWebsite = mode.equals("website") || (mode.equals("auto")
                        && (bytes <= 0 || bytes > policy.optLong("maxDirectMB", 128) * 1024 * 1024));
                String message = policy.optString("message", "{product} {version}\n{notes}")
                        .replace("{product}", policy.optString("product", productSlug()))
                        .replace("{version}", release.optString("version"))
                        .replace("{notes}", release.optString("notes", "覆盖更新，保留应用数据。"));
                if (useWebsite) message += "\n请前往网站下载新版并覆盖安装。";
                final String description = message.substring(0, Math.min(2400, message.length()));
                main.post(() -> {
                    Activity activity = foreground;
                    if (!visible(activity)) { checked = false; return; }
                    new AlertDialog.Builder(activity).setTitle(policy.optString("title", "发现新版本"))
                            .setMessage(description)
                            .setNegativeButton("稍后", null)
                            .setPositiveButton(useWebsite ? "前往网站" : "下载更新", (dialog, which) -> {
                                if (useWebsite) website(activity); else download(activity, release);
                            }).show();
                });
            } catch (Exception error) {
                android.util.Log.i("InfluenceUpdates", "Update check unavailable: " + error.getClass().getSimpleName());
            } finally { busy.set(false); }
        });
    }

    private void download(Activity activity, JSONObject release) {
        if (!busy.compareAndSet(false, true)) return;
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("正在下载更新")
                .setMessage("下载并校验安装包，请稍候…").setCancelable(false).show();
        worker.execute(() -> {
            File partial = new File(getContext().getCacheDir(), "influence-updates/download.part");
            File apk = new File(partial.getParentFile(), "update.apk");
            try {
                long expected = release.getLong("bytes");
                if (expected < 1 || expected > 2L * 1024 * 1024 * 1024) throw new IOException("Invalid APK size");
                if (!partial.getParentFile().isDirectory() && !partial.getParentFile().mkdirs()) throw new IOException("Storage unavailable");
                if (partial.getParentFile().getUsableSpace() < expected + 32L * 1024 * 1024) throw new IOException("存储空间不足");
                HttpURLConnection connection = connect(release.getString("url"));
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                long lastProgress = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > expected) throw new IOException("APK exceeds expected size");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        if (System.currentTimeMillis() - lastProgress > 500) {
                            lastProgress = System.currentTimeMillis();
                            final long percent = total * 100 / expected;
                            main.post(() -> { if (visible(activity)) progress.setMessage("正在下载 " + percent + "%"); });
                        }
                    }
                    output.getFD().sync();
                } finally { connection.disconnect(); }
                if (total != expected || !hex(digest.digest()).equalsIgnoreCase(release.getString("sha256")))
                    throw new IOException("文件校验失败");
                PackageInfo current = info(null), candidate = info(partial);
                Set<String> installedSigners = signers(current);
                if (!candidate.packageName.equals(current.packageName) || version(candidate) != release.getLong("versionCode")
                        || version(candidate) <= version(current) || installedSigners.isEmpty()
                        || !installedSigners.equals(signers(candidate))) throw new IOException("安装包或签名不匹配");
                if (apk.exists() && !apk.delete()) throw new IOException("Cannot replace cached APK");
                if (!partial.renameTo(apk)) throw new IOException("Cannot commit cached APK");
                ready = apk;
                main.post(() -> { if (!activity.isDestroyed()) progress.dismiss(); install(); });
            } catch (Exception error) {
                partial.delete();
                android.util.Log.w("InfluenceUpdates", "Update failed", error);
                main.post(() -> {
                    if (!activity.isDestroyed()) progress.dismiss();
                    if (visible(activity)) new AlertDialog.Builder(activity).setTitle("更新失败")
                            .setMessage("下载或校验失败，当前版本与数据保持不变。请稍后重启应用重试。")
                            .setNegativeButton("稍后", null)
                            .setPositiveButton("前往网站", (dialog, which) -> website(activity)).show();
                });
            } finally { busy.set(false); }
        });
    }

    private void install() {
        Activity activity = foreground;
        if (!visible(activity) || ready == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                permissionPending = true;
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            }
            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".influence.updates/update.apk");
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("update", uri));
            activity.startActivity(intent);
            ready = null;
        } catch (Exception error) {
            new AlertDialog.Builder(activity).setMessage("无法打开系统安装器，请稍后重试。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    private File exposed(Uri uri) throws FileNotFoundException {
        if (!uri.getAuthority().equals(getContext().getPackageName() + ".influence.updates")
                || !"/update.apk".equals(uri.getPath())) throw new FileNotFoundException();
        File apk = new File(getContext().getCacheDir(), "influence-updates/update.apk");
        if (!apk.isFile()) throw new FileNotFoundException();
        return apk;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(exposed(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File apk = exposed(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "update.apk";
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = apk.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] args) { throw new UnsupportedOperationException(); }
    @Override public void onActivityResumed(Activity activity) {
        foreground = activity;
        if (permissionPending) {
            permissionPending = false;
            if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) install();
            else ready = null;
        } else if (ready != null) install();
        if (!checked && base != null && automatic) {
            checked = true;
            main.postDelayed(this::check, 12000);
        }
    }
    @Override public void onActivityPaused(Activity activity) { if (foreground == activity) foreground = null; }
    @Override public void onActivityDestroyed(Activity activity) { if (foreground == activity) foreground = null; }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
