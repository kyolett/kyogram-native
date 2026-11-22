package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Kyogram: the Care tab. Care (DroidShop), Trello tasks and Kyogram x OA live in the Kyogram website,
 * so this tab shows that site (signed in once, then kept), under the app's own glass tab bar.
 */
public class KyogramWebActivity extends BaseFragment {

    public static final String SITE = "https://main.kyogram-d13.pages.dev";

    private WebView web;
    private final boolean hasMainTabs;

    public KyogramWebActivity(Bundle args) {
        super(args);
        hasMainTabs = args != null && args.getBoolean("hasMainTabs", false);
    }

    @Override
    public ActionBar createActionBar(Context context) {
        return null; // * the site has its own header
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Color.parseColor("#15121c"));
        web = new WebView(context);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.parseColor("#15121c"));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u != null && !SITE.contains(String.valueOf(u.getHost()))) {
                    try {
                        view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignore) {
                    }
                    return true;
                }
                return false;
            }
        });
        web.loadUrl(SITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.topMargin = AndroidUtilities.statusBarHeight;
        lp.bottomMargin = hasMainTabs ? AndroidUtilities.dp(76) : 0;
        frame.addView(web, lp);
        fragmentView = frame;
        return frame;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (web != null && web.canGoBack()) {
            if (invoked) web.goBack();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (web != null) {
            web.destroy();
            web = null;
        }
    }
}
