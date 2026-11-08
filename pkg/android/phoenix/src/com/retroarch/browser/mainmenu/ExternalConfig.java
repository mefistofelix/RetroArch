package com.retroarch.browser.mainmenu;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration on an external disk (USB stick, SD card).
 *
 * Convention: the first external disk that has RetroFire/retroarch.cfg at its root wins.
 * That file is a template: its keys are written over the app's own retroarch.cfg at every
 * start (keys it does not set are left alone), after expanding these tokens in the values:
 *
 *   {cfgdir}   the folder of the external retroarch.cfg       (/storage/XXXX-XXXX/RetroFire)
 *   {appdata}  the app's writable folder on that same disk    (/storage/XXXX-XXXX/Android/data/<package>/files)
 *   {appdir}   the app's internal data folder                 (/data/user/0/<package>)
 *
 * so the external config never contains the disk's serial number. Android only lets the app
 * write to its own folder on a removable disk, so saves, states and history go to {appdata}.
 *
 * Cores: Android does not run code from an external disk, so the .so files in
 * {cfgdir}/cores are copied to the app's internal cores folder when new or changed (and the
 * .info files next to them to the internal info folder). Nothing is ever deleted.
 *
 * Without an external config nothing changes: the app behaves as upstream RetroArch.
 */
public final class ExternalConfig
{
	private static final String TAG = "ExternalConfig";
	public static final String FOLDER = "RetroFire";
	public static final String CONFIG = "retroarch.cfg";

	public final File cfgDir;    // {cfgdir}
	public final File appData;   // {appdata}, may be null if Android gave none

	private ExternalConfig(File cfgDir, File appData)
	{
		this.cfgDir = cfgDir;
		this.appData = appData;
	}

	/** First external disk with FOLDER/CONFIG, or null. */
	public static ExternalConfig find(Context ctx)
	{
		List<File> roots = new ArrayList<File>();
		List<File> data = new ArrayList<File>();

		// Disks Android knows for this app: <root>/Android/data/<package>/files
		// (asking also creates that folder, the only one the app may write to).
		File[] dirs = android.os.Build.VERSION.SDK_INT >= 19 ? ctx.getExternalFilesDirs(null) : null;
		if (dirs != null)
		{
			for (File d : dirs)
			{
				if (d == null)
					continue;
				String p = d.getAbsolutePath();
				int i = p.indexOf("/Android/data/");
				if (i <= 0)
					continue;
				roots.add(new File(p.substring(0, i)));
				data.add(d);
			}
		}
		// Plus anything mounted under /storage that Android did not report.
		File[] mounted = new File("/storage").listFiles();
		if (mounted != null)
		{
			for (File m : mounted)
			{
				if (!roots.contains(m))
				{
					roots.add(m);
					data.add(null);
				}
			}
		}

		for (int i = 0; i < roots.size(); i++)
		{
			File cfg = new File(new File(roots.get(i), FOLDER), CONFIG);
			if (cfg.isFile() && cfg.canRead())
			{
				File appData = data.get(i);
				if (appData == null)
					appData = new File(roots.get(i), "Android/data/" + ctx.getPackageName() + "/files");
				Log.i(TAG, "Using " + cfg.getAbsolutePath() + ", writable folder " + appData);
				return new ExternalConfig(cfg.getParentFile(), appData);
			}
		}
		return null;
	}

	private String expand(Context ctx, String value)
	{
		return value
			.replace("{cfgdir}", cfgDir.getAbsolutePath())
			.replace("{appdata}", appData.getAbsolutePath())
			.replace("{appdir}", ctx.getApplicationInfo().dataDir);
	}

	/** key -> raw line value (with quotes), in file order. */
	private static Map<String, String> readTemplate(File f) throws IOException
	{
		Map<String, String> kv = new LinkedHashMap<String, String>();
		BufferedReader br = new BufferedReader(new FileReader(f));
		try
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				String t = line.trim();
				if (t.isEmpty() || t.startsWith("#"))
					continue;
				int eq = t.indexOf('=');
				if (eq <= 0)
					continue;
				kv.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
			}
		}
		finally
		{
			br.close();
		}
		return kv;
	}

	/**
	 * Writes the external config's keys over the app config at configPath.
	 * Every other line of the app config is kept as it is.
	 */
	public void applyTo(Context ctx, String configPath)
	{
		try
		{
			Map<String, String> tpl = readTemplate(new File(cfgDir, CONFIG));
			for (Map.Entry<String, String> e : tpl.entrySet())
			{
				String raw = e.getValue();
				e.setValue(expand(ctx, raw));
				// Folders in the writable area must exist: RetroArch does not create
				// them all (the content history, for one, is then never saved)
				if (raw.contains("{appdata}"))
				{
					String path = e.getValue().replace("\"", "");
					File f = new File(path);
					if (e.getKey().endsWith("_path"))
						f = f.getParentFile();
					if (f != null)
						f.mkdirs();
				}
			}

			List<String> out = new ArrayList<String>();
			File app = new File(configPath);
			if (app.isFile())
			{
				BufferedReader br = new BufferedReader(new FileReader(app));
				try
				{
					String line;
					while ((line = br.readLine()) != null)
					{
						int eq = line.indexOf('=');
						String key = eq > 0 ? line.substring(0, eq).trim() : null;
						if (key != null && tpl.containsKey(key))
						{
							out.add(key + " = " + tpl.remove(key));
							continue;
						}
						out.add(line);
					}
				}
				finally
				{
					br.close();
				}
			}
			for (Map.Entry<String, String> e : tpl.entrySet())
				out.add(e.getKey() + " = " + e.getValue());

			File tmp = new File(configPath + ".tmp");
			PrintWriter w = new PrintWriter(tmp);
			for (String l : out)
				w.println(l);
			w.close();
			if (!tmp.renameTo(app))
			{
				app.delete();
				tmp.renameTo(app);
			}
			if (appData != null)
				appData.mkdirs();
			Log.i(TAG, "Applied " + new File(cfgDir, CONFIG) + " to " + configPath);
		}
		catch (IOException e)
		{
			Log.e(TAG, "Could not apply the external config: " + e);
		}
	}

	/** Copies new or changed cores (and their .info files) from {cfgdir}/cores. Returns how many. */
	public int syncCores(Context ctx)
	{
		File src = new File(cfgDir, "cores");
		File[] files = src.listFiles();
		if (files == null)
			return 0;
		String dataDir = ctx.getApplicationInfo().dataDir;
		File cores = new File(dataDir, "cores");
		File info = new File(dataDir, "info");
		int copied = 0;
		for (File f : files)
		{
			String n = f.getName();
			File dst;
			if (n.endsWith(".so"))
				dst = new File(cores, n);
			else if (n.endsWith(".info"))
				dst = new File(info, n);
			else
				continue;
			if (dst.isFile() && dst.length() == f.length() && dst.lastModified() == f.lastModified())
				continue;
			try
			{
				copy(f, dst);
				copied++;
				Log.i(TAG, "Core file updated: " + dst);
			}
			catch (IOException e)
			{
				Log.e(TAG, "Could not copy " + f + ": " + e);
			}
		}
		return copied;
	}

	private static void copy(File from, File to) throws IOException
	{
		to.getParentFile().mkdirs();
		File tmp = new File(to.getPath() + ".tmp");
		InputStream in = new FileInputStream(from);
		OutputStream out = new FileOutputStream(tmp);
		try
		{
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
		}
		finally
		{
			in.close();
			out.close();
		}
		if (!tmp.renameTo(to))
		{
			to.delete();
			if (!tmp.renameTo(to))
				throw new IOException("rename failed");
		}
		to.setLastModified(from.lastModified());
	}
}
