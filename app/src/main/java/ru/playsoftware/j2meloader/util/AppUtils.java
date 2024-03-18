/*
 * Copyright 2018-2020 Nikita Shakarun
 * Copyright 2019-2024 Yury Kharchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.util;

import static ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ConfigActivity;
import ru.woesss.j2me.jar.Descriptor;

public class AppUtils {
	private static final String TAG = AppUtils.class.getSimpleName();

	private static ArrayList<AppItem> getAppsList(@NonNull List<String> appFolders) {
		ArrayList<AppItem> apps = new ArrayList<>();
		File appsDir = new File(Config.getAppDir());
		for (String appFolderName : appFolders) {
			File appFolder = new File(appsDir, appFolderName);
			if (!appFolder.isDirectory()) {
				if (!appFolder.delete()) {
					Log.e(TAG, "getAppsList() failed delete file: " + appFolder);
				}
				continue;
			}
			File compressed = new File(appFolder, Config.MIDLET_DEX_ARCH);
			if (!compressed.isFile()) {
				File dex = new File(appFolder, Config.MIDLET_DEX_FILE);
				if (!dex.isFile()) {
					FileUtils.deleteDirectory(appFolder);
					continue;
				}
			}
			try {
				AppItem item = getApp(appFolder);
				apps.add(item);
			} catch (Exception e) {
				Log.w(TAG, "getAppsList: ", e);
				FileUtils.deleteDirectory(appFolder);
			}
		}
		return apps;
	}

	private static AppItem getApp(File appDir) throws IOException {
		File mf = new File(appDir, Config.MIDLET_MANIFEST_FILE);
		Descriptor params = new Descriptor(mf, false);
		AppItem item = new AppItem(appDir.getName(), params.getName(),
				params.getVendor(),
				params.getVersion());
		File icon = new File(appDir, Config.MIDLET_ICON_FILE);
		if (icon.exists()) {
			item.setImagePathExt(Config.MIDLET_ICON_FILE);
		} else {
			String iconPath = Config.MIDLET_RES_DIR + '/' + params.getIcon();
			icon = new File(appDir, iconPath);
			if (icon.exists()) {
				item.setImagePathExt(iconPath);
			}
		}
		return item;
	}

	public static void deleteApp(AppItem item) {
		File appDir = new File(item.getPathExt());
		FileUtils.deleteDirectory(appDir);
		File appSaveDir = new File(Config.getDataDir(), item.getPath());
		FileUtils.deleteDirectory(appSaveDir);
		File appConfigsDir = new File(Config.getConfigsDir(), item.getPath());
		FileUtils.deleteDirectory(appConfigsDir);
		ShortcutManagerCompat.removeDynamicShortcuts(ContextHolder.getAppContext(), List.of(item.getPathExt()));
	}

	public static void updateDb(AppRepository appRepository, List<AppItem> items) {
		File tmp = new File(Config.getAppDir(), ".tmp");
		if (tmp.exists()) {
			// TODO: 30.07.2021 incomplete installation - maybe can continue?
			FileUtils.deleteDirectory(tmp);
		}
		String[] appFolders = new File(Config.getAppDir()).list();
		if (appFolders == null || appFolders.length == 0) {
			// If db isn't empty
			if (items.size() != 0) {
				appRepository.deleteAll();
				removeFromRecentShortcuts(items);
			}
			return;
		}
		List<String> appFoldersList = new ArrayList<>(Arrays.asList(appFolders));
		// Delete invalid app items from db
		ListIterator<AppItem> iterator = items.listIterator(items.size());
		while (iterator.hasPrevious()) {
			AppItem item = iterator.previous();
			if (appFoldersList.remove(item.getPath())) {
				iterator.remove();
			}
		}
		if (items.size() > 0) {
			appRepository.delete(items);
			removeFromRecentShortcuts(items);
		}
		if (appFoldersList.size() > 0) {
			appRepository.insert(getAppsList(appFoldersList));
		}
	}

	public static Bitmap getIconBitmap(AppItem appItem) {
		String file = appItem.getImagePathExt();
		if (file == null) {
			return null;
		}
		return BitmapFactory.decodeFile(file);
	}

	public static void addShortcut(Context context, AppItem appItem) {
		Bitmap bitmap = getIconBitmap(appItem);
		IconCompat icon;
		if (bitmap == null) {
			icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher);
		} else {
			int width = bitmap.getWidth();
			int height = bitmap.getHeight();
			ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			int iconSize = am.getLauncherLargeIconSize();
			Rect src;
			if (width > height) {
				int left = (width - height) / 2;
				src = new Rect(left, 0, left + height, height);
			} else if (width < height) {
				int top = (height - width) / 2;
				src = new Rect(0, top, width, top + width);
			} else {
				src = null;
			}
			Bitmap scaled = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(scaled);
			canvas.drawBitmap(bitmap, src, new RectF(0, 0, iconSize, iconSize), null);
			icon = IconCompat.createWithBitmap(scaled);
		}
		String title = appItem.getTitle();
		Intent launchIntent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(appItem.getPathExt()),
				context, ConfigActivity.class);
		launchIntent.putExtra(KEY_MIDLET_NAME, title);
		ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, title)
				.setIntent(launchIntent)
				.setShortLabel(title)
				.setIcon(icon)
				.build();
		ShortcutManagerCompat.requestPinShortcut(context, shortcut, null);
	}

	public static void pushToRecentShortcuts(Context context, String appPath, String appName, File iconFile) {
		try {
			IconCompat icon;
			if (iconFile == null) {
				icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher);
			} else {
				icon = IconCompat.createWithContentUri(Uri.fromFile(iconFile));
			}
			Intent launchIntent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(appPath),
					context, ConfigActivity.class);
			launchIntent.putExtra(KEY_MIDLET_NAME, appName);
			ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, appPath)
					.setIntent(launchIntent)
					.setShortLabel(appName)
					.setIcon(icon)
					.build();
			ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
		} catch (Exception e) {
			Log.e(TAG, "pushToRecentShortCuts()", e);
		}
	}

	public static void removeFromRecentShortcuts(List<AppItem> items) {
		List<String> shortcuts = new ArrayList<>();
		for (AppItem item : items) {
			shortcuts.add(item.getPathExt());
		}
		ShortcutManagerCompat.removeDynamicShortcuts(ContextHolder.getAppContext(), shortcuts);
	}
}
