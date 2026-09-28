package __APP_ID__;

// The line above gets rewritten automatically at build time (see the
// "Add VaultMedia native plugin" step in build-android.yml) to match your
// real Capacitor appId from capacitor.config.json — you don't need to
// edit it by hand.

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VaultMedia — lets the web app (1) pick photos/videos while keeping their
 * original content:// URI, and (2) ask Android to delete those originals
 * from the gallery afterwards.
 *
 * A plain <input type="file"> can do (1) but never gives JS the URI back,
 * and no web API can do (2) at all — Android requires a native, user-
 * confirmed request (MediaStore.createDeleteRequest) before any app can
 * delete another app's media. That's why this needs to be native code
 * instead of something addable to the HTML file directly.
 *
 * It also provides getAccessToken() for Google Drive backup (see below).
 */
// requestCodes MUST list every startIntentSenderForResult code used below
// (9821 = DELETE_REQUEST_CODE, 9932 = AUTH_REQUEST_CODE), otherwise Capacitor
// never calls handleOnActivityResult and the JS promise hangs.
@CapacitorPlugin(
    name = "VaultMedia",
    requestCodes = {9821, 9932},
    permissions = {
        @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }),
        // Android 13+ (API 33+): photos and videos
        @Permission(alias = "media", strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO }),
        // Android 12 and below: classic storage permission (write is only
        // actually needed pre-Android 10, and the manifest caps it there too)
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE })
    }
)
public class VaultMediaPlugin extends Plugin {

    /* ---------------- Google Drive: silent access token ----------------
       Uses Google's Authorization API (what native apps like WhatsApp use).
       The first call shows Google's consent screen once. After that every
       call returns a fresh access token with NO screen, so the Drive login
       never expires from the user's point of view. The app is identified to
       Google by its package name + signing SHA-1 (the "Android" OAuth client).

       JS: VaultMedia.getAccessToken({ interactive: true | false })
       interactive=false rejects with "needs_consent" instead of showing UI. */
    private static final int AUTH_REQUEST_CODE = 9932;
    private String pendingAuthCallbackId;

    @PluginMethod
    public void getAccessToken(final PluginCall call) {
        final boolean interactive = Boolean.TRUE.equals(call.getBoolean("interactive", false));

        List<Scope> scopes = new ArrayList<>();
        scopes.add(new Scope("https://www.googleapis.com/auth/drive.file"));
        scopes.add(new Scope("email"));
        scopes.add(new Scope("profile"));

        AuthorizationRequest request = AuthorizationRequest.builder()
                .setRequestedScopes(scopes)
                .build();

        Identity.getAuthorizationClient(getActivity())
                .authorize(request)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        // User hasn't approved yet (or approval was revoked).
                        if (!interactive) {
                            call.reject("needs_consent");
                            return;
                        }
                        try {
                            call.setKeepAlive(true);
                            bridge.saveCall(call);
                            pendingAuthCallbackId = call.getCallbackId();
                            getActivity().startIntentSenderForResult(
                                    result.getPendingIntent().getIntentSender(),
                                    AUTH_REQUEST_CODE, null, 0, 0, 0
                            );
                        } catch (IntentSender.SendIntentException e) {
                            pendingAuthCallbackId = null;
                            call.reject("Could not open Google consent screen", e);
                        }
                    } else {
                        // Already approved earlier: token comes back silently.
                        resolveAuthToken(call, result);
                    }
                })
                .addOnFailureListener(e -> call.reject("authorize_failed: " + e.getMessage(), e));
    }

    private void resolveAuthToken(PluginCall call, AuthorizationResult result) {
        String token = result.getAccessToken();
        if (token == null) {
            call.reject("no_token");
            return;
        }
        JSObject ret = new JSObject();
        ret.put("accessToken", token);
        call.resolve(ret);
    }

    private void handleAuthResult(int resultCode, Intent data) {
        if (pendingAuthCallbackId == null) return;
        PluginCall savedCall = bridge.getSavedCall(pendingAuthCallbackId);
        pendingAuthCallbackId = null;
        if (savedCall == null) return;
        try {
            if (resultCode != Activity.RESULT_OK) {
                savedCall.reject("cancelled");
            } else {
                AuthorizationResult result =
                        Identity.getAuthorizationClient(getActivity()).getAuthorizationResultFromIntent(data);
                resolveAuthToken(savedCall, result);
            }
        } catch (Exception e) {
            savedCall.reject("authorize_failed: " + e.getMessage(), e);
        } finally {
            bridge.releaseCall(savedCall);
        }
    }

    /* ---------------- "Share to V Vault" (Android Share sheet) ----------------
       MainActivity hands photos/videos shared from Gallery (or any app) to this
       static queue — as full data:...;base64,... strings — the moment they
       arrive, regardless of whether the WebView has finished loading yet. The
       web app then pulls them whenever it's actually ready via getSharedMedia().
       This "push into a static queue, pull when ready" shape is what avoids the
       old race condition where a JS call fired before index.html had loaded. */
    private static final List<String> pendingSharedMedia = Collections.synchronizedList(new ArrayList<>());

    public static void addSharedMedia(List<String> dataUris) {
        if (dataUris == null || dataUris.isEmpty()) return;
        pendingSharedMedia.addAll(dataUris);
    }

    @PluginMethod
    public void getSharedMedia(PluginCall call) {
        JSArray items = new JSArray();
        synchronized (pendingSharedMedia) {
            for (String s : pendingSharedMedia) items.put(s);
            pendingSharedMedia.clear();
        }
        JSObject ret = new JSObject();
        ret.put("items", items);
        call.resolve(ret);
    }

    /* ---------------- "Save to device" / "Move to Gallery" ----------------
       A plain <a download> on a data: URI is unreliable inside an embedded
       Android WebView (it silently does nothing on a lot of devices/OS
       versions) — it only really works in a full browser tab. This uses
       MediaStore directly instead, which is the same mechanism the real
       Gallery/Camera app uses to save photos, so the file reliably shows up
       there. Saves into Pictures/V Vault or Movies/V Vault. */
    @PluginMethod
    public void saveMedia(PluginCall call) {
        String base64 = call.getString("base64");
        String mimeType = call.getString("mimeType");
        String fileName = call.getString("fileName");
        if (base64 == null || mimeType == null) {
            call.reject("base64 and mimeType are required");
            return;
        }
        boolean isVideo = mimeType.startsWith("video/");
        if (fileName == null || fileName.isEmpty()) {
            fileName = "vvault_" + System.currentTimeMillis() + (isVideo ? ".mp4" : ".jpg");
        }

        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/V Vault");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }

            Uri collection = isVideo
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri itemUri = getContext().getContentResolver().insert(collection, values);
            if (itemUri == null) {
                call.reject("Could not create file in gallery");
                return;
            }

            try (OutputStream out = getContext().getContentResolver().openOutputStream(itemUri)) {
                if (out == null) {
                    call.reject("Could not open output stream for saved file");
                    return;
                }
                out.write(bytes);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContext().getContentResolver().update(itemUri, done, null, null);
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("uri", itemUri.toString());
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject("Storage permission needed to save to gallery", e);
        } catch (Exception e) {
            call.reject("Failed to save media: " + e.getMessage(), e);
        }
    }

    /* ---------------- permissions: camera + gallery + file access ---------------- */

    private String mediaAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "media" : "storage";
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Environment.isExternalStorageManager()
                : true; // Android 10 and below use the classic storage permission
    }

    private JSObject permissionStates() {
        JSObject ret = new JSObject();
        ret.put("camera", getPermissionState("camera") == PermissionState.GRANTED);
        ret.put("media", getPermissionState(mediaAlias()) == PermissionState.GRANTED);
        ret.put("allFiles", hasAllFilesAccess());
        return ret;
    }

    /** Asks for Camera + Photos/Videos (gallery) permissions if they aren't granted yet. */
    @PluginMethod
    public void requestAppPermissions(PluginCall call) {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (getPermissionState("camera") != PermissionState.GRANTED) needed.add("camera");
        if (getPermissionState(mediaAlias()) != PermissionState.GRANTED) needed.add(mediaAlias());
        if (needed.isEmpty()) {
            call.resolve(permissionStates());
            return;
        }
        requestPermissionForAliases(needed.toArray(new String[0]), call, "permissionsCallback");
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        call.resolve(permissionStates());
    }

    /** Returns the current state of all permissions without asking. */
    @PluginMethod
    public void checkAppPermissions(PluginCall call) {
        call.resolve(permissionStates());
    }

    /**
     * "All files access" (file-manager level access) can't be granted with a normal
     * pop-up on Android 11+; the user has to switch it on in a system settings screen.
     * This opens that screen for this app.
     */
    @PluginMethod
    public void openAllFilesAccess(PluginCall call) {
        if (hasAllFilesAccess()) {
            call.resolve(permissionStates());
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(fallback);
            } catch (Exception e2) {
                call.reject("Could not open the settings screen");
                return;
            }
        }
        call.resolve(permissionStates());
    }

    @PluginMethod
    public void pickMedia(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(call, intent, "pickMediaResult");
    }

    @ActivityCallback
    private void pickMediaResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK) {
            call.reject("cancelled");
            return;
        }

        Intent data = result.getData();
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data != null && data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data != null && data.getData() != null) {
            uris.add(data.getData());
        }

        JSArray items = new JSArray();
        for (Uri uri : uris) {
            // Keep read access beyond this call, so a later deleteMedia()
            // request can still reference the same URI.
            try {
                getContext().getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception e) {
                // Some providers don't support persistable permissions — fine.
            }

            String mimeType = getContext().getContentResolver().getType(uri);
            if (mimeType == null) mimeType = "application/octet-stream";

            try (java.io.InputStream stream = getContext().getContentResolver().openInputStream(uri)) {
                if (stream != null) {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = stream.read(chunk)) != -1) buffer.write(chunk, 0, n);
                    String base64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);

                    JSObject obj = new JSObject();
                    obj.put("uri", uri.toString());
                    obj.put("mimeType", mimeType);
                    obj.put("base64", base64);
                    items.put(obj);
                }
            } catch (Exception e) {
                // Skip a file we couldn't read rather than failing the whole batch.
            }
        }

        JSObject ret = new JSObject();
        ret.put("items", items);
        call.resolve(ret);
    }

    private String pendingDeleteCallbackId;
    private int pendingDeleteCount = 0;
    private static final int DELETE_REQUEST_CODE = 9821;


    /**
     * ACTION_OPEN_DOCUMENT gives "document" URIs such as
     *   content://com.android.providers.media.documents/document/image%3A1234
     * but MediaStore.createDeleteRequest only accepts real MediaStore URIs such as
     *   content://media/external/images/media/1234
     * Passing a document URI throws, so the delete never happened. This converts.
     * Returns null when the file isn't in MediaStore (e.g. Google Drive, Downloads).
     */
    private Uri toMediaStoreUri(Uri uri) {
        if (uri == null) return null;
        try {
            // Android 12+: the system converts the picked URI for us and keeps
            // the access the user granted when picking the file.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Uri viaSystem = MediaStore.getMediaUri(getContext(), uri);
                if (viaSystem != null) return viaSystem;
            }
            String authority = uri.getAuthority();
            if ("media".equals(authority)) {
                return uri; // already a MediaStore / picker URI
            }
            if ("com.android.providers.media.documents".equals(authority)) {
                String docId = DocumentsContract.getDocumentId(uri); // e.g. "image:1234"
                String[] parts = docId.split(":");
                if (parts.length == 2) {
                    long id = Long.parseLong(parts[1]);
                    if ("image".equals(parts[0])) {
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    } else if ("video".equals(parts[0])) {
                        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    @PluginMethod
    public void deleteMedia(PluginCall call) {
        JSArray uriArray = call.getArray("uris");
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        try {
            if (uriArray != null) {
                for (Object o : uriArray.toList()) {
                    Uri converted = toMediaStoreUri(Uri.parse(String.valueOf(o)));
                    if (converted != null) uris.add(converted);
                }
            }
        } catch (Exception e) {
            // ignore malformed input, treated as empty below
        }

        if (uris.isEmpty()) {
            JSObject ret = new JSObject();
            ret.put("deleted", 0);
            call.resolve(ret);
            return;
        }

        int deletedDirectly = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            // "All files access" is on: no confirmation dialog needed, delete directly.
            java.util.List<Uri> remaining = new java.util.ArrayList<>();
            for (Uri u : uris) {
                try {
                    if (getContext().getContentResolver().delete(u, null, null) > 0) {
                        deletedDirectly++;
                        continue;
                    }
                } catch (Exception e) {
                    // fall back to the system dialog for this one
                }
                remaining.add(u);
            }
            uris = remaining;
            if (uris.isEmpty()) {
                JSObject done = new JSObject();
                done.put("deleted", deletedDirectly);
                call.resolve(done);
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: one system confirmation dialog covers every file
            // in the batch. The user taps "Allow" once; there is no way to
            // skip this dialog — it's an OS privacy requirement, not
            // something this plugin can bypass.
            //
            // We launch this the plain Android way (Activity.startIntentSenderForResult)
            // and catch the result in handleOnActivityResult below, rather than
            // relying on a newer Capacitor Plugin helper method that may not
            // exist in every Capacitor version.
            try {
                PendingIntent pendingIntent =
                        MediaStore.createDeleteRequest(getContext().getContentResolver(), uris);
                call.setKeepAlive(true);
                bridge.saveCall(call);
                pendingDeleteCallbackId = call.getCallbackId();
                pendingDeleteCount = uris.size() + deletedDirectly;
                getActivity().startIntentSenderForResult(
                        pendingIntent.getIntentSender(), DELETE_REQUEST_CODE,
                        null, 0, 0, 0
                );
            } catch (Exception e) {
                call.reject("Could not request delete", e);
            }
        } else {
            // Android 10 and below: try a direct delete per file. This only
            // succeeds for files the app itself owns, or where the user has
            // already granted broad storage access — otherwise it silently
            // fails, which is fine since the vault copy already exists.
            int deleted = 0;
            for (Uri uri : uris) {
                try {
                    if (getContext().getContentResolver().delete(uri, null, null) > 0) deleted++;
                } catch (Exception e) {
                    // e.g. RecoverableSecurityException — skip this file.
                }
            }
            JSObject ret = new JSObject();
            ret.put("deleted", deleted);
            call.resolve(ret);
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode == AUTH_REQUEST_CODE) {
            handleAuthResult(resultCode, data);
            return;
        }
        if (requestCode != DELETE_REQUEST_CODE || pendingDeleteCallbackId == null) return;
        PluginCall savedCall = bridge.getSavedCall(pendingDeleteCallbackId);
        pendingDeleteCallbackId = null;
        if (savedCall == null) return;
        JSObject ret = new JSObject();
        // RESULT_OK means the user approved the whole batch.
        ret.put("deleted", resultCode == Activity.RESULT_OK ? pendingDeleteCount : 0);
        savedCall.resolve(ret);
        bridge.releaseCall(savedCall);
    }
}
