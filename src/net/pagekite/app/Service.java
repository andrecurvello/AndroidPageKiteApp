package net.pagekite.app;

import net.pagekite.lib.PageKiteAPI;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.widget.Toast;

public class Service extends android.app.Service {

	public static final String STATUS_UPDATE_INTENT = "net.pagekite.lib.STATUS_UPDATE";
	public static final String STATUS_SERVICE = "svc";
	public static final String STATUS_PAGEKITE = "pagekite";
	public static final String STATUS_COUNT = "count";

	public static final int STATUS_SERVICE_STARTED = 1;
	public static final int STATUS_SERVICE_STOPPED = 2;

	private static final int NOTIFICATION_ID = 1;

	private Notification mNotification = null;
	private static int mStatusCounter = 0;
	public static String mStatusText;
	public static String mStatusTextMore;

	private boolean mKeepRunning = true;
	private String mKiteName = null;
	private Handler mHandler = null;
	private NotificationManager mNotificationManager;

	public Service() {
		mHandler = new Handler();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
	    doStartup();
	    return START_STICKY;
	}

	public String getWiFiIP() {
	   WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
	   WifiInfo wifiInfo = wifiManager.getConnectionInfo();
	   int ip = wifiInfo.getIpAddress();

	   return String.format("%d.%d.%d.%d",
			    			(ip & 0xff),       (ip >> 8 & 0xff),
	                        (ip >> 16 & 0xff), (ip >> 24 & 0xff));
	}

	private void doStartup() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

		String problem = "";
		mKiteName = prefs.getString("kiteName", "kitename");
		String kiteSecret = prefs.getString("kiteSecret", "secret");
		int httpPortNumber = Integer.parseInt(prefs.getString("httpPortNumber", "0"));
		int websocketPortNumber = Integer.parseInt(prefs.getString("websocketPortNumber", "0"));
		int httpsPortNumber = Integer.parseInt(prefs.getString("httpsPortNumber", "0"));
		int sshPortNumber = Integer.parseInt(prefs.getString("sshPortNumber", "0"));

		String localip = "localhost";
		if (prefs.getBoolean("useWiFiIP", false)) {
			localip = getWiFiIP();
		}

		if ((mKiteName == "kitename") || (kiteSecret == "secret")) {
			Toast.makeText(getBaseContext(),
				       getText(R.string.need_account_details),
				       Toast.LENGTH_LONG).show();
			stopSelf();
		}
		else if ((httpPortNumber + websocketPortNumber + httpsPortNumber) == 0) {
			Toast.makeText(getBaseContext(),
				       getText(R.string.need_service_ports),
				       Toast.LENGTH_LONG).show();
			stopSelf();
		}
		else {
			statusUpdateLoop();
			boolean ok = true;
			if (ok) {
				if (prefs.getBoolean("usePageKiteNet", false)) {
					ok = PageKiteAPI.initPagekiteNet(5, 10);
				}
				else {
					ok = PageKiteAPI.init(5, 5, 10, null);
				}
				problem = "Init failed.";
			}
			if (ok && (httpPortNumber > 0)) {
				ok = PageKiteAPI.addKite("http", mKiteName, 0, kiteSecret,
						             	 localip, httpPortNumber);
				problem = "add_kite(http, ...) failed.";
			}
			if (ok && (websocketPortNumber > 0)) {
				ok = PageKiteAPI.addKite("websocket", mKiteName, 0, kiteSecret,
						             	 localip, websocketPortNumber);
				problem = "add_kite(websockets, ...) failed.";
			}
			if (ok && (httpsPortNumber > 0)) {
				ok = PageKiteAPI.addKite("https", mKiteName, 0, kiteSecret,
					   	                 localip, httpsPortNumber);
				problem = "add_kite(https, ...) failed.";
			}
			if (ok && (sshPortNumber > 0)) {
				ok = PageKiteAPI.addKite("raw", mKiteName, 22, kiteSecret,
					   	                 localip, sshPortNumber);
				problem = "add_kite(ssh, ...) failed.";
			}
			if (ok) {
				ok = PageKiteAPI.start();
				problem = "start failed.";
			}
		    if (ok)
			{
				if (prefs.getBoolean("showNotification", false)) {
					mNotification = new Notification(R.drawable.notify_icon,
							getText(R.string.ticker_text),
							System.currentTimeMillis());
					updateStatusText();
					updateNotification(false);
					startForeground(NOTIFICATION_ID, mNotification);
				}
				else {
					Toast.makeText(getBaseContext(),
						       "Started PageKite Service",
						       Toast.LENGTH_LONG).show();					
				}
				setPrefActive(prefs, true);
				announce(STATUS_SERVICE_STARTED, 0);
			}
			else {
				Toast.makeText(getBaseContext(),
						       "PageKite Error: " + problem,
						       Toast.LENGTH_LONG).show();
				setPrefActive(prefs, false);
				stopSelf();
				announce(STATUS_SERVICE_STOPPED, 0);
			}
		}
	}

	void setPrefActive(SharedPreferences prefs, boolean active) {
		Editor editor = prefs.edit();
		editor.putBoolean("enablePageKite", active);
		editor.commit();
	}
	
	void announce(Integer serviceStatus, Integer pagekiteStatus) {
		Intent croak = new Intent(STATUS_UPDATE_INTENT);
		croak.putExtra(STATUS_SERVICE, serviceStatus);
		croak.putExtra(STATUS_PAGEKITE, pagekiteStatus);
		croak.putExtra(STATUS_COUNT, mStatusCounter++);
		sendBroadcast(croak);
	}

	void updateNotification(boolean notify) {
		if (mNotification != null) {
			Intent nfInt = new Intent(this, Preferences.class);
			mNotification.setLatestEventInfo(this,
					mKiteName,
					(mStatusTextMore == null) ? mStatusText : mStatusTextMore,
					PendingIntent.getActivity(this, 0, nfInt, 0));
			if (notify) {
				if (mNotificationManager == null) {
					String ns = Context.NOTIFICATION_SERVICE;
					mNotificationManager = (NotificationManager) getSystemService(ns);					
				}
				if (mNotificationManager != null) {
					mNotificationManager.notify(NOTIFICATION_ID, mNotification);
				}
			}
		}
	}
	
	boolean updateStatusText() {
		String oldStatusText = mStatusText + mStatusTextMore;
		mStatusTextMore = null;
		switch (PageKiteAPI.getStatus()) {
		case PageKiteAPI.PK_STATUS_STARTUP:
			mStatusText = getText(R.string.status_startup).toString();
			break;
		case PageKiteAPI.PK_STATUS_CONNECT:
			mStatusText = getText(R.string.status_connect).toString();
			break;
		case PageKiteAPI.PK_STATUS_DYNDNS:
			mStatusText = getText(R.string.status_dyndns).toString();
			break;
		case PageKiteAPI.PK_STATUS_PROBLEMS:
			mStatusText = getText(R.string.status_problems).toString();
			break;
		case PageKiteAPI.PK_STATUS_REJECTED:
			mStatusText = getText(R.string.status_rejected).toString();
			break;
		case PageKiteAPI.PK_STATUS_FLYING:
			mStatusText = getText(R.string.status_flying).toString();
			mStatusTextMore = 
					getText(R.string.status_frontends) + ": " + PageKiteAPI.getLiveFrontends() + 
					"  " +
					getText(R.string.status_streams) + ": " + PageKiteAPI.getLiveStreams();
			break;
		default:
			mStatusText = getText(R.string.status_unknown).toString();
		}
		return (oldStatusText != (mStatusText + mStatusTextMore));
	}

	void statusUpdateLoop() {
		new Thread(new Runnable() {
	        public void run() {
	        	mKeepRunning = true;
	    		while (mKeepRunning) {
	    			PageKiteAPI.poll(3600);
	    			try {
	    				Thread.sleep(250);
	    			} catch (InterruptedException e) {
	    				// Ignore
	    			}
	    			mHandler.post(new Runnable() {
	    				public void run() {
	    					if (updateStatusText()) {
	    						announce(0, PageKiteAPI.getStatus());
	    						updateNotification(true);
	    					}
	    				}
	    			});
	    		}
	        }
	    }).start();
	}
	
	@Override
	public void onDestroy() {
		mKeepRunning = false;
		mNotification = null;
		stopForeground(true);
		if (PageKiteAPI.stop()) {
			Toast.makeText(getBaseContext(), "Stopped PageKite.",
				       Toast.LENGTH_LONG).show();
		}
		else {
			// Don't complain about this, it probably just means we were
			// stopped already.
		}
		setPrefActive(PreferenceManager.getDefaultSharedPreferences(getBaseContext()),
				      false);
		announce(STATUS_SERVICE_STOPPED, 0);
	}

	@Override
	public IBinder onBind(Intent arg0) {
		// TODO Auto-generated method stub
		return null;
	}
}