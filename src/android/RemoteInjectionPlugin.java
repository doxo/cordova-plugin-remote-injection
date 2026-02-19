package com.truckmovers.cordova;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.os.Build;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebViewEngine;
import org.apache.cordova.LOG;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

public class RemoteInjectionPlugin extends CordovaPlugin {
    private static String TAG = "RemoteInjectionPlugin";
    private static Pattern REMOTE_URL_REGEX = Pattern.compile("^http(s)?://.*");


    // List of files to inject before injecting Cordova.
    private final ArrayList<String> preInjectionFileNames = new ArrayList<String>();
    private int promptInterval;  // Delay before prompting user to retry in seconds
    private boolean allowFetchAndInject;

    private RequestLifecycle lifecycle;

    protected void pluginInitialize() {
        String pref = webView.getPreferences().getString("CRIInjectFirstFiles", "");
        for (String path: pref.split(",")) {
            preInjectionFileNames.add(path.trim());
        }
        promptInterval = webView.getPreferences().getInteger("CRIPageLoadPromptInterval", 10);
        allowFetchAndInject = webView.getPreferences().getBoolean("CRIAllowFetchAndInject", false);

        final Activity activity = super.cordova.getActivity();
        final CordovaWebViewEngine engine = super.webView.getEngine();
        lifecycle = new RequestLifecycle(activity, engine, promptInterval);
    }

    /**
     * Handle exec() calls from JavaScript.
     * Supported actions:
     *   - fetchAndInject: fetches JS from URLs via native HTTP and injects via evaluateJavascript().
     *     Bypasses CSP restrictions that block dynamically created script tags.
     *     Gated behind the CRIAllowFetchAndInject preference (default: false).
     *     Args: array of URL strings to fetch and inject sequentially.
     */
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("fetchAndInject".equals(action)) {
            if (!allowFetchAndInject) {
                callbackContext.error("fetchAndInject is disabled. Set CRIAllowFetchAndInject=true in config.xml to enable.");
                return true;
            }
            final List<String> urls = new ArrayList<String>();
            for (int i = 0; i < args.length(); i++) {
                urls.add(args.getString(i));
            }
            fetchAndInject(urls, callbackContext);
            return true;
        }
        return false;
    }

    /**
     * Fetches JavaScript from the given URLs via native HTTP and injects the combined
     * result into the WebView via evaluateJavascript().
     *
     * Both the network fetch (Java HttpURLConnection) and the script execution
     * (WebView.evaluateJavascript) happen outside the WebView's CSP context, so this
     * method bypasses Content Security Policy restrictions entirely.
     *
     * URLs are fetched sequentially on a background thread. If any fetch fails, the
     * callback receives an error and no scripts are injected. On success, all fetched
     * JS is concatenated and injected in a single evaluateJavascript() call on the
     * UI thread.
     *
     * Security: this action is gated behind the CRIAllowFetchAndInject preference
     * (default: false). Only enable it in debug/dev builds.
     *
     * @param urls            List of HTTP/HTTPS URLs pointing to JavaScript sources.
     * @param callbackContext Cordova callback — success() on injection, error(msg) on failure.
     */
    private void fetchAndInject(final List<String> urls, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                final StringBuilder allJs = new StringBuilder();
                for (String urlStr : urls) {
                    URL url;
                    try {
                        url = new URL(urlStr);
                    } catch (MalformedURLException e) {
                        callbackContext.error("Invalid URL: " + urlStr);
                        return;
                    }

                    String scheme = url.getProtocol();
                    if (!"http".equals(scheme) && !"https".equals(scheme)) {
                        callbackContext.error("Only http and https URLs are allowed, got: " + scheme + " for " + urlStr);
                        return;
                    }

                    try {
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(10000);
                        try {
                            int status = conn.getResponseCode();
                            if (status != 200) {
                                callbackContext.error("HTTP " + status + " for " + urlStr);
                                return;
                            }
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    allJs.append(line);
                                    allJs.append("\n");
                                }
                            }
                            allJs.append(";\n"); // ensure statement boundary between files
                        } finally {
                            conn.disconnect();
                        }
                    } catch (IOException e) {
                        callbackContext.error("Failed to fetch " + urlStr + ": " + e.getMessage());
                        return;
                    }
                }

                final String js = allJs.toString();
                injectJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String result) {
                        callbackContext.success();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        callbackContext.error("Current WebView engine does not expose an android.webkit.WebView; cannot inject JavaScript.");
                    }
                });
            }
        });
    }

    /**
     * Injects JavaScript into the WebView via evaluateJavascript() on the UI thread.
     * Falls back to the onUnsupported callback if the engine view is not a WebView.
     *
     * Requires Android API 19+ (KitKat). On older devices, logs an error and runs onUnsupported.
     *
     * @param js            The JavaScript code to inject.
     * @param onComplete    Called on the UI thread after evaluateJavascript() completes.
     * @param onUnsupported Called on the UI thread if the engine is not a WebView or API < 19.
     */
    private void injectJavascript(final String js, final ValueCallback<String> onComplete, final Runnable onUnsupported) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                    LOG.e(TAG, "evaluateJavascript() requires API 19+. Current: " + Build.VERSION.SDK_INT);
                    if (onUnsupported != null) onUnsupported.run();
                    return;
                }

                Object engineView = webView.getEngine().getView();
                if (engineView instanceof WebView) {
                    WebView androidWebView = (WebView) engineView;
                    androidWebView.evaluateJavascript(js, onComplete);
                } else {
                    LOG.e(TAG, "Engine view is not a WebView: " + engineView.getClass().getName());
                    if (onUnsupported != null) onUnsupported.run();
                }
            }
        });
    }

    private void onMessageTypeFailure(String messageId, Object data) {
        LOG.e(TAG, messageId + " received a data instance that is not an expected type:" + data.getClass().getName());
    }

    @Override
    public void onReset() {
        super.onReset();

        lifecycle.requestStopped();
    }

    @Override
    public Object onMessage(String id, Object data) {
        if (id.equals("onReceivedError")) {
            // Data is a JSONObject instance with the following keys:
            // * errorCode
            // * description
            // * url

            if (data instanceof JSONObject) {
                JSONObject json = (JSONObject) data;

                try {
                    if (isRemote(json.getString("url"))) {
                        lifecycle.requestStopped();
                    }
                } catch (JSONException e) {
                    LOG.e(TAG, "Unexpected JSON in onReceiveError", e);
                }
            } else {
                onMessageTypeFailure(id, data);
            }
        } else if (id.equals("onPageFinished")) {
            if (data instanceof String) {
                String url = (String) data;
                if (isRemote(url)) {
                    injectCordova();
                    lifecycle.requestStopped();
                }
            } else {
                onMessageTypeFailure(id, data);
            }
        } else if (id.equals("onPageStarted")) {
            if (data instanceof String) {
                String url = (String) data;

                if (isRemote(url)) {
                    lifecycle.requestStarted(url);
                }
            } else {
                onMessageTypeFailure(id, data);
            }
        }

        return null;
    }

    /**
     * @param url
     * @return true if the URL over HTTP or HTTPS
     */
    private boolean isRemote(String url) {
        return REMOTE_URL_REGEX.matcher((String) url).matches();
    }

    private void injectCordova() {
        List<String> jsPaths = new ArrayList<String>();
        for (String path: preInjectionFileNames) {
            jsPaths.add(path);
        }

        jsPaths.add("www/cordova.js");

        // We load the plugin code manually rather than allow cordova to load them (via
        // cordova_plugins.js).  The reason for this is the WebView will attempt to load the
        // file in the origin of the page (e.g. https://truckmover.com/plugins/plugin/plugin.js).
        // By loading them first cordova will skip its loading process altogether.
        jsPaths.addAll(jsPathsToInject(cordova.getActivity().getResources().getAssets(), "www/plugins"));

        // Initialize the cordova plugin registry.
        jsPaths.add("www/cordova_plugins.js");

        // Use evaluateJavascript() to inject directly into the page context.
        // This bypasses Content Security Policy restrictions that block data: URIs
        // in script-src directives (e.g. nonce-based CSP used by modern SSR apps).
        StringBuilder jsToInject = new StringBuilder();
        for (String path: jsPaths) {
            jsToInject.append(readFile(cordova.getActivity().getResources().getAssets(), path));
        }

        final String js = jsToInject.toString();
        injectJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String result) {
                // Injection complete
            }
        }, new Runnable() {
            @Override
            public void run() {
                LOG.e(TAG, "injectCordova failed: engine view is not a WebView.");
            }
        });
    }

    private String readFile(AssetManager assets, String filePath) {
        StringBuilder out = new StringBuilder();
        BufferedReader in = null;
        try {
            InputStream stream = assets.open(filePath);
            in = new BufferedReader(new InputStreamReader(stream));
            String str = "";

            while ((str = in.readLine()) != null) {
                out.append(str);
                out.append("\n");
            }
        } catch (MalformedURLException e) {
        } catch (IOException e) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return out.toString();
    }

    /**
     * Searches the provided path for javascript files recursively.
     *
     * @param assets
     * @param path start path
     * @return found JS files
     */
    private List<String> jsPathsToInject(AssetManager assets, String path){
        List jsPaths = new ArrayList<String>();

        try {
            for (String filePath: assets.list(path)) {
                String fullPath = path + File.separator + filePath;

                if (fullPath.endsWith(".js")) {
                    jsPaths.add(fullPath);
                } else {
                    List<String> childPaths = jsPathsToInject(assets, fullPath);
                    if (!childPaths.isEmpty()) {
                        jsPaths.addAll(childPaths);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return jsPaths;
    }

    private static class RequestLifecycle {
        private final Activity activity;
        private final CordovaWebViewEngine engine;
        private UserPromptTask task;
        private final int promptInterval;

        RequestLifecycle(Activity activity, CordovaWebViewEngine engine, int promptInterval) {
            this.activity = activity;
            this.engine = engine;
            this.promptInterval = promptInterval;
        }

        boolean isLoading() {
            return task != null;
        }

        void requestStopped() {
            stopTask();
        }

        void requestStarted(final String url) {
            startTask(url);
        }

        private synchronized void stopTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }

        private synchronized void startTask(final String url) {
            if (task != null) {
                task.cancel();
            }

            if (promptInterval > 0 ) {
                task = new UserPromptTask(this, activity, engine, url);
                new Timer().schedule(task, promptInterval * 1000);
            }
        }
    }

    /**
     * Prompt the user asking if they want to wait on the current request or retry.
     */
    static class UserPromptTask extends TimerTask {
        private final RequestLifecycle lifecycle;
        private final Activity activity;
        private final CordovaWebViewEngine engine;
        final String url;

        AlertDialog alertDialog;

        UserPromptTask(RequestLifecycle lifecycle, Activity activity, CordovaWebViewEngine engine, String url) {
            this.lifecycle = lifecycle;
            this.activity = activity;
            this.engine = engine;
            this.url = url;
        }

        @Override
        public boolean cancel() {
            boolean result = super.cancel();
            cleanup();

            return result;
        }

        private void cleanup() {
            if (alertDialog != null) {
                alertDialog.dismiss();
                alertDialog = null;
            }
        }

        @Override
        public void run() {
            if (lifecycle.isLoading()) {
                // Prompts the user giving them the choice to wait on the current request or retry.
                lifecycle.activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                        builder.setMessage("The server is taking longer than expected to respond.")
                                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                    @Override
                                    public void onDismiss(DialogInterface dialog) {
                                        UserPromptTask.this.cleanup();
                                    }
                                })
                                .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int id) {
                                        // Obviously only works for GETs but good enough.
                                        engine.loadUrl(engine.getUrl(), false);
                                    }
                                })
                                .setNegativeButton("Wait", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int id) {
                                        lifecycle.startTask(url);
                                    }
                                });
                        AlertDialog dialog = UserPromptTask.this.alertDialog = builder.create();
                        dialog.show();
                    }
                });
            } else {
                lifecycle.stopTask();
            }
        }
    }
}
