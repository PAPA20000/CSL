package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional resource browser fragment for installing mods, resource packs, or shader packs.
 * Can be accessed after profile creation or from the profile editor.
 */
public class ResourceBrowserFragment extends Fragment {
    public static final String TAG = "ResourceBrowserFragment";
    private static final String ARG_PROFILE_KEY = "profile_key";

    private String mProfileKey;
    private MinecraftProfile mProfile;
    private File mGameDir;

    private RecyclerView mInstalledList;
    private TextView mEmptyText;
    private TextView mSubtitle;

    // Tabs
    private TextView mTabMods, mTabResourcePacks, mTabShaderPacks;
    private String mCurrentTab = "mods"; // "mods", "resourcepacks", "shaderpacks"

    // Import launcher
    private final ActivityResultLauncher<String> mFilePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> handleFileImport(uri));

    public ResourceBrowserFragment() {
        super(R.layout.fragment_resource_browser);
    }

    public static ResourceBrowserFragment newInstance(String profileKey) {
        ResourceBrowserFragment fragment = new ResourceBrowserFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROFILE_KEY, profileKey);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getArguments() != null) {
            mProfileKey = getArguments().getString(ARG_PROFILE_KEY);
        }

        // Load profile
        if (mProfileKey != null) {
            LauncherProfiles.load();
            mProfile = LauncherProfiles.mainProfileJson.profiles.get(mProfileKey);
        }

        // Resolve game directory
        if (mProfile != null) {
            mGameDir = Tools.getGameDirPath(mProfile);
        } else {
            mGameDir = new File(Tools.DIR_GAME_NEW);
        }

        // Setup views
        mInstalledList = view.findViewById(R.id.resource_installed_list);
        mEmptyText = view.findViewById(R.id.resource_empty_text);
        mSubtitle = view.findViewById(R.id.resource_browser_subtitle);
        mTabMods = view.findViewById(R.id.tab_mods);
        mTabResourcePacks = view.findViewById(R.id.tab_resourcepacks);
        mTabShaderPacks = view.findViewById(R.id.tab_shaderpacks);

        mInstalledList.setLayoutManager(new LinearLayoutManager(requireContext()));

        // Setup tabs
        mTabMods.setOnClickListener(v -> switchTab("mods"));
        mTabResourcePacks.setOnClickListener(v -> switchTab("resourcepacks"));
        mTabShaderPacks.setOnClickListener(v -> switchTab("shaderpacks"));

        // Import button
        view.findViewById(R.id.resource_import_button).setOnClickListener(v -> {
            mFilePicker.launch("*/*");
        });

        // Skip and Done buttons
        view.findViewById(R.id.resource_skip_button).setOnClickListener(v -> finish());
        view.findViewById(R.id.resource_done_button).setOnClickListener(v -> finish());

        // Update subtitle
        if (mProfile != null) {
            mSubtitle.setText("Profile: " + mProfile.name);
        }

        // Load installed resources
        loadInstalledResources();
    }

    private void switchTab(String tab) {
        mCurrentTab = tab;

        // Update tab colors
        mTabMods.setTextColor(tab.equals("mods") ? 0xFFFFFFFF : 0xFFAAAAAA);
        mTabResourcePacks.setTextColor(tab.equals("resourcepacks") ? 0xFFFFFFFF : 0xFFAAAAAA);
        mTabShaderPacks.setTextColor(tab.equals("shaderpacks") ? 0xFFFFFFFF : 0xFFAAAAAA);

        loadInstalledResources();
    }

    private void loadInstalledResources() {
        if (mGameDir == null || !mGameDir.exists()) {
            mInstalledList.setVisibility(View.GONE);
            mEmptyText.setVisibility(View.VISIBLE);
            return;
        }

        File resourcesDir = new File(mGameDir, mCurrentTab);
        if (!resourcesDir.exists()) {
            resourcesDir.mkdirs();
        }

        File[] files = resourcesDir.listFiles((dir, name) ->
                name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".disabled"));

        List<String> resourceNames = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                resourceNames.add(f.getName());
            }
        }

        if (resourceNames.isEmpty()) {
            mInstalledList.setVisibility(View.GONE);
            mEmptyText.setVisibility(View.VISIBLE);
        } else {
            mInstalledList.setVisibility(View.VISIBLE);
            mEmptyText.setVisibility(View.GONE);
            mInstalledList.setAdapter(new ResourceAdapter(resourceNames));
        }
    }

    private void handleFileImport(Uri uri) {
        if (uri == null || mGameDir == null) return;

        try {
            File targetDir = new File(mGameDir, mCurrentTab);
            targetDir.mkdirs();

            String fileName = getFileName(uri);
            if (fileName == null) fileName = "imported_resource.jar";

            File targetFile = new File(targetDir, fileName);

            try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                if (is == null) {
                    Toast.makeText(getContext(), "Failed to read file", Toast.LENGTH_SHORT).show();
                    return;
                }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            Toast.makeText(getContext(), "Imported: " + fileName, Toast.LENGTH_SHORT).show();
            loadInstalledResources();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileName(Uri uri) {
        String name = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = requireContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        name = cursor.getString(idx);
                    }
                }
            }
        }
        if (name == null) {
            name = uri.getPath();
            if (name != null) {
                int cut = name.lastIndexOf('/');
                if (cut != -1) {
                    name = name.substring(cut + 1);
                }
            }
        }
        return name;
    }

    private void finish() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).refreshHomeState();
        } else {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RESOURCE ADAPTER
    // ══════════════════════════════════════════════════════════════════════════

    private class ResourceAdapter extends RecyclerView.Adapter<ResourceAdapter.VH> {
        private final List<String> mItems;

        ResourceAdapter(List<String> items) {
            mItems = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_wizard_version, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String name = mItems.get(position);
            h.versionName.setText(name);
            h.versionTypeBadge.setText("Installed");
            h.versionTypeBadge.setTextColor(0xFF00FF41);
            h.selectedDot.setVisibility(View.GONE);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView versionName;
            TextView versionTypeBadge;
            View selectedDot;

            VH(@NonNull View v) {
                super(v);
                versionName = v.findViewById(R.id.version_name);
                versionTypeBadge = v.findViewById(R.id.version_type_badge);
                selectedDot = v.findViewById(R.id.version_selected_dot);
            }
        }
    }
}
