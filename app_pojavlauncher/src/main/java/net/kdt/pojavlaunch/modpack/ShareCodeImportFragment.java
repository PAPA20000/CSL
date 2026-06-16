package net.kdt.pojavlaunch.modpack;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.fragments.ProfileEditorFragment;

import org.json.JSONObject;

/**
 * Fragment for importing a modpack from a share code (CS-MP-... or CS-MP-GH-...).
 * One-click recreate the profile from the decoded manifest.
 */
public class ShareCodeImportFragment extends Fragment {

    public static final String TAG = "ShareCodeImportFragment";

    private TextInputEditText mCodeInput;
    private TextInputLayout mCodeInputLayout;
    private MaterialButton mBtnImport, mBtnPaste;
    private ProgressBar mProgressBar;
    private TextView mStatusText;

    public ShareCodeImportFragment() { super(R.layout.fragment_share_code_import); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mCodeInput = view.findViewById(R.id.import_code_input);
        mCodeInputLayout = view.findViewById(R.id.import_code_layout);
        mBtnImport = view.findViewById(R.id.btn_import);
        mBtnPaste = view.findViewById(R.id.btn_paste);
        mProgressBar = view.findViewById(R.id.import_progress);
        mStatusText = view.findViewById(R.id.import_status);

        mBtnImport.setEnabled(false);
        mBtnImport.setOnClickListener(v -> doImport());

        mCodeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int s2, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int s2, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {
                String raw = s != null ? s.toString().trim() : "";
                mBtnImport.setEnabled(!raw.isEmpty());
                mCodeInputLayout.setError(null);
            }
        });

        mBtnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence paste = cm.getPrimaryClip().getItemAt(0).getText();
                if (paste != null) {
                    mCodeInput.setText(paste.toString().trim());
                    mCodeInput.setSelection(mCodeInput.getText().length());
                }
            }
        });

        view.findViewById(R.id.btn_close).setOnClickListener(v -> {
            if (getParentFragment() != null) {
                getParentFragmentManager().popBackStack();
            } else {
                requireActivity().onBackPressed();
            }
        });
    }

    private void doImport() {
        String code = mCodeInput.getText() != null
                ? mCodeInput.getText().toString().trim() : "";

        if (code.isEmpty()) {
            mCodeInputLayout.setError(getString(R.string.mp_import_code_required));
            return;
        }

        if (!ShareCodeEncoder.isOfflineCode(code) && !ShareCodeEncoder.isGistCode(code)) {
            mCodeInputLayout.setError(getString(R.string.mp_import_invalid_code));
            return;
        }

        mBtnImport.setEnabled(false);
        mProgressBar.setVisibility(View.VISIBLE);
        mStatusText.setText(R.string.mp_import_decoding);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                final String manifestJson;

                if (ShareCodeEncoder.isGistCode(code)) {
                    Tools.runOnUiThread(() ->
                            mStatusText.setText(R.string.mp_import_downloading));
                    String gistId = ShareCodeEncoder.decodeGistId(code);
                    manifestJson = ShareCodeHelper.downloadGist(gistId);
                } else {
                    manifestJson = ShareCodeEncoder.decodeOffline(code);
                }

                // Validate it's proper JSON
                JSONObject manifest = new JSONObject(manifestJson);

                // Rebuild the modpack from manifest
                ModpackGenerator gen = new ModpackGenerator(requireContext(), manifest);
                String profileKey = gen.generate();

                Tools.runOnUiThread(() -> {
                    mProgressBar.setVisibility(View.GONE);
                    mStatusText.setText(R.string.mp_import_success);

                    // Navigate to the imported profile
                    Bundle args = new Bundle();
                    args.putString("profile_key", profileKey);
                    Tools.swapFragment(requireActivity(),
                            ProfileEditorFragment.class, ProfileEditorFragment.TAG, args);
                });

            } catch (Exception e) {
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                Tools.runOnUiThread(() -> {
                    mProgressBar.setVisibility(View.GONE);
                    mBtnImport.setEnabled(true);
                    mStatusText.setTextColor(
                            getResources().getColor(android.R.color.holo_red_light));
                    mStatusText.setText(getString(R.string.mp_import_failed, errorMsg));
                });
            }
        });
    }
}
