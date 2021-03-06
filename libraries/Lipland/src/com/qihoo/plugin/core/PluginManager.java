/*
 * Copyright (C) 2005-2017 Qihoo 360 Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.qihoo.plugin.core;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.app.LoadedApk;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser.ActivityIntentInfo;
import android.content.pm.PackageParser.Service;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.Camera;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Xml;

import com.qihoo.plugin.BuildConfig;
import com.qihoo.plugin.Config;
import com.qihoo.plugin.ILog;
import com.qihoo.plugin.IPlugin;
import com.qihoo.plugin.IPluginLoadListener;
import com.qihoo.plugin.base.Actions;
import com.qihoo.plugin.base.ActivityStub;
import com.qihoo.plugin.base.BaseProxyActivity;
import com.qihoo.plugin.base.ConfigFilter;
import com.qihoo.plugin.base.DefaultLogHandler;
import com.qihoo.plugin.base.DefaultPluginLoadHandler;
import com.qihoo.plugin.base.HostGlobal;
import com.qihoo.plugin.base.PluginCarshHandler;
import com.qihoo.plugin.base.PluginHelper;
import com.qihoo.plugin.base.PluginProcessStartup;
import com.qihoo.plugin.bean.ActivityStatus;
import com.qihoo.plugin.bean.LibInfo;
import com.qihoo.plugin.bean.LoadTimeInfo;
import com.qihoo.plugin.bean.Plugin;
import com.qihoo.plugin.bean.PluginContextInfo;
import com.qihoo.plugin.bean.PluginInfo;
import com.qihoo.plugin.bean.PluginPackage;
import com.qihoo.plugin.bean.SerializableWrapper;
import com.qihoo.plugin.bean.UpdateInfo;
import com.qihoo.plugin.core.hook.ILocationManagerHacker;
import com.qihoo.plugin.core.hook.InstrumentationHacker;
import com.qihoo.plugin.install.InstallCheck;
import com.qihoo.plugin.install.InstallManager;
import com.qihoo.plugin.update.UpdateFilter;
import com.qihoo.plugin.update.UpdateManager;
import com.qihoo.plugin.update.UpdateService;
import com.qihoo.plugin.util.ApkUtil;
import com.qihoo.plugin.util.CodeTraceTS;
import com.qihoo.plugin.util.IO;
import com.qihoo.plugin.util.MD5Util;
import com.qihoo.plugin.util.NetworkManager;
import com.qihoo.plugin.util.NetworkManager.INetworkChange;
import com.qihoo.plugin.util.PluginUtil;
import com.qihoo.plugin.util.RWLock;
import com.qihoo.plugin.util.RefUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.DexClassLoader;

/**
 * 插件管理类，提供加载插件，管理插件以及一些通用方法
 *
 * @author xupengpai
 * @date 2014年12月18日 下午3:35:55
 *
 */
public class PluginManager {

	public final static int DEFAULT_THREAD_MAX_COUNT = 10;
	private ExecutorService threadPool;
	private Map<String,UncaughtExceptionHandler> crashHandlers;
	private boolean enableCrashHandler = false;
	private ProxyActivityPool proxyActivityPool;
	private static boolean inited = false;
	private boolean isPluginProcess;

	private ActivityThread activityThread;

	private Handler mainHandler;

	public final static String KEY_COMMAND_INTENT = "__COMMAND_INTENT";
    public static final String KEY_ORIGIN_INTENT = "__ORIGIN_INTENT";
	public final static String KEY_IS_PLUGIN_ACTIVITY = "__IS_PLUGIN_ACTIVITY";
	public final static String KEY_IS_PLUGIN_INTENT = "__IS_PLUGIN_INTENT";
	public final static String KEY_IS_CUSTOM_PROXY_ACTIVITY = "__IS_CUSTOM_PROXY_ACTIVITY";
	public final static String KEY_IS_ALONE_PROCESS = "__IS_ALONE_PROCESS";
	public final static String KEY_TARGET_CLASS_NAME = "__TARGET_CLASS_NAME";
	public final static String KEY_CLASSLOADER_TAG = "__CLASSLOADER_TAG";
	public final static String KEY_PLUGIN_TAG = "____PLUGIN_TAG";
	public final static String KEY_BASE_PLUGIN_OBJECT = "__BASE_PLUGIN_OBJECT";
	public final static String KEY_LOADING_POS = "__LOADING_POS";
	public final static String KEY_EXCEPTION_OBJECT = "__EXCEPTION_OBJECT";
	public final static String KEY_ERROR_CODE = "__ERROR_CODE";
	public final static String KEY_PLUGIN_PATH = "__PLUGIN_PATH";
	public final static String KEY_PLUGIN_CALLBACK = "__PLUGIN_IMPL_CLASS";
	public final static String KEY_ALONE_PROCESS = "__ALONE_PROCESS";
	public final static String HOST_TAG = "________________HOST_____TAG_____ - -!  0.0  o.o o.0";
	public final static int ERROR_CODE_APK_PATH_IS_NULL = 1;
	public final static int ERROR_CODE_SIGNATURE = 2;
	public final static int ERROR_CODE_CLASSLOADER_IS_NULL = 3;
	public final static int ERROR_CODE_TAG_IS_NULL = 4;
	public final static int ERROR_CODE_COPY_APK_TO_WORK_DIR = 5;

	public final static int TAG_CLASS_LOADER_NAME = 0;

	// 未指定初始化类的情况下，插件默认初始化类名，路径为插件：[插件包名].DEFAULT_PLUGIN_INIT_CLASS
	public final static String DEFAULT_PLUGIN_INIT_CLASS = "PluginImpl";

	public final static String TAG = "PluginManager";
	private static Map<String, PluginManager> instances = new HashMap<String, PluginManager>();

	// 版本规则限定，永远只有3位，每位大小不限
	public final static String VERSION = BuildConfig.VERSION;

	// 插件管理器名称，也作为插件进程后缀
	private String name;

	private Application application;
	private InstallManager installManager;
	private NetworkManager networkManager;
	private static InstrumentationHacker instrumentation;
	private Map<String, Plugin> plugins;
	private Class<? extends BaseProxyActivity> defaultProxyActivity = ProxyActivity.class;
	private List<WeakReference<Activity>> pluginActivities;
	private IPluginLoadListener defaultPluginLoadListener;
	private Map<String,Map<String,LibInfo>> libs;
	private Boolean isHostLibsInited = false;
	private String pluginProcessName;

	private final static boolean DEFAULT_IS_USE_WORK_DIR = true;
	public final static String DEFAULT_SIGN_MD5 = "";

	public final static int FLAG_USE_WEB_VIEW = 1<<0;
	public final static int FLAG_USE_RESOURCES = 1<<1;
	public final static int FLAG_USE_RECEIVER = 1<<2;
	public final static int FLAG_USE_NATIVE_LIBRARY = 1<<3;
	public final static int FLAG_USE_ACTIVITY = 1<<4;
	public final static int FLAG_USE_PROVIDER = 1<<5;
	public final static int FLAG_LOAD_DEX = 0;
	public final static int FLAG_USE_ALL = FLAG_USE_ACTIVITY | FLAG_USE_WEB_VIEW | FLAG_USE_RESOURCES | FLAG_USE_RECEIVER |
							FLAG_USE_NATIVE_LIBRARY | FLAG_USE_PROVIDER;

	public final static boolean DEFAULT_USE_UPDATE_MANAGER = true;

	private ConfigFilter configFilter;
	private List<PluginInfo> defaultPluginList;
	private Map<String,IPluginLoadListener> pluginLoadListeners;

	private boolean isDefaultPluginsInstalled = false;

	/**
	 * 是否使用工作目录，如果原插件可能会被修改，则尽量设置为true，默认为true 如果为false，可以省去一步拷贝插件操作，节省加载时间
	 */
	private boolean useWorkDir;

//	/**
//	 * 加载插件时验证的签名，如果为空则不验证签名
//	 */
//	private String signMD5 = DEFAULT_SIGN_MD5;

	//插件签名列表
	private List<String> signList;
	//是否验证插件签名
	private boolean isVerifySign;

	public boolean isUseWorkDir() {
		return useWorkDir;
	}

	public void setUseWorkDir(boolean useWorkDir) {
		this.useWorkDir = useWorkDir;
	}

	public List<String> getSignList() {
		return signList;
	}

	public String getName() {
		return name;
	}

	public void setConfigFilter(ConfigFilter configFilter) {
		this.configFilter = configFilter;
	}

	public void setName(String name) {
		this.name = name;
	}

	public static void setInstrumentation(InstrumentationHacker instrumentation) {
		PluginManager.instrumentation = instrumentation;
	}

	public static InstrumentationHacker getInstrumentation() {
		return instrumentation;
	}

	public InstallManager getInstallManager() {
		return installManager;
	}


	public ActivityThread getActivityThread() {
		return activityThread;
	}

	public void setDefaultProxyActivity(
			Class<? extends BaseProxyActivity> defaultProxyActivity) {
		this.defaultProxyActivity = defaultProxyActivity;
	}

	public Class<? extends BaseProxyActivity> getDefaultProxyActivity() {
		return defaultProxyActivity;
	}

	public boolean isPluginProcess(){
		return HostGlobal.getProcessName().equals(getPluginProcessName());
	}

	public boolean isMainProcess(){
		return HostGlobal.isMainProcess();
	}

//	public void setPluginProcess(boolean isPluginProcess){
//		this.isPluginProcess = isPluginProcess;
//	}

	//在插件运行崩溃的情况下，可能导致它对应的ProxyActivity非正常退出，而无法置空闲位
	//如果这种情况持续出现，会导致ProxyActivity被无限占用的情况
	//该方法就是在插件进程启动时调用，重置所有的ProxyActivity。
	public void resetProxyActivitiesStatus(){
		ProxyActivityPool.notifyReset(application);
	}

	private HandlerThread backgroundThread;
	private Handler backgroundHandler;

	public Handler getBackgroundHandler(){
		if(backgroundThread == null) {
			backgroundThread = new HandlerThread("PluginManager_HandlerThread_backgroundThread");
			backgroundThread.start();
			backgroundHandler = new Handler(backgroundThread.getLooper());
		}
		return backgroundHandler;
	}

	/**
	 * 维护一个异步线程消息队列处理耗时操作，方便加快应用启动速度
	 * @param run
	 */
	public void runInBackground(Runnable run){
		getBackgroundHandler().post(run);
	}

	public void runInBackground(Runnable run,int delay){
		getBackgroundHandler().postDelayed(run,delay);
	}


	/**
	 * 安装并处理化插件管理器
	 * @param application
	 */
	public static boolean setup(Application app){
		return setup(app,true,false,false);
	}



	private static boolean setup(final Application app,final boolean startPluginProcess,boolean installDefaultPlugin,boolean forceInstallDefaultPlugin){

		HostGlobal.init(app);


		// 4.0以下版本不支持
		if (android.os.Build.VERSION.SDK_INT < VERSION_CODES.ICE_CREAM_SANDWICH) {
			return false;
		}

		if(!inited){

			final PluginManager pm = PluginManager.getInstance();

			pm.init();

			HostApplicationProxy.hook(app);

			pm.runInBackground(new Runnable() {
				@Override
				public void run() {
					NetworkManager.getInstance(app);
					app.registerComponentCallbacks(new ComponentCallbacks() {

						@Override
						public void onLowMemory() {
							// TODO Auto-generated method stub

						}

						@Override
						public void onConfigurationChanged(Configuration newConfig) {
							// 同步插件中资源的onConfigurationChanged事件
							Map<String, Plugin> plugins = PluginManager.getInstance().getPlugins();
							for (Plugin p : plugins.values()) {
								p.getRes().updateConfiguration(newConfig, app.getResources().getDisplayMetrics());
							}
						}
					});

					if(pm.isMainProcess() && startPluginProcess){
						PluginHelper.startPluginProcess(null);
					}

					if(pm.isPluginProcess()) {

//					if (installDefaultPlugin)
//						pm.installDefaultPlugins(forceInstallDefaultPlugin);

						pm.resetProxyActivitiesStatus();

//						//加载有loadOnAppStarted标志的插件，该标志表示在应用启动时在后台将插件加载到内存
//						//有这个标志的插件，启动相对会快一点，但是在没有启动时也会占用部分内存。
//						pm.loadPluginOnAppStarted();

							}

//					if(pm.isPluginProcess() && startUpdate){
//						pm.startUpdate();
//					}
				}
			});

			inited = true;
		}

		return true;
	}


	public PluginManager(String name, Application application) {

		this.name = name;
		this.application = application;
		this.useWorkDir = DEFAULT_IS_USE_WORK_DIR;
		this.plugins = new HashMap<String, Plugin>();
		this.mainHandler = new Handler(Looper.getMainLooper());
		this.pluginActivities = new ArrayList<WeakReference<Activity>>();
		this.defaultPluginLoadListener = new DefaultPluginLoadHandler();
		this.pluginLoadListeners = new HashMap<String, IPluginLoadListener>();
		this.libs = new HashMap<String, Map<String,LibInfo>>();
		this.threadPool = Executors
				.newFixedThreadPool(DEFAULT_THREAD_MAX_COUNT);
		this.crashHandlers = new HashMap<String, Thread.UncaughtExceptionHandler>();
	}



	//初始化一些工具类、管理类，可能有一定消耗
	public void init(){

		CodeTraceTS.begin();

		if(signList == null)
			signList = new ArrayList<>();

		signList.add(DEFAULT_SIGN_MD5);
		registerMonitor(application);

		activityThread = ActivityThread.currentActivityThread();

		this.installManager = new InstallManager(this,activityThread,application,DEFAULT_USE_UPDATE_MANAGER);

		Log.i(TAG,"installManager "+CodeTraceTS.end().time() + "ms");

		CodeTraceTS.begin();
		this.networkManager = NetworkManager.getInstance(application);
		this.proxyActivityPool = new ProxyActivityPool();
		Log.i(TAG,"networkManager "+CodeTraceTS.end().time() + "ms");

		//在线程中做一些可能耗时的操作，尽量减少主线程的耗时
		threadPool.execute(new Runnable() {
			@Override
			public void run() {

//				getPluginProcessName();
//				HostGlobal.getProcessName();
				proxyActivityPool.init(application);

			}
		});

		CodeTraceTS.begin();
		initHostLibraryInfo();
		Log.i(TAG,"initHostLibraryInfo "+CodeTraceTS.end().time() + "ms");

	}

	public void setDefaultPluginLoadListener(
			IPluginLoadListener defaultPluginLoadListener) {
		this.defaultPluginLoadListener = defaultPluginLoadListener;
	}

	public void setDefaultPluginLoadListener(String tag,
			IPluginLoadListener defaultPluginLoadListener) {
		this.pluginLoadListeners.put(tag, defaultPluginLoadListener);
	}

	public IBinder getToken(Activity activity) {
		return (IBinder) RefUtil.getFieldValue(activity, "mToken");
	}


	//获取PluginProcessStartup服务的进程名，将其作为插件进程名
	public String getPluginProcessName(){
;		if(pluginProcessName != null)
			return pluginProcessName;

		//默认为主进程
		pluginProcessName = application.getPackageName();

		PackageInfo pi = null;
		try {
			pi = application.getPackageManager().getPackageInfo(application.getPackageName(),PackageManager.GET_SERVICES);
		} catch (PackageManager.NameNotFoundException e) {
			e.printStackTrace();
			Log.e(TAG,"",e);
			return pluginProcessName;
		}

		Log.d(TAG,"getPluginProcessName()...pi="+pi);
		if(pi.services != null) {
			//只有注册了的坑才有效
			Log.d(TAG,"getPluginProcessName()...services.length="+pi.services.length);
			for (ServiceInfo info : pi.services) {
				if(info.name.equals(PluginProcessStartup.class.getName())){
					if(info.processName != null && !"".equals(info.processName.trim())) {
						if (info.processName.startsWith(":")) {
							pluginProcessName = application.getPackageName() + info.processName;
						}else{
							pluginProcessName = info.processName;
						}

					}
					break;
				}
			}
		}
		return pluginProcessName;
	}

//	public boolean isRunningPluginActivity(Activity activity){
//		List<WeakReference<Activity>> nullRefList = new ArrayList<WeakReference<Activity>>();
//		for(WeakReference<Activity> ref : pluginActivities){
//			Activity act = ref.get();
//			if(act != null){
//				if(getToken(act)==getToken(activity))
//					return true;
//			}else{
//				nullRefList.add(ref);
//			}
//		}
//		//清理已经销毁的activity
//		for(WeakReference<Activity> ref : nullRefList){
//			pluginActivities.remove(ref);
//		}
//		return false;
//	}

	// 是否为自己的进程
	public boolean isSelfProcess() {
		if (isEmpty(name)) {
			return HostGlobal.isMainProcess();
		} else {
			return (HostGlobal.getPackageName() + ":" + name).equals(HostGlobal
					.getProcessName());
		}

	}

	private static boolean isEmpty(String str) {
		return str == null || str.trim().equals("");
	}

	public static PluginManager newInstance(String name, Application application) {
		String key = HostGlobal.getPackageName();
		if (!isEmpty(name)) {
			key += ":" + name;
		}
		PluginManager pluginManager = instances.get(key);
		if (pluginManager == null) {
			pluginManager = new PluginManager(name, application);
			instances.put(key, pluginManager);
		}
		return pluginManager;
	}

	private static String getPluginKey(String name) {
		String key = HostGlobal.getPackageName();
		if (!isEmpty(name)) {
			key += ":" + name;
			String[] strs = name.split(":");
			if (strs.length == 2) {
				key += ":" + strs[1];
			}
		}
		return key;
	}

	private static PluginManager newSelfInstance(Application application) {

		String procName = HostGlobal.getProcessName();
		String[] strs = procName.split(":");
		String name = null;
		if (strs.length == 2) {
			name = strs[1];
		}

		return newInstance(name, application);
	}

	/**
	 * 或者当前进程自己的PluginManager实例 实际上就是以进程后缀命名的插件管理器，如果是主进程，则名称为空
	 *
	 * @return
	 */
	public static PluginManager getSelfInstance() {
		String procName = HostGlobal.getProcessName();
		PluginManager pluginManager = instances.get(procName);
		if (pluginManager == null)
			pluginManager = newSelfInstance(HostGlobal.getBaseApplication());
		return pluginManager;
	}

	/**
	 * 或者指定进程的PluginManager实例
	 *
	 * @return
	 */
	public static PluginManager getInstance(String name) {
		return newInstance(name, HostGlobal.getBaseApplication());
	}


	/**
	 * 或者指定进程的PluginManager实例
	 *
	 * @return
	 */
	public static PluginManager getInstance() {
		return getSelfInstance();
	}

	public boolean install(String tag, int versionCode, String apkPath) {
		return installManager.install(tag, versionCode, apkPath);
	}


	public boolean install(String tag, String versionName, String apkPath) {
		return installManager.install(tag, versionName, apkPath);
	}


	public boolean uninstall(String tag) {
		Log.i(TAG, "uninstall::tag=" + tag);
		installManager.uninstall(tag);
		if(!isLoaded(tag)){
			//如果插件当前未加载，则彻底清除插件，如果已经加载，为保险起见，不删除文件。
			new File(Config.getPluginDir(tag)).delete();
			plugins.remove(tag);
			cleanPlugin(tag);
		}
		return true;
	}

	//比对 "文件名"+.md5文件，来确定文件是否需要拷贝
	private boolean needCopy(String src,String target){
		File srcMd5File = new File(src + ".md5");
		File targetMd5File = new File(target + ".md5");

		if(!srcMd5File.exists() || !targetMd5File.exists()){
			return true;
		}
		try {
			String srcMd5 = IO.readString(srcMd5File);
			String targetMd5 = IO.readString(targetMd5File);
			return !(srcMd5 != null && srcMd5.equals(targetMd5));
//			return true;
		}catch(Exception e){
			return true;
		}

	}

	public void cleanPlugin(String tag){
        //清理插件残余信息
        libs.remove(tag);
    }

	/**
	 * 添加未处理完毕的插件，便于下次启动时处理
	 * @param tag
	 * @param path
	 */
	public void addUnprocessdPlugin(String tag,String path){
		Log.d(TAG, "addUnprocessdPlugin()...tag=" + tag + ",path="+path);
		String value = tag + "," + path;
		SharedPreferences pref = application.getSharedPreferences("PluginManager" , Context.MODE_PRIVATE);
		Set<String> set = pref.getStringSet("unprocessd",null);
		Log.d(TAG, "addUnprocessdPlugin()...set=" + set);
		if(set == null){
			set = new HashSet<>();
		}else{
			set = new HashSet<>(set);
		}
		set.add(value);
		Log.d(TAG, "addUnprocessdPlugin()...value=" + value);
		SharedPreferences.Editor editor = pref.edit();
		editor.putStringSet("unprocessd",set);
		editor.commit();

		set = pref.getStringSet("unprocessd",null);
		Log.d(TAG, "addUnprocessdPlugin()...222--set=" + set);


	}

	public void handlePendingInstallPlugin(){
		Log.d(TAG, "handlePendingInstallPlugin()...");
		try {
			String path = Config.getPluginPendingInstallDir();
			Log.d(TAG, "handlePendingInstallPlugin()...path="+path);
			String files[] = new File(path).list();
			if (files != null && files.length > 0) {
				for(String fileName : files){
					String file = path + "/" + fileName;
					Log.d(TAG, "handlePendingInstallPlugin()...file="+file);
					try {
						UpdateInfo updateInfo = (UpdateInfo) IO.unserialize(file);
						Log.d(TAG, "handlePendingInstallPlugin()...updateInfo="+updateInfo);
						if (updateInfo != null) {
							UpdateManager.getInstance().installPlugin(updateInfo);
						}
						new File(file).delete();
					}catch (Exception e){
						Log.e(TAG, e);
					}
				}
			}
		}catch(Exception e){
			Log.e(TAG, e);
		}
	}

    //处理上一次安装时未预处理完毕的插件。
	//比如一个正在运行的插件被更新的情况
    public void preproccess(){
		Log.d(TAG, "preproccess()...");
		SharedPreferences pref = application.getSharedPreferences("PluginManager" , Context.MODE_PRIVATE);
		Set<String> set = pref.getStringSet("unprocessd",null);
		Log.d(TAG, "preproccess()...set=" + set);
		if(set != null){

			Iterator<String> iter = set.iterator();
			while(iter.hasNext()){
				String value = iter.next();
				String[] items = value.split(",");
				if(items != null && items.length > 1){
					String tag = items[0];
					String path = items[1];
					Log.d(TAG, "preproccess(),tag=" + tag + ",path="+path);
					preproccessPlugin(tag, path, false);
				}else{
					Log.e(TAG, "preproccess(),error,value=" + value + ",items="+items);
				}
			}

			SharedPreferences.Editor editor = pref.edit();
			editor.putStringSet("unprocessd",null);
			editor.commit();

		}
	}

	//预处理插件
	public void preproccessPlugin(String tag,String path,boolean force){

		try {

			//拷贝到工作目录，在加载时，如果md5相同，则不会再拷贝
			CodeTraceTS.begin();
			String workApkPath = getPluginApkWorkPath(tag,path);
			boolean needUpdate = copyApkToWorkDir(tag, path,workApkPath);
			Log.i(TAG, "preproccessPlugin::needUpdate=" + needUpdate);
			Log.i(TAG, "preproccessPlugin::workApkPath=" + workApkPath);
			Log.i(TAG, "preproccessPlugin::copyApkToWorkDir " + CodeTraceTS.end().time() + "ms");


			if (force || needUpdate) {

				//解压和配置so库
				CodeTraceTS.begin();
				String libPath = Config.getLibPath(tag);

				unzipLibs(tag, workApkPath, libPath);

				Log.i(TAG, "preproccessPlugin::unzipLibs,libPath=" + libPath);
				Log.i(TAG, "preproccessPlugin::unzipLibs " + CodeTraceTS.end().time() + "ms");

				CodeTraceTS.begin();
				String soPath = createNativeLibraryPath(tag);
				Log.i(TAG, "preproccessPlugin::createNativeLibraryPath " + CodeTraceTS.end().time() + "ms");
				Log.i(TAG, "preproccessPlugin::createNativeLibraryPath,soPath=" + soPath);


				CodeTraceTS.begin();
				//加载一次apk，让系统进行编译，以后的加载可以大幅度提升速度。
				//此处会段时间内占用一部分内存，因为没有引用，之后会被gc。
				DexClassLoaderEx classLoader = new DexClassLoaderEx(tag, libs, application,
						workApkPath, Config.getDexWorkPath(tag),
						soPath, application.getClassLoader().getParent(),
						application.getClassLoader());
//			dex2oat(workApk,Config.getDexWorkPath(pi.tag)+new File(workApk).getName() + ".dex");
				Log.i(TAG, "preproccessPlugin::createClassLoader " + CodeTraceTS.end().time() + "ms");
			}
		}catch(Throwable thr){
			//预处理属于优化操作，出现任何异常都不应该影响程序的正常运行
			Log.e(TAG, thr);
		}

	}


	public void dex2oat(String file,String targetFile) {
		String cmd = String.format("dex2oat --debuggable --compiler-filter=speed --dex-file=%s --oat-file=%s", file, targetFile);
		try {
			Runtime.getRuntime().exec(cmd);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}


//	public void preproccessPluginClassLoader(PluginInfo pi){
//
//		//拷贝到工作目录，在加载时，如果md5相同，则不会再拷贝
//		CodeTraceTS.begin();
//		String workApk = copyApkToWorkDir(pi.tag,pi.path);
//		Log.i(TAG, "preproccessPluginClassLoader::workApk=" + workApk);
//		Log.i(TAG, "preproccessPluginClassLoader::copyApkToWorkDir " + CodeTraceTS.end().time() + "ms");
//
//
//		if(workApk != null) {
//
//			//解压和配置so库
//			CodeTraceTS.begin();
//			String libPath = Config.getLibPath(pi.tag);
//
//
//			CodeTraceTS.begin();
//			String soPath = createNativeLibraryPath(pi.tag);
//			Log.i(TAG, "preproccessPluginClassLoader::createNativeLibraryPath " + CodeTraceTS.end().time() + "ms");
//			Log.i(TAG, "preproccessPluginClassLoader::createNativeLibraryPath,soPath=" + soPath);
//
//			CodeTraceTS.begin();
//			//加载一次apk，让系统进行编译，以后的加载可以大幅度提升速度。
//			//此处短时间内占用一部分内存，因为没有引用，之后会被gc。
//			DexClassLoaderEx classLoader = new DexClassLoaderEx(pi.tag,libs,application,
//					workApk, Config.getDexWorkPath(pi.tag),
//					soPath, application.getClassLoader().getParent(),
//					application.getClassLoader());
//			Log.i(TAG, "preproccessPluginClassLoader::createClassLoader " + CodeTraceTS.end().time() + "ms");
//		}
//
//	}


	public void preproccessPluginLibrary(PluginInfo pi){

		//解压和配置so库
		CodeTraceTS.begin();
		String libPath = Config.getLibPath(pi.tag);

		unzipLibs(pi.tag,pi.path, libPath);

		Log.i(TAG, "preproccessPlugin::unzipLibs,libPath=" + libPath);
		Log.i(TAG, "preproccessPlugin::unzipLibs " + CodeTraceTS.end().time() + "ms");

	}

	public String getStackTraceString(Throwable thr) {
		if (thr == null) {
			return "";
		}

		Throwable t = thr;
		while (t != null) {
			if (t instanceof UnknownHostException) {
				return "";
			}
			t = t.getCause();
		}

		StringWriter sw = new StringWriter();

		PrintWriter pw = new PrintWriter(sw, false);
		thr.printStackTrace(pw);
		pw.flush();
		return sw.toString();
	}

	public void postExceptionToHost(String key,String msg,Throwable thr){
		Intent intent = new Intent("action.qdas.event");
		intent.putExtra("key", key);
		intent.putExtra("msg", msg);
		intent.putExtra("exception", getStackTraceString(thr));
		application.sendBroadcast(intent);
	}

	/**
	 *
	 * @param tag
	 * @param path
	 * @param workApkPath
	 * @return 只有在需要拷贝且拷贝成功的情况下才会返回true
	 */
	private boolean copyApkToWorkDir(String tag,String path,String workApkPath) {
		if (path == null)
			return false;
		File src = new File(path);

		if (src.isFile()) {

            if(needCopy(src.getAbsolutePath(), workApkPath)){
				byte[] bytes = null;
				String md5 = null;
				try {
					bytes = IO.readBytes(src.getAbsolutePath());
					md5 = MD5Util.md5str(bytes);
				} catch (IOException e) {
					Log.e(TAG,e);
					String msg = "tag="+tag+",path="+path+",workApkPath="+workApkPath+
							",md5="+md5+",bytes="+bytes;

					postExceptionToHost("copyApkToWorkDir", msg, e);
				}
//				md5=null;
				if(md5 != null && bytes != null){
					try {
						IO.writeBytes(workApkPath, bytes);
						IO.writeString(src.getAbsolutePath() + ".md5",md5);
						IO.writeString(workApkPath + ".md5",md5);
						return true;
					}catch (IOException e){
						Log.e(TAG,e);
						String msg = "tag="+tag+",path="+path+",workApkPath="+workApkPath+
								",md5="+md5+",bytes="+bytes;

						postExceptionToHost("copyApkToWorkDir", msg, e);
						return false;
					}
				}else{
					String msg = "tag="+tag+",path="+path+",workApkPath="+workApkPath+
							",md5="+md5+",bytes="+bytes;
					postExceptionToHost("copyApkToWorkDir", msg, null);
					return false;
				}
            }
            return false;
		}
		String msg = "tag="+tag+",path="+path;
		postExceptionToHost("copyApkToWorkDir", "src.isFile() == false" + "," + msg, null);
		return false;
	}

	/**
	 * 判断插件是否已经加载
	 * @param tag
	 * @return
	 */
	public boolean isLoaded(String tag){
		Iterator<String> iter = plugins.keySet().iterator();
		Log.d(TAG,"isLoaded(),tag=" + tag + "," +plugins.size());
		while(iter.hasNext()){
			Log.d(TAG,"isLoaded()," + iter.next());
		}
		return plugins.containsKey(tag);
	}

	private String getSignMD5(Signature[] signs) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			for (Signature signature : signs) {
				out.write(signature.toByteArray());
			}
			return MD5Util.md5str(out.toByteArray());
		} catch (Exception e) {
			Log.w(TAG, "getSignMD5::exception," + e);
		} finally {
			try {
				out.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * 验证签名
	 *
	 * @param pi
	 * @return
	 */
	private boolean verifySign(PackageInfo pi) {
		if(this.signList != null){
			String pluginSign = getSignMD5(pi.signatures);
			for(String sign : signList){
				if(sign.equals(pluginSign)){
					return true;
				}
			}
		}
		return false;
	}
//	private boolean verifySign(Signature[] signatures) {
//		if (!isEmpty(signMD5)) {
//			if (signatures == null) {
//				return false;
//			}
//			return getSignMD5(signatures).equals(signMD5);
//		} else {
//			return true;
//		}
//	}

	private String createNativeLibraryPath(String tag) {
		// getWorkDir() + "/tmp"
		// String nativePath = application.getDir("lib", 0) + "/" + tag;
		String nativePath = Config.getLibPath(tag);
		// String nativePath = getWorkDir() + "/tmp" + "/" + tag;
		// String nativePath =
		// "/data/data/"+application.getApplicationInfo().packageName + "/lib"+
		// "/" + tag;

		String soPath = nativePath + "/lib/armeabi";
		new File(soPath).mkdirs();

		if (Build.CPU_ABI.equals("armeabi-v7a")
				|| Build.CPU_ABI2.equals("armeabi-v7a")) {
			String v7aPath = nativePath + "/lib/armeabi-v7a";
			new File(v7aPath).mkdirs();
			soPath = v7aPath + ":" + soPath;
		}
		return soPath;
	}

	private static boolean testBit(int flag, int bit) {
		return (flag & bit) > 0;
	}

	/**
	 * 加载一个已经安装的插件
	 * 	 * @param tag
	 * @param listener
	 * @return
	 */
	public Plugin load(String tag, IPluginLoadListener listener) {
		PluginInfo info = getInstalledPluginInfo(tag);
		if (info == null) {
			Log.e(TAG,
					"load():: Plugin is not installed,please call to install () for installation. tag="
							+ tag);
			Log.e(TAG,installManager.getInstalledPlugins().toString());

			return null;
		}

		if(!new File(info.path).isFile()){
			Log.e(TAG,
					"load():: File not found,"
							+ info.path);
			return null;
		}

		if(listener == null){
			if(this.pluginLoadListeners.containsKey(tag)){
				listener = this.pluginLoadListeners.get(tag);
			}else{
				listener = defaultPluginLoadListener;
			}
		}
		return load(tag, info.path, null, listener, FLAG_USE_ALL);
	}

	/**
	 * 加载一个已经安装的插件
	 *
	 * @param tag
	 * @return
	 */
	public Plugin load(String tag) {
		return load(tag, defaultPluginLoadListener);
	}

	// 加载apk插件，默认带处理so，资源、webview
	public Plugin load(String tag, String apkPath, String callbackClass,
			IPluginLoadListener listener) {

		if (listener == null)
			listener = new DefaultPluginLoadHandler();

		return load(tag, apkPath, callbackClass, listener,
				FLAG_USE_ALL);
	}

	// 加载apk插件，默认带处理so，资源、webview
	public Plugin load(String tag, String path,
			IPluginLoadListener listener) {
		return load(tag, path, null, listener);
	}


	public Plugin load(String tag,String path) {
		return load(tag, path, null, null);
	}


	public String getPluginApkWorkPath(String tag,String apkPath){
		String workPath = Config.getCurrentProcessPluginWorkDir(tag);
		String workApkPath = workPath + "/" + new File(apkPath).getName();
		return workApkPath;
	}

	/**
	 * 加载一个插件 flag为处理内容细节，根据情况调节可以提高加载速度，减少不必要的资源浪费。
	 *
	 * @param tag
	 * @param apkPath
	 * @param callbackClass
	 * @param listener
	 * @param flag
	 * @return
	 */
	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	public synchronized Plugin load(String tag, String apkPath, String callbackClass,
			final IPluginLoadListener listener, int flags) {

		LoadTimeInfo ts = new LoadTimeInfo();

		Log.i(TAG, "loadPlugin::arguments tag=" + tag + ",apkPath=" + apkPath
				+ ",callbackClass=" + callbackClass + ",listener=" + listener);

		if (isEmpty(tag) || isEmpty(apkPath) || listener == null) {
			Log.e(TAG,
					"loadPlugin::error,tag/apkPath/listener can't be null,tag="
							+ tag + ",apkPath=" + apkPath);
			listener.onError(tag, ERROR_CODE_APK_PATH_IS_NULL);
			return null;
		}
		if (!isSelfProcess()) {
			Log.e(TAG,
					"loadPlugin::error,Only load plug in its own process！,tag="
							+ tag + ",apkPath=" + apkPath);
			listener.onError(tag, ERROR_CODE_APK_PATH_IS_NULL);
			return null;
		}

		if(isLoaded(tag)){
			Log.e(TAG, "Plug-repeated loading,tag="+tag);
			return getPlugin(tag);
		}


		listener.onStart(tag);

		String srcPath = apkPath;

		Log.i(TAG, "loadPlugin::useWorkDir=" + useWorkDir);
		if (useWorkDir) {

			CodeTraceTS.begin("preproccessPlugin");

			preproccessPlugin(tag, apkPath, false);

			ts.copyToWork = CodeTraceTS.end("preproccessPlugin").time();

			if(apkPath == null){
				Log.e(TAG,
						"loadPlugin::error,copyApkToWorkDir, tag="
								+ tag + ",apkPath=" + apkPath);
				listener.onError(tag, ERROR_CODE_COPY_APK_TO_WORK_DIR);
				return null;
			}


			Log.i(TAG, "loadPlugin::copyApkToWorkDir,apkPath=" + apkPath);
		}

		listener.onLoading(tag, 10);


		CodeTraceTS.begin();
		PluginPackage pluginPackage = installManager.getInstalledPlugin(tag);
		ts.getInstalledPlugin = CodeTraceTS.end().time();

		CodeTraceTS.begin();
		PackageInfo pi = null;
		if(pluginPackage != null){
			try{
				pi = ApkUtil.generatePackageInfo(pluginPackage.pkg, PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES
						| (testBit(flags, FLAG_USE_ALL) ? PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS | PackageManager.GET_SERVICES
								: 0));
////				List<ProviderInfo> list = new ArrayList<ProviderInfo>();
//				if(pi.providers!=null && pi.providers.length>0){
////					java.util.Collections.addAll(list, pi.providers);
////					ActivityThread.currentActivityThread().installSystemProviders(list);
//					installProviders(ActivityThread.currentActivityThread(),pi.providers);
//				}

			}catch(Exception e){
				Log.e(TAG, e);
			}
		}

		//兼容调用内部API解析失败的情况
		if(pi == null){
			pi = application
					.getPackageManager()
					.getPackageArchiveInfo(
							apkPath,
							PackageManager.GET_SIGNATURES | PackageManager.GET_META_DATA
									| (testBit(flags, FLAG_USE_ALL) ? PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS | PackageManager.GET_SERVICES
											: 0));
		}
		ts.parseApk = CodeTraceTS.end().time();

		Log.i(TAG, "loadPlugin::getPackageArchiveInfo,pi=" + pi);

		CodeTraceTS.begin();
		if (isVerifySign && !verifySign(pi)) {
			Log.e(TAG, String.format("[%s] signature error", apkPath));
			listener.onError(tag, ERROR_CODE_SIGNATURE);
			return null;
		}
		ts.verifySign = CodeTraceTS.end().time();

		listener.onLoading(tag, 30);

		CodeTraceTS.begin();
		String soPath = createNativeLibraryPath(tag);
		Log.i(TAG, "loadPlugin::createNativeLibraryPath,soPath=" + soPath);

		CodeTraceTS.begin();
		DexClassLoaderEx classLoader = new DexClassLoaderEx(tag,libs,application,
				apkPath, Config.getDexWorkPath(tag),
				soPath, application.getClassLoader().getParent(),
				application.getClassLoader());
		Log.i(TAG, "loadPlugin::createClassLoader,this.getClass().getClassLoader()=" + this.getClass().getClassLoader());
		Log.i(TAG, "loadPlugin::createClassLoader,this.getClass().getClassLoader().getParent()=" + this.getClass().getClassLoader().getParent());
		Log.i(TAG, "loadPlugin::createClassLoader,application.getClassLoader()=" + application.getClassLoader());
		Log.i(TAG, "loadPlugin::createClassLoader,application.getClassLoader().getParent()=" + application.getClassLoader().getParent());
		Log.i(TAG, "loadPlugin::createClassLoader,calssLoader=" + classLoader);
		Log.i(TAG, "loadPlugin::createClassLoader,calssLoader=" + ClassLoader.getSystemClassLoader());

		ts.createClassLoader = CodeTraceTS.end().time();

		listener.onLoading(tag, 50);

		final Plugin p = new Plugin();
		p.setCl(classLoader);
		p.setPath(apkPath);
		p.setSrcPath(srcPath);
		p.setTag(tag);
		p.setPackageInfo(pi);
		p.setPluginPackage(pluginPackage);

		if (pi != null) {
			p.setActivityInfo(pi.activities);
			if (tag == null)
				tag = pi.packageName;
		}

		CodeTraceTS.begin();
		try {
			if (testBit(flags, FLAG_USE_RESOURCES)) {
				p.setRes(loadResources(application, apkPath,
						testBit(flags, FLAG_USE_WEB_VIEW)));
			}
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			Log.e(TAG,e1);
		}
		ts.loadResources = CodeTraceTS.end().time();



		CodeTraceTS.begin();
		/**
		 * 注册receiver
		 */
		if (testBit(flags, FLAG_USE_RECEIVER)) {
			registerReceivers(p);
		}
		ts.registerReceivers = CodeTraceTS.end().time();

		CodeTraceTS.begin();
		/**
		 * 解压so
		 */
		if (testBit(flags, FLAG_USE_NATIVE_LIBRARY)) {
			setPluginLibrary(tag);
//			unzipLibs(tag,apkPath, Config.getLibPath(tag));
		}
		ts.unzipLibs = CodeTraceTS.end().time();

		loadBroadcastReceivers(p, apkPath);

		listener.onLoading(tag, 80);

		String appClassName = Application.class.getName();
		if (pi.applicationInfo.className != null)
			appClassName = pi.applicationInfo.className;

		final UncaughtExceptionHandler origHandler = Thread.getDefaultUncaughtExceptionHandler();


		CodeTraceTS.begin();
		// 为插件创建一个application
		try {
			createApplicationContext(pi, p);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			Log.e(TAG, e);
		}
		ts.createApplication = CodeTraceTS.end().time();
		listener.onLoading(tag, 90);

		if (callbackClass == null && pi != null) {
			callbackClass = pi.packageName + "." + DEFAULT_PLUGIN_INIT_CLASS;
		}

		IPlugin callback = null;
		try {
			callback = (IPlugin) p.loadClass(callbackClass).newInstance();
			p.setCallback(callback);
		} catch (Exception e) {
			// 运行回调接口不存在
		}

		plugins.put(p.getTag(), p);

		CodeTraceTS.begin();
		hookContext(p.getApplication(), p);
		ts.hookContext = CodeTraceTS.end().time();

//		// 更新插件信息到数据库
//		GlobalDataManager.updatePluginInfo(tag, p.getPath());

		if (callback != null) {
			Runnable runnable = new Runnable() {

				@Override
				public void run() {
					// TODO Auto-generated method stub
					try {

						// 调用回调
						p.getCallback().onLoad(p,
								application.getApplicationContext(),
								HostGlobal.isMainProcess());

//						// 更新插件信息到数据库
//						GlobalDataManager.updatePluginInfo(p.getTag(),
//								p.getPath());

						loadCompleted(p,origHandler,listener);

						UncaughtExceptionHandler newHandler1 = Thread.getDefaultUncaughtExceptionHandler();
						Log.i(TAG,"newHandler1======"+ newHandler1);

					} catch (Exception e) {
						if(enableCrashHandler){
							CrashHandlerDispatcher.getInstance().setToApp();
						}
						Log.e(TAG, e);
						listener.onThrowException(p.getTag(), e);
					}

				}
			};

			if(isMainThread()){
				runnable.run();
			}else{
				mainHandler.post(runnable);
			}

		} else {
			loadCompleted(p,origHandler,listener);

		}

		//加载耗时统计
		TimeStatistics.putLoadTime(tag, ts);

		return p;

	}

	private void loadCompleted(Plugin p,UncaughtExceptionHandler origHandler,IPluginLoadListener listener){

		String tag = p.getTag();

		//比较UncaughtExceptionHandler是否被插件设置，如果被替换则缓存起来
		//如果宿主开启了插件异常处理，则替换掉插件设置的异常处理，由插件管理器负责分派
		UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
		if(origHandler != currentHandler){
			crashHandlers.put(tag, currentHandler);
			if(enableCrashHandler){
				CrashHandlerDispatcher.getInstance().setToApp();
			}
		}

		listener.onLoading(tag, 100);
		listener.onComplete(tag, p);

	}


	public ContentResolverWrapper getContentResolverWrapper(String tag) {
		return new ContentResolverWrapper(tag, application.getPackageName(),
				application.getContentResolver());
	}

	public ContentResolverWrapper getContentResolverWrapper() {
		return getContentResolverWrapper(null);
	}

	//设置插件签名的MD5，在加载插件时会验证md5，如果为null，则不验证
	public void addPluginSign(String md5) {
		if(this.signList == null){
			this.signList = new ArrayList<>();
		}
		this.signList.add(md5);
	}

	public void setVerifySign(boolean isVerifySign){
		this.isVerifySign = isVerifySign;
	}

	public Application getApplicationContext() {
		return application;
	}

	/**
	 * 判断类是否为插件中的可序列化对象
	 *
	 * @param cls
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	private static boolean isSerialzableObject(Class cls) {
		if (cls.getClassLoader().getClass().equals(DexClassLoaderEx.class)
				|| cls.getClassLoader().getClass().equals(DexClassLoader.class)) {
			Class[] interfaces = RefUtil.getInterfaces(cls);

			for (Class i : interfaces) {
				if (i.equals(Serializable.class))
					return true;
			}
		}
		return false;
	}

	private String getPackageName(String path) {

		PackageInfo pi = application.getPackageManager().getPackageArchiveInfo(
				path, PackageManager.GET_ACTIVITIES);

		if (pi != null)
			return pi.packageName;
		return null;
	}

	public boolean isInstalled(String tag){
		return installManager.isInstalled(tag);
	}

	private void loadBroadcastReceivers(Plugin p, String path) {
		// PackageInfo pi =
		// application.getPackageManager().getPackageArchiveInfo(path,
		// PackageManager.GET_RECEIVERS).receivers[];
	}

	private void initHostLibraryInfo(){
		//host只初始化一次
		synchronized (isHostLibsInited) {
			if (!isHostLibsInited) {
				isHostLibsInited = true;
				File nativeLibraryDir = new File(application.getApplicationInfo().nativeLibraryDir);
				File[] files = nativeLibraryDir.listFiles();
				if (files != null && files.length > 0) {

					Map<String, LibInfo> infos = new HashMap<String, LibInfo>();
					for (File file : files) {

						LibInfo info = new LibInfo();

						String libName = file.getName().substring(3, file.getName().length() - 3);
						info.name = libName;
						info.mappingName = libName;
						info.fileName = file.getName();
						info.path = file.getAbsolutePath();
						info.tag = null;
						infos.put(info.name, info);

					}
					libs.put(HOST_TAG, infos);
				}
			}
		}
	}

	private Map<String,RWLock> unzipApkLibsLockMap = new HashMap<>();


	private RWLock getUnzipApkLibsLock(String tag){
		//控制解压操作的可重入性，保证插件加载时，so一定解压完毕
		RWLock unzipLock = null;
		synchronized (unzipApkLibsLockMap){
			if(!unzipApkLibsLockMap.containsKey(tag)){
				unzipLock = new RWLock();
				unzipApkLibsLockMap.put(tag,unzipLock);
			}else {
				unzipLock = unzipApkLibsLockMap.get(tag);
			}
		}
		return unzipLock;
	}

	private void setPluginLibrary(String tag){

		RWLock unzipLock = getUnzipApkLibsLock(tag);

		unzipLock.lock();
		try {
			if (!libs.containsKey(tag)) {
				String str = Config.getLibraryInfo(tag);
				Map<String, LibInfo> infos = LibInfo.toMap(str);
				libs.put(tag, infos);
			}
		}finally {
			unzipLock.unlock();
		}

	}

	@SuppressLint("NewApi")
	private void unzipLibs(String tag,String apkPath, String nativePath) {

		// String abi = "armeabi";
		//
		// if (Build.CPU_ABI.equals("armeabi-v7a")
		// || Build.CPU_ABI2.equals("armeabi-v7a")) {
		// abi = "armeabi-v7a";
		// }

		// ApkUtil.unzipApkLibs(apkPath, getWorkDir() + "/tmp", nativePath,
		// abi);
		//
		//
		// Log.e("unzipApkLibs",
		// "================================================");
		// nativePath = Config.getPluginDir() + "/map";

		RWLock unzipLock = getUnzipApkLibsLock(tag);

		unzipLock.lock();

		try {
			ApkUtil.unzipApkLibs3(libs, tag, apkPath, nativePath, true);
			Map<String, LibInfo> infos = libs.get(tag);
			Config.setLibraryInfo(tag, LibInfo.toString(infos));
		}finally {
			unzipLock.unlock();
		}

	}


	/**
	 * 动态注册插件中定义的BroadcastReceiver
	 * @param plugin
	 * @param pluginPackage
	 */
	private void registerReceivers(Plugin plugin){
		PluginPackage pluginPackage = plugin.getPluginPackage();
		if(pluginPackage.pkg.receivers != null){
			for(android.content.pm.PackageParser.Activity receiverInfo : pluginPackage.pkg.receivers){

				IntentFilter filter = new IntentFilter();
				if(receiverInfo.intents != null){
					for(ActivityIntentInfo info : receiverInfo.intents){
						Class<?> clz;
						try {
							clz = plugin.loadClass(receiverInfo.className);
							if(clz != null){
								BroadcastReceiver receiver = (BroadcastReceiver)clz.newInstance();
								application.registerReceiver(receiver, (IntentFilter)info);
							}
						} catch (Exception e) {
							Log.e(TAG,e);
						}
					}
				}
			}
		}
	}

	private Resources loadResources(Context context,String path){

		Object assetMag;

		Class<?> class_AssetManager;
		try {
			class_AssetManager = Class
					.forName("android.content.res.AssetManager");
			assetMag = class_AssetManager.newInstance();
			Method method_addAssetPath = class_AssetManager.getDeclaredMethod(
					"addAssetPath", String.class);
			method_addAssetPath.invoke(assetMag, path);
			Resources res = context.getResources();

			return new Resources((AssetManager) assetMag,res.getDisplayMetrics(), res.getConfiguration());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}
	private Resources loadResources(Context context, String path,
			boolean useWebView) throws Exception {
		Object assetMag;

		Class<?> class_AssetManager = Class
				.forName("android.content.res.AssetManager");
		assetMag = class_AssetManager.newInstance();
		Method method_addAssetPath = class_AssetManager.getDeclaredMethod(
				"addAssetPath", String.class);
		method_addAssetPath.invoke(assetMag, path);

		// 5.0系统单独处理WebView
//		if (useWebView && Build.VERSION.SDK_INT >= 21) {
//
//			try {
//				Class<?> cls = Class.forName("android.webkit.WebViewFactory");
//				Method getProviderMethod = cls.getDeclaredMethod("getProvider",
//						new Class[] {});
//				getProviderMethod.setAccessible(true);
//
//				getProviderMethod.invoke(cls);
//				PackageInfo pi = (PackageInfo) RefUtil.getFieldValue(cls,
//						"sPackageInfo");
//				// String webViewAssetPath = pi.applicationInfo.sourceDir;
//				// String packageName = pi.packageName;
//				// application.getPackageManager().getPackageInfo(packageName,
//				// 0);
//				// Context webViewContext =
//				// application.createPackageContext(packageName,
//				// Context.CONTEXT_INCLUDE_CODE |
//				// Context.CONTEXT_IGNORE_SECURITY);
//				method_addAssetPath.invoke(assetMag,
//						pi.applicationInfo.sourceDir);
//			} catch (Exception e) {
//				e.printStackTrace();
//			}
//		}

		Resources res = application.getResources();
		return new Resources((AssetManager) assetMag,res.getDisplayMetrics(), res.getConfiguration());
//		Constructor<?> constructor_Resources = Resources.class.getConstructor(
//				class_AssetManager, res.getDisplayMetrics().getClass(), res
//						.getConfiguration().getClass());
//		res = (Resources) constructor_Resources.newInstance(assetMag,
//				res.getDisplayMetrics(), res.getConfiguration());

//		return res;
	}

	public InputStream getAssetsInputStream(Context context, String assetsPath) {
		Class<?> clazz = null;
		try {
			clazz = Class
					.forName("com.qihoo.haosou.msearchpublic.util.AssetsUtil");
		} catch (Exception e) {
			Log.e(e);
		}
		InputStream inputStream = null;
		if (clazz != null) {
			try {
				Method method = clazz.getDeclaredMethod("open", String.class);
				inputStream = (InputStream) method.invoke(null, assetsPath);
			} catch (Exception e) {
				Log.e(e);
			}
		}
		if (inputStream == null) {
			try {
				inputStream = context.getAssets().open(assetsPath);
			} catch (IOException e) {
				Log.e(e);
			}
		}
		return inputStream;
	}

	public Map<String, Plugin> getPlugins() {
		return plugins;
	}

	// /**
	// * 以插件的方式加载一个目录下所有的.apk文件 tag为文件路径
	// *
	// * @param dir
	// * 插件扫描目录
	// */
	// public Plugin[] loadApks(String dir) {
	// File file = new File(dir);
	// List<Plugin> list = new ArrayList<Plugin>();
	// if (file.isDirectory()) {
	// String[] apks = file.list(new FilenameFilter() {
	// @Override
	// public boolean accept(File dir, String filename) {
	// // TODO Auto-generated method stub
	// return filename.endsWith(".apk");
	// }
	// });
	//
	// for (String path : apks) {
	// Plugin p = loadApkLater(path, dir + "/" + path, null);
	// if (p != null)
	// list.add(p);
	// }
	// }
	// return list.toArray(new Plugin[] {});
	// }

	class CreateApplicationThread extends Thread {

		private Class<?> appClass;
		private Context context;
		private Application application;
		private Object syncObj;
		private String packageName;

		public CreateApplicationThread(Class<?> appClass, Context context,String packageName,
				Object syncObj) {
			this.syncObj = syncObj;
			this.context = context;
			this.appClass = appClass;
		}

		public void run() {
			try {
				application = (Application) Instrumentation.newApplication(
						appClass, context);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				Log.e(TAG, e);
			} finally {
				synchronized (context) {
					syncObj.notifyAll();
				}
			}
		}

		public Application getApplication() {
			return application;
		}

	}


	/**
	 * 重新加载一个插件
	 * @param tag
	 */
	public Plugin reload(String tag){

		//如果未加载则正常加载
		if(!isLoaded(tag)){
			return load(tag);
		}

		//如果已经加载，则处理一些释放内存对象，检测运行状态(如果有需要的话)等等操作再加载
//		System.load(pathName);

		return load(tag);

	}

	private boolean isMainThread() {
		return mainHandler.getLooper().getThread().getId() == Thread
				.currentThread().getId();
	}

	/**
	 * 该方法必须在主线程中执行，否则可能出问题
	 */
	private Application createApplication(Context baseContext,
			Class<? extends Application> appClass,PackageInfo pi,Plugin p) {
		Log.d(TAG,"createApplication(),appClass="+ appClass + ",pi="+pi+",tag="+p.getTag());
		Log.d(TAG,"createApplication(),path="+p.getPath() + ",loadedApk=" + p.getLoadedApk());
		try {
//			Application application = (Application) Instrumentation
//					.newApplication(appClass, baseContext);

			ApplicationInfo ai = pi.applicationInfo;//application.getPackageManager().getApplicationInfo(application.getPackageName(),PackageManager.GET_SHARED_LIBRARY_FILES);

			CompatibilityInfo compatInfo = null;
			try{
				compatInfo = (CompatibilityInfo)RefUtil.getFieldValue(p.getRes(), Resources.class, "mCompatibilityInfo");
			}catch(Exception e){
				Log.e(TAG, e);
			}

            Log.d(TAG,"createApplication(),activityThread="+ activityThread);
            Log.d(TAG,"createApplication(),HostGlobal.getPackageName()="+ HostGlobal.getPackageName());
			Log.d(TAG,"createApplication(),compatInfo="+ compatInfo);
			Log.d(TAG,"createApplication(),pi="+ pi);

			LoadedApk hostLoadedApk = activityThread.getPackageInfo(HostGlobal.getPackageName(), compatInfo, Context.CONTEXT_INCLUDE_CODE
					| Context.CONTEXT_IGNORE_SECURITY);
			LoadedApk loadedApk = activityThread.getPackageInfo(ai, compatInfo, Context.CONTEXT_INCLUDE_CODE
			| Context.CONTEXT_IGNORE_SECURITY);
//			LoadedApk loadedApk = ActivityThread.currentActivityThread().getPackageInfoNoCheck(ai, compatInfo);

			//拷贝宿主的应用信息到插件应用信息中
//			RefUtil.cloneObject(hostLoadedApk, loadedApk,LoadedApk.class);

			Log.d(TAG,"createApplication(),hostLoadedApk=" + hostLoadedApk);
			Log.d(TAG,"createApplication(),loadedApk=" + loadedApk);

			//保持与宿主一致的容器
			RefUtil.copyField(LoadedApk.class, "mReceivers",hostLoadedApk , loadedApk);
			RefUtil.copyField(LoadedApk.class, "mUnregisteredReceivers",hostLoadedApk , loadedApk);
			RefUtil.copyField(LoadedApk.class, "mServices",hostLoadedApk , loadedApk);
			RefUtil.copyField(LoadedApk.class, "mUnboundServices",hostLoadedApk , loadedApk);

			//填充插件相关信息
			RefUtil.setFieldValue(loadedApk, "mApplication", null);
			RefUtil.setFieldValue(loadedApk, "mClassLoader", p.getCl());
			RefUtil.setFieldValue(loadedApk, "mResources", p.getRes());

//			make的时候用宿主的包名初始化BaseContext，避免有些功能用不了
//			很明显的，不这么做，onCreate()中toast弹不出来。
//			这样做，对插件可能会有些信息方面的影响
//
//			++用宿主的包名在makeApplication会调用插件Application里面的onCreate()或者其他方法时，插件代码可能出现问题，比如获取包名。



			//初始化时用宿主包名
			//ContextImpl构造函数中
			// mContentResolver = new ApplicationContentResolver(this, mainThread, user);代码初始化会保存包名，需要宿主包名才能正常通信
			//InstrumentationHacker.newApplication()中会将包名置为插件的。
			RefUtil.setFieldValue(loadedApk, "mPackageName", HostGlobal.getPackageName());

//			RefUtil.setFieldValue(loadedApk, "mPackageName", ai.packageName);


//			RefUtil.setDeclaredFieldValue(baseContext, "mPackageInfo", loadedApk);

			ThreadContextData.putData(0,p.getTag());
			ThreadContextData.putData(1,loadedApk);
			Application application  = loadedApk.makeApplication(false, instrumentation);
//			application.setTheme(pi.applicationInfo.theme);
//			RefUtil.setFieldValue(loadedApk, "mPackageName", ai.packageName);

			//替换为宿主的定位服务
			Object locationManager = HostGlobal.getBaseApplication().getSystemService(Context.LOCATION_SERVICE);
//			LocationManager l1 = (LocationManager) application.getSystemService(Context.LOCATION_SERVICE);
//			System.out.println(l1);
//			Thread.sleep(5000);
			ILocationManagerHacker.replace(application.getBaseContext(),locationManager);
			application.getSystemService(Context.LOCATION_SERVICE);
//			LocationManager l2 = (LocationManager) application.getSystemService(Context.LOCATION_SERVICE);
//			System.out.println(l2);

			HostApplicationProxy.fixServiceCache(HostGlobal.getBaseApplication().getBaseContext(), application.getBaseContext());

			//--------------------处理外部存储器目录---------------------------------
			PluginUtil.handleExternalDirs(application.getBaseContext());

//			Context hostContextImpl = HostGlobal.getBaseApplication().getBaseContext();
//
//			//确认外部缓存已经挂载
//			File hostCacheDir = hostContextImpl.getExternalCacheDir();
//
//			File[] hostmExternalFilesDirs = (File[]) RefUtil.getFieldValue(hostContextImpl, "mExternalCacheDirs");
//			if(hostmExternalFilesDirs != null){
//				//处理API 19以上的情况
//				RefUtil.setFieldValue(application.getBaseContext(), "mExternalCacheDirs", hostmExternalFilesDirs);

//				//创建插件的外部缓存目录
//				for(File file : hostmExternalFilesDirs){
//					String path = file.getAbsolutePath().replace(HostGlobal.getPackageName(), ai.packageName);
//					File dir = new File(path);
//					if(!dir.mkdirs()){
//						IBinder binder = ServiceManager.getService("mount");
//						RefUtil.setFieldValue(obj, fieldName, value)
//						binder.queryLocalInterface(descriptor)
//						IMountService mount = IMountService.Stub.asInterface("");
//						try {
//							//需要用宿主的包名创建目录
//							RefUtil.callDeclaredMethod(mount, "mkdirs", new Class[]{String.class,String.class}, new Object[]{
//									HostGlobal.getPackageName(), dir.getAbsolutePath()
//							});
//
//						} catch (Exception ignored) {
//						}
//					}
//				}

//			}
//			else{
//				String pluginCacheDir = hostCacheDir.getAbsolutePath().replace(HostGlobal.getPackageName(), ai.packageName);
//				new File(pluginCacheDir).mkdirs();
//			}



			p.setLoadedApk(loadedApk);

			// 设置application中对应的application引用，解决在某些组件中需要调用getApplication()，而getApplication()为null的问题
//			Object packageInfo = RefUtil.getFieldValue(baseContext, "mPackageInfo");
//
//			if (packageInfo != null)
//				RefUtil.setFieldValue(packageInfo, "mApplication", application);
			return application;
		} catch (Exception e) {
			// TODO Auto-generated catch block
			Log.e(TAG,"createApplication(),Exception...", e);
			Log.e(TAG, e);
		}
		return null;
	}

	/**
	 * hook插件上下文环境
	 */
	public static void hookContext(Context context,Plugin p){

		Log.d(TAG,"hookContext(),p=" + p);
		Log.d(TAG,"hookContext(),context=" + context);

		//hook ContentResolver
//		RefUtil.setFieldValue(context, "mContentResolver", getContentResolverWrapper());

//		if(tag != null){
			//hook PackageManager
//			PackageManager pm = PackageManagerHacker.getPluginPackageManager(tag, application.getPackageManager());
//			RefUtil.setFieldValue(context, "mPackageManager", pm);

//			Plugin p = getPlugin(tag);
			if(p != null) {

				Log.d(TAG, "hookContext(),tag=" + p.getTag());
				Log.d(TAG, "hookContext(),p.getLoadedApk()=" + p.getLoadedApk());

				//注入LoadedApk
				RefUtil.setFieldValue(context, "mPackageInfo", p.getLoadedApk());

				//注入ClassLoader
				RefUtil.setFieldValue(context, "mClassLoader", p.getCl());

				//注入application
				RefUtil.setFieldValue(context, "mApplication", p.getApplication());

				//注入资源
				RefUtil.setFieldValue(context, "mResources", p.getRes());

				RefUtil.setFieldValue(context, "mOpPackageName", HostGlobal.getPackageName());

				RefUtil.setFieldValue(context, "mBasePackageName", HostGlobal.getPackageName());

			}
//		}

		//ContextImpl
		Object mBase = RefUtil.getFieldValue(context, "mBase");
		if(mBase != null){
			hookContext((Context)mBase,p);
		}

	}


	public class CreateApplicationTask implements Runnable{

		private Application application;
		private Context context;
		private Class<? extends Application> appClass;
		private Object sync;
		private PackageInfo pi;
		private Plugin plugin;
		private Object threadSync;
		private boolean finish = false;

		public CreateApplicationTask(Context context,Class<? extends Application> appClass,PackageInfo pi,Plugin plugin){
			this.context = context;
			this.appClass = appClass;
			this.sync = new Object();
			this.pi = pi;
			this.plugin = plugin;
			this.threadSync = new Object();
		}

		public Application tryGetApplication() {
			synchronized (sync) {
				try {
//					//进入等待后，就可以让CreateApplicationTask()继续运行了
//					synchronized (threadSync) {
//						threadSync.notifyAll();
//					}
					Log.d(TAG, "tryGetApplication(),sync.wait(),finish="+finish);
					if(!finish) {
						sync.wait();
					}
					Log.d(TAG, "tryGetApplication(),sync wakeup");
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					Log.e(TAG, e);
				}
				return application;
			}
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub

			Log.d(TAG, "CreateApplicationTask.run(),begin");
			//等待外部线程进入tryGetApplication()
//			synchronized (threadSync) {
//				try {
//					Log.d(TAG, "CreateApplicationTask.run(),threadSync.wait()");
//					threadSync.wait();
//					Log.d(TAG, "CreateApplicationTask.run(),threadSync wakeup");
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					Log.e(TAG, e);
//				}
//			}

			synchronized (sync) {
				try{
					Log.d(TAG, "CreateApplicationTask.run(),createApplication() begin");
					application = createApplication(context, appClass,pi,plugin);
					Log.d(TAG, "CreateApplicationTask.run(),createApplication() end");
				}catch(Throwable thr){
					Log.e(TAG, thr);
				}
				finish = true;
				sync.notifyAll();
			}
		}


	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	private void createApplicationContext(final PackageInfo pi, final Plugin p)
			throws NameNotFoundException, ClassNotFoundException,
			InstantiationException, IllegalAccessException {


//		final Context baseContext = application.createPackageContext(
//				application.getPackageName(), Context.CONTEXT_INCLUDE_CODE
//						| Context.CONTEXT_IGNORE_SECURITY);
//		beginUsePluginContext(p, baseContext);

		Context baseContext = null;

		String appClassName = Application.class.getName();
		if (pi.applicationInfo.className != null)
			appClassName = pi.applicationInfo.className;

		final Class<? extends Application> appClass = (Class<? extends Application>) p
				.getCl().loadClass(appClassName);


		Application application = null;

//		if (isMainThread()) {
			Log.i(TAG, "createApplicationContext::isMainThread()=true");
			application = createApplication(baseContext, appClass,pi,p);
			Log.i(TAG, "createApplicationContext::application="+application);

//		} else {
//
//			Log.i(TAG, "createApplicationContext::isMainThread()=false");
//			Log.i(TAG, "createApplicationContext::mainHandler="+mainHandler);
//			//在某些机型上application必须在主线程创建
//			CreateApplicationTask task = new CreateApplicationTask(baseContext, appClass,pi,p);
//			mainHandler.post(task);
//			Log.i(TAG, "createApplicationContext::wiat for CreateApplicationTask...");
//
//			//tryGetApplication()必须先执行
//			application = task.tryGetApplication();
//			Log.i(TAG, "createApplicationContext::application="+application);
//		}

		if(application != null){

			//在这里hook，可能对于某些代码来说，有点太晚
//			hookContext(application, p);

			p.setApplication(application);

			instrumentation.addContextInfo(application, new PluginContextInfo(application, p, defaultProxyActivity.getName(),null, null));
			instrumentation.addContextInfo(application.getApplicationContext(), new PluginContextInfo(application.getApplicationContext(), p, defaultProxyActivity.getName(),null, null));
			instrumentation.addContextInfo(application.getBaseContext(), new PluginContextInfo(application.getBaseContext(), p, defaultProxyActivity.getName(), null,null));

			application.setTheme(pi.applicationInfo.theme);
		}


	}

	public void enableCrashHandler(PluginCarshHandler pluginCarshHandler){
		CrashHandlerDispatcher crashHandlerDispatcher = CrashHandlerDispatcher.getInstance();
		crashHandlerDispatcher.setCrashHandlers(crashHandlers);
		crashHandlerDispatcher.setPluginCarshHandler(pluginCarshHandler);
		crashHandlerDispatcher.setToApp();
		enableCrashHandler = true;
	}

//	private UncaughtExceptionHandler getUncaughtExceptionHandler(){
//		Thread mainThread = Looper.getMainLooper().getThread();
//		UncaughtExceptionHandler handler = mainThread.getUncaughtExceptionHandler();
//		if(handler == null){
//			handler = Thread.getDefaultUncaughtExceptionHandler();
//		}
//		return handler;
//	}

	private UncaughtExceptionHandler setUncaughtExceptionHandler(){
		Thread mainThread = Looper.getMainLooper().getThread();
		UncaughtExceptionHandler handler = mainThread.getUncaughtExceptionHandler();
		if(handler == null){
			handler = Thread.getDefaultUncaughtExceptionHandler();
		}
		return handler;
	}

	/**
	 * 包装Intent，目前主要是包装处理序列化对象
	 *
	 * @param tag
	 * @param intent
	 */
	public static void wrapIntent(String tag, Intent intent) {
		Bundle bundle = null;
		try {
			bundle = intent.getExtras();
		} catch (Exception e) {
			Log.e(e);
		}

		if (bundle != null) {
			Iterator<String> keyIter = bundle.keySet().iterator();

			Map<String, Serializable> map = new HashMap<String, Serializable>();

			while (keyIter.hasNext()) {
				String key = keyIter.next();
				Object value = bundle.get(key);
				if (value != null && isSerialzableObject(value.getClass())) {
					SerializableWrapper wrapper = new SerializableWrapper();
					wrapper.obj = IO.serialize((Serializable) value);
					wrapper.tag = tag;
					map.put(key, (Serializable) wrapper);
				}
			}

			keyIter = map.keySet().iterator();
			while (keyIter.hasNext()) {
				String key = keyIter.next();
				Serializable value = map.get(key);
				intent.putExtra(key, value);
			}
		}
	}

	public static void unwrapIntent(Plugin plugin, Intent intent) {

		Bundle bundle = null;
		try {
			bundle = intent.getExtras();
		} catch (Exception e) {
			Log.e(e);
		}

		if (bundle != null) {

			Iterator<String> keyIter = bundle.keySet().iterator();
			Map<String, Serializable> map = new HashMap<String, Serializable>();

			while (keyIter.hasNext()) {
				String key = keyIter.next();
				Object value = bundle.get(key);

				if (value != null
						&& value.getClass().equals(SerializableWrapper.class)) {
					SerializableWrapper wrapper = (SerializableWrapper) value;
					Serializable obj = (Serializable) IO.unserialize(plugin,
							wrapper.obj);
					map.put(key, obj);
				}
			}

			keyIter = map.keySet().iterator();
			while (keyIter.hasNext()) {
				String key = keyIter.next();
				Serializable value = map.get(key);
				intent.putExtra(key, value);
			}
		}
	}

	public void resetProxyActivity(Context context,String tag,ActivityInfo ai,Intent intent,Class<?> proxyActivity){
		if(configFilter != null){
			String targetClassName = intent.getStringExtra(PluginManager.KEY_TARGET_CLASS_NAME);

			Class<?> clz = configFilter.getProxyActivity(tag,ai,intent,targetClassName,proxyActivity);

			if(clz != null){
				//从外部接口获取代理Activity
				intent.setClass(application, clz);

				//设置自定义代理标志，自定义的ProxyActivity，所有属性写死，与在宿主注册时一致，不会动态修改
				intent.putExtra(PluginManager.KEY_IS_CUSTOM_PROXY_ACTIVITY, true);
			}else{
				ActivityStatus act = proxyActivityPool.getIdleActivity(ai.launchMode);
				if(act != null){
					try {
						intent.setClass(application, Class.forName(act.className));
					} catch (ClassNotFoundException e) {
						e.printStackTrace();
						intent.setClass(application, defaultProxyActivity);
					}
				}else {
					intent.setClass(application, defaultProxyActivity);
				}
			}

		}
	}

	private Intent makeActivityIntent(String tag, String className,Intent oriIntent,Class<? extends BaseProxyActivity> proxyActivityClass) {
		// 如果已经经过插件化改造则直接返回
		if (oriIntent.hasExtra(PluginManager.KEY_IS_PLUGIN_INTENT))
			return oriIntent;

		Intent intent = new Intent();
		PluginManager.wrapIntent(tag, oriIntent);
		Log.i(TAG, "makeIntent::oriIntent=" + oriIntent);

//		intent.setClassName(application, className);

		// 插件化标记
		intent.putExtra(PluginManager.KEY_IS_PLUGIN_INTENT, true);

		intent.putExtra(PluginManager.KEY_TARGET_CLASS_NAME, className);
		intent.putExtra(PluginManager.KEY_PLUGIN_TAG, tag);
		intent.putExtra(PluginManager.KEY_ORIGIN_INTENT, oriIntent);
		intent.setClass(application, proxyActivityClass);


		return intent;

	}



	private void registerMonitor(Context context){
		IntentFilter filter = new IntentFilter();
		filter.addAction(Actions.ACTION_PLUGIN_PROCESS_READY);
		context.registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if(action.equals(Actions.ACTION_PLUGIN_PROCESS_READY)) {
					isReady = true;
				}
			}
		},filter);
	}

	private boolean isReady;

	public boolean isPluginProcessAlive(){
		String name = getPluginProcessName();
		ActivityManager mActivityManager = (ActivityManager) application
				.getSystemService(Context.ACTIVITY_SERVICE);
		for (ActivityManager.RunningAppProcessInfo appProcess : mActivityManager
				.getRunningAppProcesses()) {
			if (appProcess.processName.equals(name) ) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断插件是否已经安装完成，并且插件进程初始化完毕。
	 * @return
	 */
	public boolean isReady(){
		return isReady;
	}

	public Intent makeActivityIntent(Context context,String tag,Intent oriIntent){
		return makeActivityIntent(context, tag, oriIntent, this.defaultProxyActivity);
	}

	public Intent makeActivityIntent(Context context,String tag,Intent oriIntent,Class<? extends BaseProxyActivity> proxyActivity){

//		android.content.pm.PackageParser.Activity activity = null;


//		/**
//		 * 优先使用tag查找一次，自己的Activity优先级最高
//		 */
//		List<android.content.pm.PackageParser.Activity> activities = installManager.queryActivities(tag, oriIntent,1);
//
//		/**
//		 * 如果使用tag没有查到，则进行一次全局搜索
//		 */
//		if(tag != null && (activities == null || activities.size() == 0)){
//			activities = installManager.queryActivities(null, oriIntent,1);
//		}

		InstallManager.ComponentIntentResult result = null;
		android.content.pm.PackageParser.Activity activity = null;
		List<InstallManager.ComponentIntentResult> activities = installManager.queryActivities(tag, oriIntent,1);

		if(activities != null && activities.size() > 0){
			//只取一个做处理
			result = activities.get(0);
			activity = (android.content.pm.PackageParser.Activity)result.component;
		}

		Intent newIntent = null;

		if(activity == null){
			Log.i(TAG, "startActivity:: No matching activity in plugin_manager,intent="+oriIntent);
		}else{
			PluginPackage pluginPackage = installManager.queryPluginInfoByActivity(activity);
			tag = pluginPackage.tag;
			newIntent = makeActivityIntent(tag, activity.className,oriIntent,proxyActivity);

			// 处理ActivityInfo里的metaData为空的问题
 			activity.info.metaData = activity.metaData;

			resetProxyActivity(context, pluginPackage.tag, activity.info, newIntent, proxyActivity);
			Log.i(TAG, "makeIntent:: className="+activity.className);
		}
		return newIntent;
	}

//	public Intent makeActivityIntent(Intent intent,String tag, String className) {
//
//		return makeActivityIntent(tag,className,intent,(Class<? extends BaseProxyActivity>)defaultProxyActivity);
//
//	}


	public boolean startActivityForResult(Context context, String tag,
			Intent intent, int requestCode,
			Class<? extends ProxyActivity> proxyActivity) {
		return startActivity(context, tag, intent, true, requestCode, proxyActivity);
	}

	public boolean startActivityForResult(Context context, String tag,
			Intent intent, int requestCode) {
		return this
				.startActivity(context, tag, intent, true, requestCode, defaultProxyActivity);
	}

	public boolean startActivity(Context context, String tag, Intent intent) {
		return this.startActivity(context, tag, intent, false, 0, defaultProxyActivity);
	}


	public boolean startMainActivity(Context context, String tag) {
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_LAUNCHER);
		return this.startActivity(context, tag, intent, false, 0, defaultProxyActivity);
	}

	public boolean startActivity(Context context, String tag, Intent intent,
			Class<? extends ProxyActivity> proxyActivity) {
		return startActivity(context, tag, intent, false, 0, proxyActivity);
	}

	// 自动匹配的方式
	public boolean startActivity(Context context, Intent intent,
			Class<? extends ProxyActivity> proxyActivity) {
		return this.startActivity(context, null, intent, false, -1, proxyActivity);
	}

	// 自动匹配的方式
	public boolean startActivity(Context context, Intent intent) {
		return this.startActivity(context, null, intent, false, -1, defaultProxyActivity);
	}

	// 自动匹配的方式
	public boolean startActivityForResult(Context context, Intent intent,
			int requestCode) {
		return this.startActivity(context, null, intent, true, requestCode, defaultProxyActivity);
	}

	// 自动匹配的方式
	public boolean startActivityForResult(Context context, Intent intent,
			int requestCode, Class<? extends ProxyActivity> proxyActivity) {
		return this.startActivity(context, null, intent, true, requestCode, proxyActivity);
	}


	/**
	 * 适合内部调用
	 *
	 * @param tag
	 * @param context
	 * @param intent
	 * @return
	 */
	public boolean startActivity(Context context, String tag, Intent intent,
			boolean forResult, int requestCode,
			Class<? extends BaseProxyActivity> proxyActivity) {

		Log.i(TAG, "startActivity::arguments tag=" + tag + ",context="
				+ context + ",intent=" + intent);

		if (context == null || intent == null) {
			Log.e(TAG, "startActivity::arguments error");
			return false;
		}

//		if(TextUtils.isEmpty(tag)){
////			activity = installManager.queryActivityByClassName(tag, className)
////			activityData = GlobalDataManager.queryActivityByIntent(intent);
//			if(activityData != null){
//				tag = activityData.tag;
//			}else{
//				Log.e(TAG,
//						"startActivity:: The activity is not found, make sure the plugin is installed. intent="
//								+ intent);
//				return false;
//			}
//		}

		Plugin plugin = null;

		if(!TextUtils.isEmpty(tag)){

			plugin = getPlugin(tag);

			// 先看内存中是否已经加载，如果未加载再判断是否已经安装
			if (plugin == null) {
				PluginInfo info = getInstalledPluginInfo(tag);
				if (info == null) {
					Log.e(TAG,
							"startActivity:: Plugin is not installed,please call to install () for installation. tag="
									+ tag);
					return false;
				}
			}
		}
		Log.i(TAG, "startActivity::proxyActivity=" + proxyActivity);

		Intent newIntent = makeActivityIntent(context, tag, intent, proxyActivity);

		if(newIntent == null)
			return false;

		if (forResult) {
			if (context instanceof Activity) {
				((Activity) context).startActivityForResult(newIntent,
						requestCode);
			} else {
				Log.e(TAG,
						"startActivity::startActivityForResult fail,context="
								+ context);
			}
		} else {
			context.startActivity(newIntent);
		}

		return true;

	}

	public Service queryService(String tag,Intent oriIntent){

    	List<Service> services = null;

    	/**
    	 * 如果插件tag不为空，则优先使用tag查询一次，如果查询不到后面还会全局搜索一次，如果tag为空，则只进行全局搜索
    	 * 这样做的目的是，在有多个匹配项的情况下，优先使用插件自身的组件，避免被其他插件影响
    	 */
    	if(!TextUtils.isEmpty(tag)){
    		services = installManager.queryServices(tag, oriIntent);
    	}

		if(services == null || services.size() == 0){
			services = installManager.queryServices(null, oriIntent);
		}

		if(services != null && services.size() > 0)
			return services.get(0);

		return null;
	}

	private Intent makeServiceIntent(String tag,Service service,Intent oriIntent){
		if(oriIntent != null && !oriIntent.getBooleanExtra(KEY_IS_PLUGIN_INTENT, false)){
			Intent intent = new Intent();
			intent.setClass(application, WrapService.class);
			intent.putExtra(PluginManager.KEY_IS_PLUGIN_INTENT, true);
			intent.putExtra(PluginManager.KEY_ORIGIN_INTENT, oriIntent);
			intent.putExtra(PluginManager.KEY_PLUGIN_TAG, tag);
			intent.putExtra(PluginManager.KEY_TARGET_CLASS_NAME, service.className);
			return intent;
		}
		return oriIntent;
	}

	   /**
	    * 指定tag可以优先使用插件自身的匹配类，否则全局查找第一个匹配的类
	    * @param context
	    * @param tag
	    * @param oriIntent
	    * @return
	    */
	    public ComponentName startService(Context context, String tag,Intent oriIntent) {
			Service service = queryService(tag,oriIntent);
	        return startService(service,oriIntent);
	    }


	    /**
	     * 指定tag可以优先使用插件自身的匹配类，否则全局查找第一个匹配的类
	     * @param context
	     * @param tag
	     * @param oriIntent
	     * @return
	     */
	   public ComponentName startService(Service service,Intent oriIntent) {

	 		if(service != null){
	 			PluginPackage pluginPackage = installManager.queryPluginInfoByService(service);
	 			Intent intent = makeServiceIntent(pluginPackage.tag,service, oriIntent);
	 			application.startService(intent);
	 			return new ComponentName(pluginPackage.pi.packageName, service.className);
	 		}

	         return null;
	     }


    private static Map<ServiceConnection,ServiceConnectionWrapper> serviceConnectionMapping = new HashMap<ServiceConnection, ServiceConnectionWrapper>();

    public static class ServiceConnectionWrapper implements ServiceConnection{

    	private ServiceConnection conn;
    	private boolean connected;
    	private Intent intent;
    	private String tag;
    	private ComponentName componentName;
    	private Context context;
    	private boolean doUnbind;
    	private PluginManager pluginManager;

    	public ServiceConnectionWrapper(Context context,String tag,Intent intent,ComponentName componentName,ServiceConnection conn){
    		this.tag = tag;
    		this.intent = intent;
    		this.conn = conn;
    		this.componentName = componentName;
    		this.context = context;
    		this.pluginManager = PluginManager.getInstance();
    	}

    	public void setDoUnbind(boolean doUnbind) {
			this.doUnbind = doUnbind;
		}

    	public boolean isDoUnbind() {
			return doUnbind;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// TODO Auto-generated method stub
    		this.connected = true;
			Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,name=" + name + ",service="+service);
    		IAidlDispatcher aidlDispatcher = IAidlDispatcher.Stub.asInterface(service);
			Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,aidlDispatcher="+aidlDispatcher);
			Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,tag="+tag);
			Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,intent="+intent);
    		try {
    			//连接上后，先尝试绑定插件服务
				Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,conn="+conn);
				Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,this="+this);
				Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,componentName="+componentName);
				Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,aidlDispatcher.bindService begin");
				aidlDispatcher.bindService(tag, componentName.getClassName(), intent);
				Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,aidlDispatcher.bindService end");
				serviceConnectionMapping.put(conn, this);
				Log.d(TAG, "ServiceConnectionWrapper, onServiceConnected ,serviceConnectionMapping="+serviceConnectionMapping);
				pluginManager.addServiceToPackageInfo(context,conn,this);
				conn.onServiceConnected(componentName, service);

			} catch (RemoteException e) {
				// TODO Auto-generated catch block
				Log.e(TAG,"ServiceConnectionWrapper::onServiceConnected(),"+e);
				e.printStackTrace();
			}
		}

		public Context getContext() {
			return context;
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			// TODO Auto-generated method stub
    		this.connected = false;
    		this.pluginManager.removeServiceFromPackageInfo(context, conn);
			conn.onServiceDisconnected(componentName);
		}

		public boolean isConnected() {
			return connected;
		}

		public Intent getIntent() {
			return intent;
		}

		public String getTag() {
			return tag;
		}

		public ComponentName getComponentName() {
			return componentName;
		}

		public ServiceConnection getServiceConnection(){
			return conn;
		}

    }

    /**
     * 指定tag可以优先使用插件自身的匹配类，否则全局查找第一个匹配的类
     * @param tag
     * @param context
     * @param oriIntent
     * @param conn
     * @param flags
     * @return
     */
    public boolean bindService(Context context, String tag,Intent oriIntent, ServiceConnection conn, int flags) {


    	Service service = queryService(tag,oriIntent);

		return bindService(context, service, oriIntent, conn, flags);

    }


    private Context getContextImpl(Context context){
    	if(context.getClass().getName().equals("android.app.ContextImpl"))
    		return context;
    	return (Context)RefUtil.getFieldValue(context, "mBase");
    }

    public boolean bindService(Context context,Service service,Intent oriIntent, ServiceConnection conn, int flags) {

		Log.d(TAG,"bindService::conn="+conn+",context="+context+",service="+service+",oriIntent="+oriIntent+",flags="+flags);
		if(service != null){
			PluginPackage pluginPackage = installManager.queryPluginInfoByService(service);
			Log.d(TAG,"bindService::pluginPackage="+pluginPackage);
			Intent intent = makeServiceIntent(pluginPackage.tag,service, oriIntent);
			Log.d(TAG,"bindService::makeServiceIntent(), intent="+intent);
			Log.d(TAG,"bindService::makeServiceIntent(), serviceConnectionMapping="+serviceConnectionMapping);
			if(!serviceConnectionMapping.containsKey(conn)){
				ComponentName componentName = new ComponentName(pluginPackage.pi.packageName,intent.getStringExtra(KEY_TARGET_CLASS_NAME));
				Log.d(TAG,"bindService::makeServiceIntent(), componentName="+componentName);
				ServiceConnectionWrapper wrapper = new ServiceConnectionWrapper(context,pluginPackage.tag,intent, componentName,conn);
				Log.d(TAG,"bindService::makeServiceIntent(), wrapper="+wrapper);
//				intent.putExtra(WrapService.SERVICE_COMMAND, WrapService.BIND_SERVICE);
//				context.startService(intent);
				return context.bindService(intent, wrapper, flags);

			}
		}

        return false;
    }

    void addServiceToPackageInfo(Context context,ServiceConnection conn,ServiceConnectionWrapper wrapper){

		Log.d(TAG, "addServiceToPackageInfo, conn="+conn+",wrapper="+wrapper);
    	try{
			LoadedApk mPackageInfo = (LoadedApk)RefUtil.getFieldValue(getContextImpl(context), "mPackageInfo");
			Log.d(TAG, "addServiceToPackageInfo, mPackageInfo="+mPackageInfo);

			Map<ServiceConnection, Map<ServiceConnection, Object>> mServices = (Map<ServiceConnection, Map<ServiceConnection, Object>>)RefUtil.getFieldValue(mPackageInfo, "mServices");

			Log.d(TAG, "addServiceToPackageInfo, mServices="+mServices);
			Map<ServiceConnection, Object> map = mServices.get(context);

			Log.d(TAG, "addServiceToPackageInfo, map="+map);

			if(map == null){
				Iterator<Map<ServiceConnection, Object>> iter = mServices.values().iterator();
				while(iter.hasNext()){
					Map<ServiceConnection, Object> tmpMap = iter.next();
					if(tmpMap.containsKey(wrapper)){
						map = tmpMap;
						break;
					}
				}
			}

			Object sd =  map.get(wrapper);

			map.put(conn,sd);
    	}catch(Exception e){
    		Log.e(TAG, e);
    	}
    }

    void removeServiceFromPackageInfo(Context context,ServiceConnection conn){

    	try{
			LoadedApk mPackageInfo = (LoadedApk)RefUtil.getFieldValue(getContextImpl(context), "mPackageInfo");

			Map<ServiceConnection, Map<ServiceConnection, Object>> mServices = (Map<ServiceConnection, Map<ServiceConnection, Object>>)RefUtil.getFieldValue(mPackageInfo, "mServices");
			Map<ServiceConnection, Object> map = mServices.get(context);
			map.remove(conn);
    	}catch(Exception e){
    		Log.e(TAG, e);
    	}

    }

	//计算服务的绑定数
    private int getServiceBindCount(String tag,String className){

		int bindCount = 0;

		for(ServiceConnectionWrapper sc:serviceConnectionMapping.values()){
			if(TextUtils.equals(tag, sc.getTag())
				&& sc.getComponentName().getClassName().equals(className)){
					bindCount++;
				}
		}
		return bindCount;
    }

    public boolean tryUnbindService(ServiceConnectionWrapper wrapper){
    	Context context = wrapper.getContext();
    	ServiceConnection conn = wrapper.getServiceConnection();
    	Intent originIntent = (Intent)wrapper.getIntent().getParcelableExtra(KEY_ORIGIN_INTENT);
    	Service service = queryService(wrapper.tag,originIntent);
    	if(service != null){
			PluginPackage pluginPackage = installManager.queryPluginInfoByService(service);
			Intent intent = makeServiceIntent(pluginPackage.tag,service, originIntent);
			if(intent != null){

				serviceConnectionMapping.remove(conn);

				//计算服务的绑定数
				int bindCount = getServiceBindCount(pluginPackage.tag,service.className);
				//只有当绑定数为0时，才真的让服务端解绑服务
				if(bindCount==0){
					intent.putExtra(WrapService.SERVICE_COMMAND, WrapService.UNBIND_SERVICE);
					context.startService(intent);
				}
				return true;
			}
		}
    	return false;
    }

    public void unbindService(final Context context,String tag,ServiceConnection conn){
        Log.i(TAG, "unbindService()::tag="+tag+",conn="+conn);
    	if(conn != null){
    		if(!serviceConnectionMapping.containsKey(conn))
    			return;
			ServiceConnectionWrapper wrapper = serviceConnectionMapping.get(conn);
			if(TextUtils.equals(wrapper.getTag(),tag)){
				if(tryUnbindService(wrapper)){
					wrapper.setDoUnbind(true);
			    	context.unbindService(wrapper);
				}
			}
    	}
    }

    public boolean stopService(Context context, String tag,Intent oriIntent,int startId) {

    	Service service = queryService(tag,oriIntent);

		return stopService(context,service,oriIntent,startId);
    }

    public boolean stopService(Context context,Service service,Intent oriIntent,int startId) {
		if(service != null){
			PluginPackage pluginPackage = installManager.queryPluginInfoByService(service);
//			int bindCount = getServiceBindCount(pluginPackage.tag,service.className);
//			//绑定的数为0时，停止服务
//			if(bindCount==0){
				Intent intent = makeServiceIntent(pluginPackage.tag,service, oriIntent);
				intent.putExtra(WrapService.SERVICE_COMMAND, WrapService.STOP_SERVICE);
				intent.putExtra(WrapService.SERVICE_START_ID, startId);
				context.startService(intent);
//			}
		}
		return true;
    }

//	public class ContextInfo {
//		Context context;
//		Context baseContext;
//		Resources baseContext_res;
//		ClassLoader baseContext_cl;
//		Resources context_res;
//		ClassLoader context_cl;
//		ActivityInfo context_ai;
//	}
//
//	private static class PackageManagerHandler implements InvocationHandler {
//		private final Object origin;
//		private final PackageInfo pkg;
//		private final String packageName;
//		private final Map<String, ActivityInfo> mapActivityInfo = new HashMap<String, ActivityInfo>();
//
//		public PackageManagerHandler(Object origin, PackageInfo pkg,
//				String packageName) {
//			this.origin = origin;
//			this.pkg = pkg;
//			this.packageName = packageName;
//		}
//
//		@Override
//		public Object invoke(Object proxy, Method method, Object[] args)
//				throws Throwable {
//			if (method.getName().equals("getActivityInfo")) {
//				String cName = ((ComponentName) args[0]).getClassName();
//				ActivityInfo[] activities = pkg.activities;
//				for (ActivityInfo aInfo : activities) {
//					if (cName.equals(aInfo.name)) {
//						ActivityInfo info = new ActivityInfo(aInfo);
//						info.applicationInfo = new ApplicationInfo(
//								aInfo.applicationInfo);
//						info.applicationInfo.packageName = packageName;
//						info.applicationInfo.uid = Process.myUid();
//						mapActivityInfo.put(cName, info);
//						return info;
//					}
//				}
//			}
//			Object result = null;
//			try {
//				result = method.invoke(origin, args);
//			} catch (InvocationTargetException e) {
//				throw e.getTargetException();
//			}
//
//			if (method.getName().equals("getPackageInfo")
//					&& args[0].equals(packageName)) {
//				if (BuildConfig.DEBUG) {
//					Log.d("PackageManagerHandler", "getPackageInfo");
//				}
//				PackageInfo pInfo = (PackageInfo) result;
//				if (pInfo != null) {
//					pInfo.versionCode = pkg.versionCode;
//					pInfo.versionName = pkg.versionName;
//				}
//			}
//			result = inceptForCoolPad(method, args, result);
//			return result;
//		}
//
//		@SuppressWarnings("unchecked")
//		private Object inceptForCoolPad(Method method, Object[] args,
//				Object result) {
//			try {
//				if (method.getName().equals("queryIntentActivities")) {
//					List<ResolveInfo> list = (List<ResolveInfo>) result;
//					if (list != null && list.size() == 0) {
//						Intent intent = (Intent) args[0];
//						String cName = intent.getComponent().getClassName();
//						ActivityInfo aInfo = mapActivityInfo.get(cName);
//						if (aInfo != null) {
//							ResolveInfo ri = new ResolveInfo();
//							ri.activityInfo = aInfo;
//							list.add(ri);
//						}
//					}
//				}
//			} catch (Exception e) {
//			}
//			return result;
//		}
//	}
//
//	public final static int CONTEXT_TYPE_BASE = 0;
//	public final static int CONTEXT_TYPE_MASTER = 1;
//
//	private void injectPackageInfo(Context context, Object packageInfo,
//			Plugin plugin) {
//
//		try {
//			RefUtil.setFieldValue(packageInfo, "mClassLoader", plugin.getCl());
//			RefUtil.setFieldValue(packageInfo, "mResources", plugin.getRes());
//			RefUtil.setFieldValue(packageInfo, "mResDir", plugin.getPath());
//			Object mActivityThread = RefUtil.getFieldValue(packageInfo,
//					"mActivityThread");
//
//			// 替换IPackageManager
//			// 替换后内置apk获得context的packname为内置应用的原有包名
//			Object origin = RefUtil.getFieldValue(mActivityThread,
//					"sPackageManager");
//			if (origin == null) { // 2.1的适配问题
//				Method getPackageManager = mActivityThread.getClass()
//						.getMethod("getPackageManager");
//				getPackageManager.invoke(null);
//				origin = RefUtil.getFieldValue(mActivityThread,
//						"sPackageManager");
//			}
//
//			PackageInfo pkg = context.getPackageManager()
//					.getPackageArchiveInfo(plugin.getPath(),
//							PackageManager.GET_ACTIVITIES);
//
//			Object newPM = Proxy.newProxyInstance(
//					getClass().getClassLoader(),
//					origin.getClass().getInterfaces(),
//					new PackageManagerHandler(origin, pkg, context
//							.getPackageName()));
//			RefUtil.setFieldValue(mActivityThread, "sPackageManager", newPM);
//			RefUtil.setFieldValue(context.getPackageManager(), "mPM", newPM);
//
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//	}
//
//	public void hookClassLoader(Context context) {
//		Object packageInfo = RefUtil.getFieldValue(context, "mPackageInfo");
//		RefUtil.setFieldValue(packageInfo, "mPackageInfo", new ClassLoader() {
//			@Override
//			public Class<?> loadClass(String className)
//					throws ClassNotFoundException {
//				// TODO Auto-generated method stub
//				return super.loadClass(className);
//			}
//
//			@Override
//			protected Class<?> loadClass(String className, boolean resolve)
//					throws ClassNotFoundException {
//				// TODO Auto-generated method stub
//				return super.loadClass(className, resolve);
//			}
//
//			@Override
//			protected Class<?> findClass(String className)
//					throws ClassNotFoundException {
//				// TODO Auto-generated method stub
//				return super.findClass(className);
//			}
//		});
//		// ci.context_cl = (ClassLoader)RefUtil.getFieldValue(packageInfo,
//		// "mClassLoader");
//
//	}

    public PluginInfo getInstalledPluginInfo(String tag){
        PluginPackage installedPlugin = installManager.getInstalledPlugin(tag);
        Log.i(TAG, "getInstalledPluginInfo()::installedPlugin="+installedPlugin);
    	if(installedPlugin != null){
            Log.i(TAG, "getInstalledPluginInfo()::installedPlugin.pi="+installedPlugin.pi);
    		return installedPlugin.pi;
    	}
    	return null;
    }

	public Plugin getPlugin(String tag) {
		return plugins.get(tag);
	}



	private class NetworkChangeHandleForUpdate implements INetworkChange{

		private boolean reloadXml;
		private String path;
		private boolean onlyWifi;
		private Class<? extends UpdateFilter> updateFilterClass;

		public NetworkChangeHandleForUpdate(boolean reloadXml,String path,boolean onlyWifi,Class<? extends UpdateFilter> updateFilterClass){
			this.reloadXml = reloadXml;
			this.path = path;
			this.onlyWifi = onlyWifi;
			this.updateFilterClass = updateFilterClass;
		}

		@Override
		public void onNetworkChanged(int type) {
			// TODO Auto-generated method stub
			if(ConnectivityManager.TYPE_WIFI == type){
				startUpdate(reloadXml, path, onlyWifi, updateFilterClass);
			}
		}

	}

	private NetworkChangeHandleForUpdate networkChangeHandleForUpdate;
	/**
	 * 启动一次 更新
	 * @param reloadXml 是否重新加载xml
	 * @param path xml路径
	 * @param onlyWifi 是否只在wifi下更新
	 * @param updateFilterClass 更新监听
	 */
	public void startUpdate(boolean reloadXml,String path,boolean onlyWifi,Class<? extends UpdateFilter> updateFilterClass){

		Intent intent = new Intent(Actions.ACTION_UPDATE_CHECK);
		intent.setClass(application, UpdateService.class);

		intent.putExtra(Actions.DATA_ONLY_WIFI, onlyWifi);
		if(reloadXml){
			intent.putExtra(Actions.DATA_RELOAD, reloadXml);
			if(!TextUtils.isEmpty(path)){
				intent.putExtra(Actions.DATA_FILE_PATH, path);
			}

		}

		if(updateFilterClass != null)
			intent.putExtra(Actions.DATA_CLASS_NAME, updateFilterClass.getName());

		ConnectivityManager cm = (ConnectivityManager) HostGlobal.getBaseApplication()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();

        //如果无网络，或者在非wifi网络下时，限定只有wifi下才更新插件，则直接注册监听，等网络连通再进行更新检测
        if (activeNetworkInfo == null || !activeNetworkInfo.isConnected()||
        		(onlyWifi && activeNetworkInfo.getType() != ConnectivityManager.TYPE_WIFI)
        		){
	    			if(networkChangeHandleForUpdate == null)
	    				networkChangeHandleForUpdate = new NetworkChangeHandleForUpdate(reloadXml,path,onlyWifi,updateFilterClass);
	    			NetworkManager.getInstance(application).addNetworkChangeListener(networkChangeHandleForUpdate);
    			return;
    	}
    	else{
			application.startService(intent);
		}
	}


	public void startUpdate(Class<? extends UpdateFilter> updateFilterClass){
		PluginManager.getInstance().startUpdate(true, updateFilterClass);
		startUpdate(true, null, true, updateFilterClass);
	}

	public void startUpdate(boolean onlyWifi,Class<? extends UpdateFilter> updateFilterClass){
		startUpdate(true, null, onlyWifi, updateFilterClass);
	}

	public void startUpdate(){
		startUpdate(true, null, true, null);
	}

	//解析xml获取插件对象
    private List<PluginInfo> parsePluginInfos(InputStream in) throws XmlPullParserException, IOException {

        List<PluginInfo> pluginInfos = null;
        PluginInfo pluginInfo = null;

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, "UTF-8");

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
            case XmlPullParser.START_DOCUMENT:
            	pluginInfos = new ArrayList<PluginInfo>();
                break;
            case XmlPullParser.START_TAG:
                if (parser.getName().equals("plugin")) {
                	pluginInfo = new PluginInfo();
					pluginInfo.loadOnAppStarted = 0;
                	pluginInfo.tag = parser.getAttributeValue(null, "tag");
                	pluginInfo.versionName = parser.getAttributeValue(null, "version");
                } else if (parser.getName().equals("packageName")) {
                    eventType = parser.next();
                    pluginInfo.packageName = parser.getText();
                } else if (parser.getName().equals("name")) {
                    eventType = parser.next();
                    pluginInfo.name = parser.getText();
                } else if (parser.getName().equals("tag")) {
                    eventType = parser.next();
                    pluginInfo.tag = (parser.getText());
                } else if (parser.getName().equals("desc")) {
                    eventType = parser.next();
                    pluginInfo.desc = (parser.getText());
                }  else if (parser.getName().equals("md5")) {
                    eventType = parser.next();
                    pluginInfo.md5 = (parser.getText());
                }  else if (parser.getName().equals("updateDesc")) {
                    eventType = parser.next();
                    pluginInfo.updateDesc = (parser.getText());
                }  else if (parser.getName().equals("path")) {
                    eventType = parser.next();
                    pluginInfo.path = (parser.getText());
                }  else if (parser.getName().equals("icon")) {
                    eventType = parser.next();
                    pluginInfo.icon = (parser.getText());
                }  else if (parser.getName().equals("url")) {
                    eventType = parser.next();
                    pluginInfo.url = (parser.getText());
                }   else if (parser.getName().equals("fileName")) {
					eventType = parser.next();
					pluginInfo.fileName = (parser.getText());
				}  else if (parser.getName().equals("loadOnAppStarted")) {
					eventType = parser.next();
					pluginInfo.loadOnAppStarted = Boolean.parseBoolean(parser.getText()) ? 1 : 0;
				}
                break;
            case XmlPullParser.END_TAG:
                if (parser.getName().equals("plugin")) {
                	pluginInfos.add(pluginInfo);
                    pluginInfo = null;
                }
                break;
            }
            eventType = parser.next();
        }
        return pluginInfos;
    }


	private boolean installPluginFromAssets(Context context,PluginInfo pi) {

		pi.path = (Config.getPluginDir() + "/" + pi.fileName);

		File targetFile = new File(pi.path);
		targetFile.getParentFile().mkdirs();

		InputStream inputStream = null;

		try {
			String assetsPath = Config.ASSETS_PLUGIN_DIR + File.separator + pi.fileName;
			inputStream = getAssetsInputStream(context, assetsPath);

			byte[] bytes = IO.readBytes(inputStream);

			IO.writeBytes(targetFile,bytes);
			String md5 = MD5Util.md5str(bytes);
			IO.writeString(targetFile.getAbsoluteFile() + ".md5",md5);

			pi.md5 = md5;

			return installManager.install(pi,false);

		} catch (Exception e) {
			Log.e(TAG, e );
			return false;
		}finally {
			if(inputStream != null)
				try {
					inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
		}
	}

	/**
	 * 加载所有的捆包插件
	 */
	public void loadDefaultPluginAll(){
		//获取所有捆包插件全部加载
		List<PluginInfo> pis = PluginManager.getInstance().getDefaultPlugins();
		for(PluginInfo pi : pis){
			PluginManager.getInstance().load(pi.tag);
		}
	}

	/**
	 * 加载设定在应用启动时加载的插件
	 */
	public void loadPluginOnAppStarted(){
		List<PluginInfo> pluginInfos = this.getInstallManager().queryInstalledPluginInfo();
		if(pluginInfos != null) {
			for (PluginInfo pi : pluginInfos) {
				if(pi.loadOnAppStarted == 1) {
					PluginManager.getInstance().load(pi.tag);
				}
			}
		}
	}


	/**
	 * 安装所有的捆包插件
	 */
	public void installDefaultPlugins() {
		installDefaultPlugins(false);
	}


	/**
	 * 获取所有的捆包插件信息
	 * @return
	 */
	public List<PluginInfo> getDefaultPlugins(){

		if(defaultPluginList == null){
			InputStream in = null;
			try {
				String assetsPath = Config.PLUGIN_ASSETS_DEFAULT_INSTALL_CONFIG_FILE;
//				in = getAssetsInputStream(application, assetsPath);
				in = application.getAssets().open(Config.PLUGIN_ASSETS_DEFAULT_INSTALL_CONFIG_FILE);
				if(in != null)
					defaultPluginList = parsePluginInfos(in);
				else
					Log.e(TAG, "getAssetsInputStream() error,assetsPath="+assetsPath);
			} catch (Exception e) {
				Log.e(TAG, e);
				return null;
			}
		}
		return defaultPluginList;
	}

	private boolean isForceInstallDefaultPlugin = false;

	public void setForceInstallDefaultPlugin(boolean force){
		this.isForceInstallDefaultPlugin = force;
	}

	/**
	 * 安装所有的捆包插件
	 * @param forceInstall 是否强制覆盖安装，强制覆盖安装会导致每次启动都会重新从assets下拷贝安装，这样插件无法从网络升级，但在测试时，方便随时换插件而不用升级版本号。正式使用时，一定要传入false。
	 */
	public void installDefaultPlugins(boolean forceInstall) {

		Log.d(TAG,"installDefaultPlugins()...forceInstall="+forceInstall);
//		SharedPreferences pref = application.getSharedPreferences("PluginManager" , Context.MODE_PRIVATE);

		isDefaultPluginsInstalled = InstallCheck.isFirstInstall(application,"isDefaultPluginsInstalled");

		if(isDefaultPluginsInstalled || forceInstall || isForceInstallDefaultPlugin) {

			InstallCheck.storeVersion(application, "isDefaultPluginsInstalled");

			List<PluginInfo> list = getDefaultPlugins();
			Log.d(TAG, "installDefaultPlugins()...list.size()=" + list.size());
			if (list != null) {
//			for (PluginInfo pi : list) {
//				pi.path = (Config.getPluginDir() + "/" + pi.fileName);
//			}

				for (PluginInfo pi : list) {
					PluginInfo ipi = getInstalledPluginInfo(pi.tag);

					//四种情况需要将插件更新为本地的捆包插件
					boolean isPluginNeedUpdate =
							ipi == null                            //插件未安装
//						|| (!new File(pi.path).isFile())   //插件apk文件不存在
									|| InstallCheck.isFirstInstall(application, "copy_" + pi.tag)  //第一次安装
									|| (PluginUtil.verCompare(pi.versionName, ipi.versionName) > 0)   //插件版本升级
							;

					if (forceInstall || isPluginNeedUpdate) {
						installPluginFromAssets(application, pi);
						InstallCheck.storeVersion(application, "copy_" + pi.tag);
					}
				}

			}


//			isDefaultPluginsInstalled = true;

//			SharedPreferences.Editor editor = pref.edit();
//			editor.putBoolean("isDefaultPluginsInstalled",true);
//			editor.commit();

		}

	}

	public void setLogHandler(ILog logHandler){
		Log.setLogHandler(logHandler);
	}

	public void setLogHandler(DefaultLogHandler logHandler){
		Log.setLogHandler(logHandler);
	}

	public void setDebug(boolean enable){
		Log.setDebug(enable);
	}

	/**
	 * 设置默认更新过滤器
	 * @param defaultUpdateFilter
	 */
	public void setDefaultUpdateFilter(UpdateFilter defaultUpdateFilter){
		installManager.getUpdateManager().setDefaultUpdateFilter(defaultUpdateFilter);
	}

	public Plugin analysisExceptionByClassLoader(Throwable ex){
		if(ex != null) {
			Map<String, Plugin> plugins = PluginManager.getInstance().getPlugins();
			if (plugins != null) {
				for (Plugin p : plugins.values()) {
					DexClassLoaderEx cl = p.getCl();
					if (ex.getStackTrace() != null) {

						for (StackTraceElement element : ex.getStackTrace()) {
							String className = element.getClassName();
							Class<?> clz = null;
							try {
								clz = cl.loadClassOrig(className);
							} catch (ClassNotFoundException e) {
							}
							if (clz != null && clz.getClassLoader() == cl) {
								return p;
							}

						}
					}
				}
			}
			return analysisExceptionByClassLoader(ex.getCause());
		}
		return null;
	}

	/**
	 * 通过在异常中查找包名来确定插件信息
	 * @param ex
	 * @return
	 */
	public Plugin analysisExceptionByPackageName(Throwable ex){
		if(ex != null) {
			String str = android.util.Log.getStackTraceString(ex);
			Map<String, Plugin> plugins = PluginManager.getInstance().getPlugins();
			if (plugins != null) {
				for (Plugin p : plugins.values()) {
					if (str.indexOf(p.getPackageInfo().packageName) > -1) {
						return p;
					}
				}
			}
		}
		return null;
	}

	public Plugin analysisException(Throwable ex){
		try {
			Plugin plugin = analysisExceptionByClassLoader(ex);
			if (plugin == null) {
				return analysisExceptionByPackageName(ex);
			}
			return plugin;
		}catch(Throwable thr){
			Log.e(TAG, thr);
		}
		return null;
	}

	public void postCrash(Plugin plugin,Throwable thr,String remarks){
		try{
			PluginCarshHandler handler = CrashHandlerDispatcher.getInstance().getPluginCarshHandler();
			if(handler != null){
				if(plugin == null && TextUtils.isEmpty(remarks)){
					plugin = analysisException(thr);
				}
				handler.uncaughtException(plugin, Thread.currentThread(), thr,true,remarks);
			}
		}catch(Exception e){
			Log.e(TAG, e);
		}
	}

	public void postCrash(Throwable thr,String remarks){
		postCrash(thr,remarks);
	}






	public void postCrash(Throwable thr){
		postCrash(null,thr,"");
	}


}
