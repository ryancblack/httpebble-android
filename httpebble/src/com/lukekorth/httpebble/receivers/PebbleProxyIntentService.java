package com.lukekorth.httpebble.receivers;

import static com.getpebble.android.kit.Constants.APP_UUID;
import static com.getpebble.android.kit.Constants.MSG_DATA;
import static com.lukekorth.httpebble.Constants.HTTPEBBLE;
import static com.lukekorth.httpebble.Constants.HTTP_APP_ID_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_COOKIE_DELETE_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_COOKIE_LOAD_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_COOKIE_STORE_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_IS_DST_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_LOCATION_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_REQUEST_ID_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_STATUS_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_TIME_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_TZ_NAME_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_URL_KEY;
import static com.lukekorth.httpebble.Constants.HTTP_UTC_OFFSET_KEY;
import static com.lukekorth.httpebble.Constants.PEBBLE_ADDRESS;

import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.github.kevinsawicki.http.HttpRequest;

public class PebbleProxyIntentService extends IntentService {

	public PebbleProxyIntentService() {
		super("PebbleProxyIntentService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		String data = intent.getStringExtra(MSG_DATA);

		PowerManager mgr = (PowerManager) getSystemService(Context.POWER_SERVICE);
		WakeLock wakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PebbleProxyIntentService");
		wakeLock.acquire();

		try {
			PebbleDictionary pebbleDictionary = PebbleDictionary.fromJson(data);
			PebbleDictionary responseDictionary = new PebbleDictionary();

			// http request
			if (pebbleDictionary.getString(HTTP_URL_KEY) != null) {
				String url = pebbleDictionary.getString(HTTP_URL_KEY);
				pebbleDictionary.remove(HTTP_URL_KEY);

				long requestIdKey = pebbleDictionary.getInteger(HTTP_REQUEST_ID_KEY);
				pebbleDictionary.remove(HTTP_REQUEST_ID_KEY);

				long appIdKey;
				if(pebbleDictionary.getInteger(HTTP_APP_ID_KEY) != null)
					appIdKey = pebbleDictionary.getInteger(HTTP_APP_ID_KEY);
				else
					appIdKey = 0;

				pebbleDictionary.remove(HTTP_APP_ID_KEY);

				HttpRequest response = HttpRequest.post(url).contentType("application/json")
						.header("X-PEBBLE-ID", getSharedPreferences(HTTPEBBLE, 0).getString(PEBBLE_ADDRESS, ""))
						.send(pebbleDictionary.toJsonString());

				JSONObject json = new JSONObject(response.body());
				Iterator<String> keys = json.keys();
				while (keys.hasNext()) {
					String key = keys.next();
					Object value = json.get(key);

					if (value instanceof String)
						responseDictionary.addString(Integer.parseInt(key), (String) value);
					else if (value instanceof Integer)
						responseDictionary.addInt32(Integer.parseInt(key), (Integer) value);
					else if (value instanceof JSONArray) {
						JSONArray arr = (JSONArray) value;
						String width = (String) arr.get(0);
						int val = (Integer) arr.get(1);

						if (width.equals("b"))
							responseDictionary.addInt8(Integer.parseInt(key), (byte) val);
						else if (width.equals("B"))
							responseDictionary.addUint8(Integer.parseInt(key), (byte) val);
						else if (width.equals("s"))
							responseDictionary.addInt16(Integer.parseInt(key), (short) val);
						else if (width.equals("S"))
							responseDictionary.addUint16(Integer.parseInt(key), (short) val);
						else if (width.equals("i"))
							responseDictionary.addInt32(Integer.parseInt(key), val);
						else if (width.equals("I"))
							responseDictionary.addUint32(Integer.parseInt(key), val);
					}
				}

				responseDictionary.addInt16(HTTP_STATUS_KEY, (short) response.code());
				responseDictionary.addInt8(HTTP_URL_KEY, (byte) ((response.ok()) ? 1 : 0));
				responseDictionary.addInt32(HTTP_REQUEST_ID_KEY, (int) requestIdKey);
				responseDictionary.addInt32(HTTP_APP_ID_KEY, (int) appIdKey);
			}
			// timezone infomation
			else if (pebbleDictionary.getUnsignedInteger(HTTP_TIME_KEY) != null) {
				responseDictionary.addInt32(HTTP_TIME_KEY, (int) (System.currentTimeMillis() / 1000));
				responseDictionary.addInt32(HTTP_UTC_OFFSET_KEY,
						TimeZone.getDefault().getOffset(new Date().getTime()) / 1000);
				responseDictionary.addUint8(HTTP_IS_DST_KEY,
						(byte) ((TimeZone.getDefault().inDaylightTime(new Date())) ? 1 : 0));
				responseDictionary.addString(HTTP_TZ_NAME_KEY, TimeZone.getDefault().getID());
			}
			// location information
			else if (pebbleDictionary.getUnsignedInteger(HTTP_LOCATION_KEY) != null) {

			}
			// setting entries in key-value store
			else if (pebbleDictionary.getInteger(HTTP_COOKIE_STORE_KEY) != null) {
				long httpCookieStoreKey = pebbleDictionary.getInteger(HTTP_COOKIE_STORE_KEY);
				responseDictionary.addUint32(HTTP_COOKIE_STORE_KEY, (int) httpCookieStoreKey);
				pebbleDictionary.remove(HTTP_COOKIE_STORE_KEY);

				long httpAppIdKey = pebbleDictionary.getInteger(HTTP_APP_ID_KEY);
				String appKey = Long.toString(httpAppIdKey);
				responseDictionary.addUint32(HTTP_APP_ID_KEY, (int) httpAppIdKey);
				pebbleDictionary.remove(HTTP_APP_ID_KEY);

				SharedPreferences sharedPrefs = getSharedPreferences(appKey, 0);
				Editor editor = sharedPrefs.edit();
				for(PebbleTuple tuple : pebbleDictionary) {
					editor.putString(Integer.toString(tuple.key), PebbleDictionary.serializeTuple(tuple).toString());
				}
				editor.commit();
			}
			// retrieving entries from key-value store
			else if (pebbleDictionary.getInteger(HTTP_COOKIE_LOAD_KEY) != null) {

			}
			// deleting entries from key-value store
			else if (pebbleDictionary.getInteger(HTTP_COOKIE_DELETE_KEY) != null) {
				long httpCookieDeleteKey = pebbleDictionary.getInteger(HTTP_COOKIE_DELETE_KEY);
				responseDictionary.addUint32(HTTP_COOKIE_DELETE_KEY, (int) httpCookieDeleteKey);
				pebbleDictionary.remove(HTTP_COOKIE_DELETE_KEY);

				long httpAppIdKey = pebbleDictionary.getInteger(HTTP_APP_ID_KEY);
				String appKey = Long.toString(httpAppIdKey);
				responseDictionary.addUint32(HTTP_APP_ID_KEY, (int) httpAppIdKey);
				pebbleDictionary.remove(HTTP_APP_ID_KEY);

				SharedPreferences sharedPrefs = getSharedPreferences(appKey, 0);
				Editor editor = sharedPrefs.edit();
				for (PebbleTuple tuple : pebbleDictionary) {
					editor.putString(Integer.toString(tuple.key), null);
				}
				editor.commit();
			}
			// fsync
			else if (pebbleDictionary.getUnsignedInteger(HTTP_COOKIE_LOAD_KEY) != null) {
				responseDictionary.addUint8(HTTP_COOKIE_LOAD_KEY, (byte) 1);

				long httpAppIdKey = pebbleDictionary.getInteger(HTTP_APP_ID_KEY);
				responseDictionary.addUint32(HTTP_APP_ID_KEY, (int) httpAppIdKey);
			}

			if (responseDictionary.size() > 0)
				PebbleKit.sendDataToPebble(this, (UUID) intent.getSerializableExtra(APP_UUID), responseDictionary);

		} catch (JSONException e) {
			Log.w("Pebble", "JSONException: " + e.getMessage());
		}

		wakeLock.release();
	}

}
