package net.kdt.pojavlaunch.modpack;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

/**
 * Displays the generated share code after a modpack is built.
 * Provides options: copy code, share via Android share sheet, export as ZIP.
 */
public class ShareCodeExportFragment extends Fragment {

    public static final String TAG = "ShareCodeExportFragment";

    private static final String ARG_MANIFEST_JSON = "manifest_json";
    private static final String ARG_MODPACK_NAME = "modpack_name";

    private TextView mCodeText;
    private ProgressBar mProgressBar;
    private MaterialCardView mCodeCard;
    private MaterialButton mBtnCopy, mBtnShare, mBtnGist, mBtnClose;

    private String mManifestJson;
    private String mModpackName;
    private String mOfflineCode;

    public ShareCodeExportFragment() { super(R.layout.fragment_share_code_export); }

    public static ShareCodeExportFragment newInstance(String manifestJson, String modpackName) {
        Bundle args = new Bundle();
        args.putString(ARG_MANIFEST_JSON, manifestJson);
        args.putString(ARG_MODPACK_NAME, modpackName);
        ShareCodeExportFragment f = new ShareCodeExportFragment();
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mManifestJson = getArguments().getString(ARG_MANIFEST_JSON);
            mModpackName = getArguments().getString(ARG_MODPACK_NAME);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mCodeText = view.findViewById(R.id.share_code_text);
        mProgressBar = view.findViewById(R.id.share_progress);
        mCodeCard = view.findViewById(R.id.code_card);
        mBtnCopy = view.findViewById(R.id.btn_copy_code);
        mBtnShare = view.findViewById(R.id.btn_share);
        mBtnGist = view.findViewById(R.id.btn_upload_gist);
        mBtnClose = view.findViewById(R.id.btn_close);

        mBtnClose.setOnClickListener(v -> {
            if (getParentFragment() != null) {
                getParentFragmentManager().popBackStack();
            } else {
                requireActivity().onBackPressed();
            }
        });

        // Generate offline code in background
        mProgressBar.setVisibility(View.VISIBLE);
        mCodeCard.setVisibility(View.GONE);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                String code = ShareCodeEncoder.encodeOffline(mManifestJson);
                Tools.runOnUiThread(() -> showOfflineCode(code));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    mProgressBar.setVisibility(View.GONE);
                    mCodeText.setText("Error: " + e.getMessage());
                });
            }
        });
    }

    private void showOfflineCode(String code) {
        mOfflineCode = code;
        mProgressBar.setVisibility(View.GONE);
        mCodeCard.setVisibility(View.VISIBLE);
        mBtnCopy.setVisibility(View.VISIBLE);
        mBtnShare.setVisibility(View.VISIBLE);

        if (ShareCodeEncoder.isOfflineCodeTooLong(code)) {
            mCodeText.setText(R.string.mp_code_too_long_hint);
            mBtnGist.setVisibility(View.VISIBLE);
        } else {
            mCodeText.setText(code);
            mCodeText.setTextIsSelectable(true);
        }

        mBtnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CS Modpack Code", code));
            Tools.runOnUiThread(() -> {
                android.widget.Toast.makeText(getContext(), R.string.mp_code_copied, android.widget.Toast.LENGTH_SHORT).show();
            });
        });

        mBtnShare.setOnClickListener(v -> {
            android.content.Intent share = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(android.content.Intent.EXTRA_TEXT, code);
            startActivity(android.content.Intent.createChooser(share,
                    getString(R.string.mp_share_title)));
        });

        mBtnGist.setOnClickListener(v -> uploadToGist());
    }

    private void uploadToGist() {
        mProgressBar.setVisibility(View.VISIBLE);
        mBtnGist.setEnabled(false);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                String gistId = ShareCodeHelper.uploadGist(mManifestJson, mModpackName);
                String gistCode = ShareCodeEncoder.encodeGist(gistId);
                Tools.runOnUiThread(() -> showGistCode(gistCode));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    mProgressBar.setVisibility(View.GONE);
                    mBtnGist.setEnabled(true);
                    android.widget.Toast.makeText(getContext(),
                            "Upload failed: " + e.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showGistCode(String gistCode) {
        mProgressBar.setVisibility(View.GONE);
        mBtnGist.setVisibility(View.GONE);
        mCodeText.setText(gistCode);
        mCodeText.setTextIsSelectable(true);
        mCodeText.append("\n\n" + getString(R.string.mp_gist_uploaded));
    }
}
