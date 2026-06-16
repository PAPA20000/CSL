package net.kdt.pojavlaunch.shortcuts;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.IOException;

/**
 * Fragment for configuring and creating a home screen shortcut for a profile.
 * Allows custom name, icon selection (profile icon / skin head / custom image).
 */
public class ShortcutIconPickerFragment extends Fragment {

    public static final String TAG = "ShortcutIconPickerFragment";
    public static final String ARG_PROFILE_KEY = "profile_key";

    private static final int ICON_SIZE_DP = 72;

    private String mProfileKey;
    private MinecraftProfile mProfile;
    private TextInputEditText mNameInput;
    private ImageView mIconPreview;
    private Button mCreateButton;
    private Button mBtnProfileIcon, mBtnSkinHead, mBtnCustom;

    private Bitmap mSelectedIcon;
    private String mSelectedIconSource = "profile"; // "profile", "skin", "custom"

    private final ActivityResultLauncher<Intent> mImagePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            Uri imageUri = result.getData().getData();
                            if (imageUri != null) {
                                loadCustomImage(imageUri);
                            }
                        }
                    });

    public ShortcutIconPickerFragment() {
        super(R.layout.dialog_add_shortcut);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mProfileKey = getArguments().getString(ARG_PROFILE_KEY);
        }
        if (mProfileKey != null && LauncherProfiles.mainProfileJson != null) {
            mProfile = LauncherProfiles.mainProfileJson.profiles.get(mProfileKey);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);

        if (view == null) return null;

        bindViews(view);
        setupListeners();

        // Pre-fill name with profile name
        if (mProfile != null && mProfile.name != null) {
            mNameInput.setText(mProfile.name);
        }

        // Load default icon (profile icon)
        loadProfileIcon();

        return view;
    }

    private void bindViews(@NonNull View view) {
        mNameInput = view.findViewById(R.id.shortcut_name_input);
        mIconPreview = view.findViewById(R.id.shortcut_icon_preview);
        mCreateButton = view.findViewById(R.id.btn_create_shortcut);
        mBtnProfileIcon = view.findViewById(R.id.btn_icon_profile);
        mBtnSkinHead = view.findViewById(R.id.btn_icon_skin_head);
        mBtnCustom = view.findViewById(R.id.btn_icon_custom);
    }

    private void setupListeners() {
        mBtnProfileIcon.setOnClickListener(v -> loadProfileIcon());
        mBtnSkinHead.setOnClickListener(v -> loadSkinHead());
        mBtnCustom.setOnClickListener(v -> pickCustomImage());

        mCreateButton.setOnClickListener(v -> createShortcut());
    }

    /**
     * Load the profile's existing icon as the shortcut icon.
     */
    private void loadProfileIcon() {
        if (getContext() == null || mProfile == null) return;

        mSelectedIconSource = "profile";
        Drawable drawable = ProfileIconCache.fetchIcon(
                getResources(), mProfileKey, mProfile.icon);

        if (drawable != null) {
            mIconPreview.setImageDrawable(drawable);
            mSelectedIcon = drawableToBitmap(drawable);
        } else {
            mIconPreview.setImageResource(R.drawable.ic_pojav_full);
            mSelectedIcon = null;
        }

        updateButtonStates();
    }

    /**
     * Download and show the player's skin head as shortcut icon.
     */
    private void loadSkinHead() {
        if (getContext() == null) return;

        mSelectedIconSource = "skin";
        mIconPreview.setImageResource(R.drawable.ic_pojav_full);
        mSelectedIcon = null;

        // Try to get username from the current account
        String username = getCurrentUsername();
        if (username == null) {
            Toast.makeText(getContext(), R.string.shortcut_no_account,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getContext(), R.string.shortcut_downloading_skin,
                Toast.LENGTH_SHORT).show();

        PojavApplication.sExecutorService.execute(() -> {
            Bitmap head = ShortcutSkinHeadHelper.getSkinHead(
                    getContext(), username);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (head != null) {
                    mIconPreview.setImageBitmap(head);
                    mSelectedIcon = head;
                } else {
                    Toast.makeText(getContext(),
                            R.string.shortcut_skin_failed,
                            Toast.LENGTH_SHORT).show();
                }
                updateButtonStates();
            });
        });
    }

    /**
     * Open gallery/camera to pick a custom image.
     */
    private void pickCustomImage() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        mImagePickerLauncher.launch(intent);
    }

    private void loadCustomImage(@NonNull Uri imageUri) {
        if (getContext() == null) return;

        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                    getContext().getContentResolver(), imageUri);
            // Scale to reasonable size
            int maxSize = 256;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width > maxSize || height > maxSize) {
                float scale = Math.min(
                        (float) maxSize / width,
                        (float) maxSize / height);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        (int) (width * scale),
                        (int) (height * scale), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }

            mSelectedIconSource = "custom";
            mSelectedIcon = bitmap;
            mIconPreview.setImageBitmap(bitmap);
            updateButtonStates();
        } catch (IOException e) {
            Toast.makeText(getContext(), R.string.shortcut_image_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Create the home screen shortcut.
     */
    private void createShortcut() {
        if (getContext() == null || mProfile == null) {
            Toast.makeText(getContext(), R.string.shortcut_error,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String shortcutName = mNameInput.getText() != null
                ? mNameInput.getText().toString().trim() : "";

        if (shortcutName.isEmpty()) {
            shortcutName = mProfile.name;
        }

        if (shortcutName == null || shortcutName.isEmpty()) {
            Toast.makeText(getContext(), R.string.shortcut_name_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        ProfileShortcutHelper.createShortcut(
                getContext(), mProfileKey, mProfile,
                shortcutName, mSelectedIcon);

        Toast.makeText(getContext(), R.string.shortcut_created,
                Toast.LENGTH_SHORT).show();

        // Go back
        if (getActivity() != null) {
            getActivity().onBackPressed();
        }
    }

    /**
     * Visual feedback for selected icon source.
     */
    private void updateButtonStates() {
        int alphaSelected = 255;
        int alphaDefault = 128;

        mBtnProfileIcon.setAlpha(
                "profile".equals(mSelectedIconSource) ? alphaSelected : alphaDefault);
        mBtnSkinHead.setAlpha(
                "skin".equals(mSelectedIconSource) ? alphaSelected : alphaDefault);
        mBtnCustom.setAlpha(
                "custom".equals(mSelectedIconSource) ? alphaSelected : alphaDefault);
    }

    /**
     * Get the current Minecraft account username from the profile system.
     */
    @Nullable
    private String getCurrentUsername() {
        try {
            net.kdt.pojavlaunch.value.MinecraftAccount account =
                    net.kdt.pojavlaunch.PojavProfile.getCurrentProfileContent(
                            getContext(), null);
            return account != null ? account.username : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Bitmap drawableToBitmap(@Nullable Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        int width = Math.max(drawable.getIntrinsicWidth(), 1);
        int height = Math.max(drawable.getIntrinsicHeight(), 1);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }
}
