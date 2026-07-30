package net.kdt.pojavlaunch;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;

import androidx.drawerlayout.widget.DrawerLayout;

import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.IOException;


public class CustomControlsActivity extends BaseActivity implements EditorExitable {
	private DrawerLayout mDrawerLayout;
	private ListView mDrawerNavigationView;
	private ControlLayout mControlLayout;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_custom_controls);

		mControlLayout = findViewById(R.id.customctrl_controllayout);
		mDrawerLayout = findViewById(R.id.customctrl_drawerlayout);
		mDrawerNavigationView = findViewById(R.id.customctrl_navigation_view);
		View mPullDrawerButton = findViewById(R.id.drawer_button);

		mPullDrawerButton.setOnClickListener(v -> mDrawerLayout.openDrawer(mDrawerNavigationView));
		mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

		String[] menuItems = getResources().getStringArray(R.array.menu_customcontrol_customactivity);
		// Phase-5 premium silver icon set (Req-11) — no more framework glyphs
		int[] menuIcons = new int[]{
				R.drawable.ic_ctrl_add_button,   // Add Button
				R.drawable.ic_ctrl_add_drawer,   // Add Drawer
				R.drawable.ic_ctrl_add_joystick, // Add Joystick
				R.drawable.ic_ctrl_add_command,  // Add Command Button
				R.drawable.ic_ctrl_load,         // Load
				R.drawable.ic_ctrl_save,         // Save
				R.drawable.ic_ctrl_default,      // Default
				R.drawable.ic_ctrl_export        // Export
		};
		mDrawerNavigationView.setAdapter(new ArrayAdapter<String>(this, R.layout.item_custom_control_menu, R.id.menu_item_text, menuItems) {
			@androidx.annotation.NonNull
			@Override
			public View getView(int position, @androidx.annotation.Nullable View convertView, @androidx.annotation.NonNull ViewGroup parent) {
				View view = super.getView(position, convertView, parent);
				ImageView icon = view.findViewById(R.id.menu_item_icon);
				if (icon != null && position < menuIcons.length) {
					icon.setImageResource(menuIcons[position]);
				}
				return view;
			}
		});
		mDrawerNavigationView.setOnItemClickListener((parent, view, position, id) -> {
			android.util.Log.i("CustomControlsActivity", "Menu item clicked: position=" + position);
			switch(position) {
				case 0: android.util.Log.i("CustomControlsActivity", "Action: Add Button"); mControlLayout.addControlButton(new ControlData("New")); break;
				case 1: android.util.Log.i("CustomControlsActivity", "Action: Add Button Drawer"); mControlLayout.addDrawer(new ControlDrawerData()); break;
				case 2: android.util.Log.i("CustomControlsActivity", "Action: Add Joystick"); mControlLayout.addJoystickButton(new ControlJoystickData()); break;
				case 3: {
					android.util.Log.i("CustomControlsActivity", "Action: Add Command Button");
					net.kdt.pojavlaunch.customcontrols.ControlData cmdData = new net.kdt.pojavlaunch.customcontrols.ControlData("Command");
					cmdData.keycodes[0] = net.kdt.pojavlaunch.customcontrols.ControlData.SPECIALBTN_CHATCOMMAND;
					mControlLayout.addControlButton(cmdData);
					// Open the edit dialog for this new button immediately
					final net.kdt.pojavlaunch.customcontrols.ControlData finalCmdData = cmdData;
					mControlLayout.post(() -> {
						for (int i = 0; i < mControlLayout.getChildCount(); i++) {
							android.view.View child = mControlLayout.getChildAt(i);
							if (child instanceof net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface) {
								net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface iface = (net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface) child;
								if (iface.getProperties() == finalCmdData) {
									mControlLayout.editControlButton(iface);
									break;
								}
							}
						}
					});
					break;
				}
				case 4: android.util.Log.i("CustomControlsActivity", "Action: Load"); mControlLayout.openLoadDialog(); break;
				case 5: android.util.Log.i("CustomControlsActivity", "Action: Save"); mControlLayout.openSaveDialog(this); break;
				case 6: android.util.Log.i("CustomControlsActivity", "Action: Select Default"); mControlLayout.openSetDefaultDialog(); break;
				case 7: // Saving the currently shown control
					android.util.Log.i("CustomControlsActivity", "Action: Share layout");
					try {
						Uri contentUri = DocumentsContract.buildDocumentUri(getString(R.string.storageProviderAuthorities), mControlLayout.saveToDirectory(mControlLayout.mLayoutFileName));

						Intent shareIntent = new Intent();
						shareIntent.setAction(Intent.ACTION_SEND);
						shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
						shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						shareIntent.setType("application/json");
						startActivity(shareIntent);

						Intent sendIntent = Intent.createChooser(shareIntent, mControlLayout.mLayoutFileName);
						startActivity(sendIntent);
					}catch (Exception e) {
						Tools.showError(this, e);
					}
					break;
			}
			mDrawerLayout.closeDrawers();
		});
		mControlLayout.setModifiable(true);
		try {
			mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
		}catch (IOException e) {
			Tools.showError(this, e);
		}
	}

	@Override
	public void onBackPressed() {
		mControlLayout.askToExit(this);
	}

	@Override
	public void exitEditor() {
		super.onBackPressed();
	}
}
