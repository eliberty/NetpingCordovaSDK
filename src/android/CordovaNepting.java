package com.eliberty.cordova.plugin.nepting;

import android.content.Context;
import android.view.WindowManager;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.getsentry.raven.Raven;
import com.getsentry.raven.RavenFactory;
import com.nepting.allpos.controller.AllPosClient;
import com.nepting.common.client.callback.UICallback;
import com.nepting.common.client.callback.UIRequest;
import com.nepting.common.client.controller.NepClient;
import com.nepting.common.client.model.Currency;
import com.nepting.common.client.model.LoadBalancingAlgorithm;
import com.nepting.common.client.model.LoginRequest;
import com.nepting.common.client.model.LoginResponse;
import com.nepting.common.client.model.TerminalInformation;
import com.nepting.common.client.model.TransactionRequest;
import com.nepting.common.client.model.TransactionResponse;
import com.nepting.common.client.model.TransactionType;
//import org.ksoap2.serialization.SoapObject;

import java.util.logging.Logger;

/**
 * Cordovanepting is a PhoneGap/Cordova plugin that bridges Android intents and MposSDK
 *
 * @author lmenu@eliberty.fr
 *
 */
public class CordovaNepting extends CordovaPlugin implements UICallback
{
    private static final String SENTRY_DSN = "https://6f62ec7de29a4447aae73d2234986ef3:d522f320f965466b949715ec9ffd82f4@sentry.io/142434";
    private static final String START_ACTIVITY = "startActivity";
    private static final String TOUCH_INIT_MPOS_IN_ERROR = "TOUCH_INIT_MPOS_IN_ERROR";
    private static final String TOUCH_SDK_NOT_READY = "TOUCH_SDK_NOT_READY";
    private static final String TOUCH_TRANSACTION_MPOS_IN_ERROR = "TOUCH_TRANSACTION_MPOS_IN_ERROR";
    private CallbackContext callbackContext = null;
    private String orderId;
    private static final String PAYMENT_MODE_PRODUCTION = "PRODUCTION";
    private static final String PAYMENT_MODE_TEST = "TEST";
    private static Raven raven;
    public NepClient nepClient;
    public Logger logger;

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

        Context ctx = cordova.getActivity().getApplicationContext();

        LOG.w("eliberty.cordova.plugin.nepting", "execute Cordova");
        this.callbackContext = callbackContext;
        final JSONArray finalArgs = args;

        if (action.equals(START_ACTIVITY)) {
//            cordova.getActivity().runOnUiThread(new Runnable() {
//                public void run() {
                try {
                    JSONObject obj = finalArgs.getJSONObject(0);                    
                    orderId = obj.has("orderId") ? obj.getString("orderId") : null;                    

                    // Init SDK must done only once
//                    runCallbackError(Integer.toString(200), "development in progress");
                    nepClient = new AllPosClient(this, logger, ctx, false);
                    String nepwebUrl = "https://qualif.nepting.com/nepweb/ws?wsdl";
                    String merchantId = "72114671066321610";
                    String[] nepWebUrlList = { nepwebUrl };
//                    nepWebUrlList[0] = nepwebUrl;

                    LoginRequest request = new LoginRequest(merchantId, nepWebUrlList, LoadBalancingAlgorithm.FIRST_ALIVE, null);

//                    cordova.getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                    nepClient.login(request);

                }
                catch (JSONException ex) {
                    raven.sendException(ex);
                    LOG.w("eliberty.cordova.plugin.nepting", "JSONException: " + ex.getMessage());
                }                
                catch (Exception e) {
                    raven.sendException(e);
                    LOG.w("eliberty.cordova.plugin.nepting", "Nepting fail", e);
                    runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, e.getMessage());
                }
//                }
//            });
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
            LOG.w("eliberty.cordova.plugin.nepting", "call error callback runCallbackError");
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
            LOG.w("eliberty.cordova.plugin.nepting", "JSONException : " + jse.getMessage());
        }
        catch (Exception ex) {
            LOG.w("eliberty.cordova.plugin.nepting", "Exception", ex);
            raven.sendException(ex);
        }
    }

    @Override
    public void transactionEnded(TransactionResponse transactionResponse) {
//        this.transactionResponse = transactionResponse;

        if (transactionResponse != null) {
            if (transactionResponse.getGlobalStatus().equalsIgnoreCase("success")) {
                // display transactions status
                // display ticket and enable buttons
            } else {
                // display transactions status
                // display success
            }

            if (nepClient != null) {
                nepClient.logoff();
                nepClient.interrupt();
            }
        }
    }

    @Override
    public void loginEnded(LoginResponse loginResponse) {
        if (loginResponse.getGlobalStatus().toString().equalsIgnoreCase("success"))
        {
            // Process Transaction
            Currency defaultCurrency = new Currency("EUR", 2, 978);
            TransactionType transactionType = TransactionType.DEBIT;
            long amount = 100;
            TransactionRequest transactionRequest = new TransactionRequest(transactionType, amount, defaultCurrency, false, Long.toString(System.currentTimeMillis()));
            nepClient.startTransaction(transactionRequest);
        } else {
            // Display error message
        }
    }

    @Override
    public void fetchLocalTransactionListEnded(int integer) {

    }

    @Override
    public void getTerminalInformationEnded(TerminalInformation terminalInformation) {

    }

    @Override
    public String postUIRequest(UIRequest uiRequest) {
        return "";
    }
}
