package net.kdt.pojavlaunch.ads;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  TEMPORARY TEST HARNESS — AdMob Rewarded Ads (remove before release)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Loads and shows a single AdMob Rewarded Ad on demand from the
 * temporary "Show Rewarded Ad (Test)" home-screen button.
 *
 * Design notes:
 *  - One-shot flow per tap: load -> show immediately -> reward toast.
 *  - The Mobile Ads SDK is initialized lazily on first use (the current
 *    recommended pattern) instead of at Application start, so the rest
 *    of the launcher pays zero startup cost for this test feature.
 *  - All Google callbacks are implemented and logged, as required:
 *    onAdLoaded, onAdFailedToLoad, onAdShowedFullScreenContent,
 *    onAdDismissedFullScreenContent, onAdFailedToShowFullScreenContent,
 *    onUserEarnedReward.
 */
public final class RewardedAdManager {

    private static final String TAG = "RewardedAdManager";

    /** Real AdMob rewarded unit used for this temporary test. */
    private static final String REWARDED_AD_UNIT_ID = "ca-app-pub-1728858169440666/9907405442";

    /** Guard against double-taps while a load is already in flight. */
    private boolean mIsLoading = false;

    /** The most recently loaded ad; cleared after show/dismiss. */
    @Nullable
    private RewardedAd mRewardedAd;

    private final Context mAppContext;
    private final AtomicBoolean mSdkInitRequested = new AtomicBoolean(false);

    private static volatile RewardedAdManager sInstance;

    private RewardedAdManager(@NonNull Context appContext) {
        mAppContext = appContext;
    }

    /** Thread-safe lazy singleton; always backed by the application context. */
    @NonNull
    public static RewardedAdManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (RewardedAdManager.class) {
                if (sInstance == null) {
                    sInstance = new RewardedAdManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /**
     * Entry point for the test button: initializes the SDK if needed,
     * loads a rewarded ad, and shows it immediately when the load succeeds.
     */
    public void loadAndShow(@NonNull Activity activity) {
        ensureSdkInitialized();

        if (mIsLoading) {
            toast("Ad is already loading, please wait…");
            return;
        }

        mIsLoading = true;
        AdRequest adRequest = new AdRequest.Builder().build();

        RewardedAd.load(mAppContext, REWARDED_AD_UNIT_ID, adRequest, new RewardedAdLoadCallback() {

            @Override
            public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                Log.i(TAG, "onAdLoaded: rewarded ad ready, showing now");
                mIsLoading = false;
                mRewardedAd = rewardedAd;
                showLoadedAd(activity, rewardedAd);
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                Log.e(TAG, "onAdFailedToLoad: code=" + loadAdError.getCode()
                        + " message=" + loadAdError.getMessage());
                mIsLoading = false;
                mRewardedAd = null;
                // Required UX: surface the failure to the tester
                toast("Ad failed to load: " + loadAdError.getMessage());
            }
        });
    }

    /** Attach the full-screen callbacks and present the loaded ad. */
    private void showLoadedAd(@NonNull Activity activity, @NonNull RewardedAd rewardedAd) {
        // Never try to show against a dead activity context.
        if (activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "showLoadedAd: activity not in a showable state, dropping ad");
            mRewardedAd = null;
            return;
        }

        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {

            @Override
            public void onAdShowedFullScreenContent() {
                Log.i(TAG, "onAdShowedFullScreenContent: impression started");
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                Log.i(TAG, "onAdDismissedFullScreenContent: user closed the ad");
                mRewardedAd = null; // reference released for GC
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                Log.e(TAG, "onAdFailedToShowFullScreenContent: code=" + adError.getCode()
                        + " message=" + adError.getMessage());
                mRewardedAd = null;
                toast("Ad failed to show: " + adError.getMessage());
            }
        });

        // Latest rewarded show API — the lambda is the OnUserEarnedRewardListener.
        rewardedAd.show(activity, (RewardItem rewardItem) -> {
            int amount = rewardItem.getAmount();
            String type = rewardItem.getType();
            Log.i(TAG, "onUserEarnedReward: " + amount + " x " + type);
            // Required UX: confirm the reward to the tester
            toast("Reward Earned!");
        });
    }

    /** Idempotent Mobile Ads SDK bootstrap (latest recommended pattern). */
    private void ensureSdkInitialized() {
        if (mSdkInitRequested.compareAndSet(false, true)) {
            MobileAds.initialize(mAppContext, initializationStatus ->
                    Log.i(TAG, "Mobile Ads SDK initialized: "
                            + initializationStatus.getAdapterStatusMap()));
        }
    }

    /** Toast helper routed through the application context. */
    private void toast(@NonNull String message) {
        Toast.makeText(mAppContext, message, Toast.LENGTH_LONG).show();
    }
}
