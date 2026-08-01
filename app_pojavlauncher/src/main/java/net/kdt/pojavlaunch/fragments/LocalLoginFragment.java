package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.utils.SkinFetchUtils;
import net.kdt.pojavlaunch.PojavApplication;

import java.io.File;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.os.Handler;
import android.os.Looper;

public class LocalLoginFragment extends Fragment {
    public static final String TAG = "LOCAL_LOGIN_FRAGMENT";

    private final Pattern mUsernameValidationPattern;
    private EditText mUsernameEditText;
    private ImageView mHeadPreview;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Runnable mFetchRunnable;

    public LocalLoginFragment(){
        super(R.layout.fragment_local_login);
        mUsernameValidationPattern = Pattern.compile("^[a-zA-Z0-9_]*$");
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // This is overkill but meh
        if (!hasOnlineProfile()){
            Tools.swapFragment(requireActivity(), MainMenuFragment.class, MainMenuFragment.TAG, null);
        }
        mUsernameEditText = view.findViewById(R.id.login_edit_email);
        mHeadPreview = view.findViewById(R.id.live_head_preview);
        
        loadSteveHead();

        mUsernameEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String username = s.toString().trim();
                mMainHandler.removeCallbacks(mFetchRunnable);
                if (username.length() >= 3) {
                    mFetchRunnable = () -> fetchPreviewHead(username);
                    mMainHandler.postDelayed(mFetchRunnable, 800);
                } else {
                    loadSteveHead();
                }
            }
        });

        view.findViewById(R.id.login_button).setOnClickListener(v -> {
            if(!checkEditText()) {
                Context context = v.getContext();
                Tools.dialog(context, context.getString(R.string.local_login_bad_username_title), context.getString(R.string.local_login_bad_username_text));
                return;
            }

            String username = mUsernameEditText.getText().toString();

            // Auto-fetch skin for local account
            PojavApplication.sExecutorService.execute(() -> {
                File skinsDir = new File(Tools.DIR_DATA + "/skins");
                if (!skinsDir.exists()) skinsDir.mkdirs();
                File destSkinFile = new File(skinsDir, username + "_skin.png");
                SkinFetchUtils.fetchAndSaveSkin(username, destSkinFile);
                
                File destHeadFile = new File(Tools.DIR_CACHE, username + ".png");
                SkinFetchUtils.fetchAndSaveHead(username, destHeadFile);
            });

            ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{
                    username, "" });

            Tools.swapFragment(requireActivity(), MainMenuFragment.class, MainMenuFragment.TAG, null);
        });

        view.setAlpha(0f);
        view.setScaleX(0.985f);
        view.setScaleY(0.985f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).start();
    }

    private void loadSteveHead() {
        if (mHeadPreview == null) return;
        Bitmap steveSkin = BitmapFactory.decodeResource(getResources(), R.drawable.ic_steve);
        if (steveSkin != null) {
            Bitmap head = net.kdt.pojavlaunch.value.MinecraftAccount.extractSkinHead(steveSkin);
            steveSkin.recycle();
            if (head != null) {
                mHeadPreview.setImageBitmap(head);
            }
        }
    }

    private void fetchPreviewHead(String username) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                URL url = new URL("https://mc-heads.net/head/" + username + "/100");
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.connect();
                java.io.InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap != null) {
                    Bitmap rounded = net.kdt.pojavlaunch.value.MinecraftAccount.roundBitmap(bitmap, 128, 16f);
                    mMainHandler.post(() -> {
                        if (mHeadPreview != null) mHeadPreview.setImageBitmap(rounded);
                    });
                }
            } catch (Exception e) {
                Log.w("SkinPreview", "Failed to fetch preview head", e);
            }
        });
    }


    /** @return Whether the mail (and password) text are eligible to make an auth request  */
    private boolean checkEditText(){

        String text = mUsernameEditText.getText().toString();

        Matcher matcher = mUsernameValidationPattern.matcher(text);
        return !(text.isEmpty()
                || text.length() < 3
                || text.length() > 16
                || !matcher.find()
                || new File(Tools.DIR_ACCOUNT_NEW + "/" + text + ".json").exists()
        );
    }
}
