package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;

public class DownloadListFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    private static final String ARG_TYPE = "content_type";

    private String mContentType;
    private RecyclerView mRecyclerView;
    private ProgressBar mProgressBar;
    private View mLoadingCard;
    private TextView mStatusText;
    private ModItemAdapter mAdapter;
    private ModpackApi mApi;

    private OnModItemClickListener mItemClickListener;

    public interface OnModItemClickListener {
        void onItemClick(ModItem item);
    }

    public void setOnModItemClickListener(OnModItemClickListener listener) {
        mItemClickListener = listener;
    }

    public String getContentType() {
        return mContentType;
    }

    public static DownloadListFragment newInstance(String type) {
        Bundle args = new Bundle();
        args.putString(ARG_TYPE, type);
        DownloadListFragment f = new DownloadListFragment();
        f.setArguments(args);
        return f;
    }

    public DownloadListFragment() {
        super(R.layout.fragment_download_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mContentType = getArguments() != null ? getArguments().getString(ARG_TYPE, "mod") : "mod";

        mRecyclerView = view.findViewById(R.id.download_list);
        mProgressBar = view.findViewById(R.id.download_list_progress);
        mLoadingCard = view.findViewById(R.id.download_loading_card);
        mStatusText = view.findViewById(R.id.download_list_status);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.addItemDecoration(new net.kdt.pojavlaunch.modloaders.modpacks.SpacesItemDecoration(12));

        // Use ModrinthApi directly for non-standard types (CF doesn't support them)
        if (mContentType.equals("mod")) {
            mApi = new CommonApi(requireContext().getString(R.string.curseforge_api_key));
        } else {
            mApi = new ModrinthApi();
        }

        mAdapter = new ModItemAdapter(getResources(), mApi, this);
        mRecyclerView.setAdapter(mAdapter);

        mAdapter.setOnItemClickListener(item -> {
            if (mItemClickListener != null) {
                mItemClickListener.onItemClick(item);
            }
        });

        // Infrawire "Powered by" badge → Official Partners page (elegant, not an ad)
        View poweredBadge = view.findViewById(R.id.infrawire_powered_badge);
        if (poweredBadge != null) {
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.applyPressAnimation(poweredBadge);
            poweredBadge.setOnClickListener(v ->
                    net.kdt.pojavlaunch.sponsor.InfrawirePartner.openPartnerPage(requireActivity()));
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.fadeIn(poweredBadge, 200);
        }

        loadContent();
    }

    private void loadContent() {
        SearchFilters filters = buildFilters("");
        showLoadingCapsule();
        mProgressBar.setVisibility(View.VISIBLE);
        mAdapter.performSearchQuery(filters);
    }

    public void filter(String query) {
        filter(query, null, null);
    }

    public void filter(String query, @Nullable String mcVersion, @Nullable String modLoader) {
        SearchFilters filters = buildFilters(query != null ? query : "");
        filters.mcVersion = mcVersion != null && !mcVersion.isEmpty() ? mcVersion : null;
        filters.modLoader = modLoader != null && !modLoader.isEmpty() ? modLoader : null;
        showLoadingCapsule();
        mProgressBar.setVisibility(View.VISIBLE);
        mAdapter.performSearchQuery(filters);
    }

    /** Loading capsule drops in softly instead of popping. */
    private void showLoadingCapsule() {
        if (mLoadingCard == null) return;
        if (mLoadingCard.getVisibility() == View.VISIBLE) return;
        mLoadingCard.setVisibility(View.VISIBLE);
        mLoadingCard.setAlpha(0f);
        mLoadingCard.setTranslationY(-12f *
                getResources().getDisplayMetrics().density);
        mLoadingCard.animate().alpha(1f).translationY(0f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void hideLoadingCapsule() {
        if (mLoadingCard == null || mLoadingCard.getVisibility() != View.VISIBLE) return;
        mLoadingCard.animate().cancel();
        mLoadingCard.animate().alpha(0f).translationY(-8f *
                getResources().getDisplayMetrics().density)
                .setDuration(180)
                .withEndAction(() -> {
                    mLoadingCard.setVisibility(View.GONE);
                    mLoadingCard.setAlpha(1f);
                    mLoadingCard.setTranslationY(0f);
                })
                .start();
    }

    private SearchFilters buildFilters(String query) {
        SearchFilters filters = new SearchFilters();
        filters.name = query;
        if (mContentType.equals("world")) {
            // Modrinth : "world" project type nahi hai — "datapack" type + adventure category use karo
            filters.projectType = "datapack";
            filters.categories = "adventure";
            filters.isModpack = false;
        } else if (mContentType.equals("modpack")) {
            filters.projectType = "modpack";
            filters.isModpack = true;
        } else {
            filters.projectType = mContentType;
            filters.isModpack = false;
        }
        return filters;
    }

    @Override
    public void onSearchFinished() {
        mProgressBar.setVisibility(View.GONE);
        hideLoadingCapsule();
        mStatusText.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mProgressBar.setVisibility(View.GONE);
        hideLoadingCapsule();
        mStatusText.setVisibility(View.VISIBLE);
        // Status pill fades in rather than popping
        mStatusText.setAlpha(0f);
        mStatusText.animate().alpha(1f).setDuration(220).start();
        switch (error) {
            case ERROR_INTERNAL:
                mStatusText.setTextColor(android.graphics.Color.parseColor("#E5A0A6"));
                mStatusText.setText(R.string.search_mod_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusText.setTextColor(android.graphics.Color.parseColor("#9C9CA8"));
                mStatusText.setText(R.string.search_mod_no_result);
                break;
        }
    }
}
