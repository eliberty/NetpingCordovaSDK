package com.eliberty.cordova.plugin.nepting;

import android.graphics.Color;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.getsentry.raven.Raven;
import com.getsentry.raven.RavenFactory;

/**
 * CordovaPayzen is a PhoneGap/Cordova plugin that bridges Android intents and MposSDK
 *
 * @author lmenu@eliberty.fr
 *
 */
public class CordovaNepting extends CordovaPlugin
{    
    private static final String SENTRY_DSN = "XXXXX";
    private static final String START_ACTIVITY = "startActivity";
    private static final String TOUCH_INIT_MPOS_IN_ERROR = "TOUCH_INIT_MPOS_IN_ERROR";
    private static final String TOUCH_SDK_NOT_READY = "TOUCH_SDK_NOT_READY";
    private static final String TOUCH_TRANSACTION_MPOS_IN_ERROR = "TOUCH_TRANSACTION_MPOS_IN_ERROR";
    private CallbackContext callbackContext = null;
    private String orderId;
    private static final String PAYMENT_MODE_PRODUCTION = "PRODUCTION";
    private static final String PAYMENT_MODE_TEST = "TEST";
    private static Raven raven;

    /**
     * Executes the request.
     * https://github.com/apache/cordova-android/blob/master/framework/src/org/apache/cordova/CordovaPlugin.java
     *
     * This method is called from the WebView thread. To do a non-trivial amount of work, use:
     *     cordova.getThreadPool().execute(runnable);
     *
     * To run on the UI thread, use:
     *     cordova.getActivity().runOnUiThread(runnable);
     *
     * @param action          The action to execute.
     * @param args            The exec() arguments.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return                Whether the action was valid.
     *
     */
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
    {
        // Init Sentry
        if (raven == null) {
            raven = RavenFactory.ravenInstance(SENTRY_DSN);
        }

        LOG.w("eliberty.cordova.plugin.nepting", "execute Cordova");
        this.callbackContext = callbackContext;
        final JSONArray finalArgs = args;

        if (action.equals(START_ACTIVITY)) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                try {
                    JSONObject obj = finalArgs.getJSONObject(0);                    
                    orderId = obj.has("orderId") ? obj.getString("orderId") : null;                    

                    // Init SDK must done only once
                    runCallbackError(Integer.toString(200), "development in progress");
                }
                catch (JSONException ex) {
                    raven.sendException(ex);
                    LOG.w("eliberty.cordova.plugin.payzen", "JSONException: " + ex.getMessage());
                }                
                catch (Exception e) {
                    raven.sendException(e);
                    LOG.w("eliberty.cordova.plugin.payzen", "MposSDK.init() fail", e);
                    runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, e.getMessage());
                }
                }
            });
        }

        return true;
    }

    /**
     * Return a Json object for the cordova's callback in case of mpos error
     *
     * @param code The code error
     * @param message The message error
     */
    private void runCallbackError(String code, String message)
    {
        try {
            LOG.w("eliberty.cordova.plugin.payzen", "call error callback runCallbackError");
            JSONObject obj = new JSONObject();
            obj.put("code", code);
            obj.put("message", message);

            synchronized(callbackContext){
                callbackContext.error(obj);
                callbackContext.notify();
            }
        }
        catch (JSONException jse) {
            raven.sendException(jse);
            LOG.w("eliberty.cordova.plugin.payzen", "JSONException : " + jse.getMessage());
        }
        catch (Exception ex) {
            LOG.w("eliberty.cordova.plugin.payzen", "Exception", ex);
            raven.sendException(ex);
        }
    }
}
