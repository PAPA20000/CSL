package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import net.kdt.pojavlaunch.client.ClientFeature;

/**
 * Settings entry point for the CS CLIENT ecosystem. The old FastClient toggle
 * is replaced by the premium "Enable Client Feature" card, which opens the
 * install wizard and shows the current client state.
 */
public class FastClientHelper {

    private static final String PREF_NAME = "fastclient_prefs";
    private static final String KEY_ENABLED = "fc_enabled";
    private static final String KEY_VERSION = "fc_version";

    public static void setup(View rootView, Context ctx, FragmentManager fm) {
        View card = rootView.findViewById(R.id.card_enable_client_feature);
        if (card == null) return;

        TextView tvStatus = rootView.findViewById(R.id.tv_fastclient_version_info);

        // Legacy FastClient flag migrates into the new unified state.
        if (!ClientFeature.isEnabled(ctx)) {
            SharedPreferences legacy = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (legacy.getBoolean(KEY_ENABLED, false)) {
                String ver = legacy.getString(KEY_VERSION, "v1.6.2");
                ClientFeature.markEnabled(ctx, "1.21.1", null);
            }
        }

        updateStatus(ctx, tvStatus);

        card.setOnClickListener(v -> {
            if (!(ctx instanceof AppCompatActivity)) return;
            ClientFeature.openWizard((AppCompatActivity) ctx);
        });
    }

    private static void updateStatus(Context ctx, TextView tvStatus) {
        if (tvStatus == null) return;
        if (ClientFeature.isEnabled(ctx)) {
            tvStatus.setVisibility(View.VISIBLE);
            String mc = ClientFeature.getMcVersion(ctx);
            tvStatus.setText("✓ CS CLIENT enabled" + (mc != null ? " — Minecraft " + mc : ""));
        } else {
            tvStatus.setVisibility(View.GONE);
        }
    }
}
