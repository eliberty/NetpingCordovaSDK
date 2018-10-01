package com.eliberty.cordova.plugin.nepting;

import android.content.Context;
import android.view.WindowManager;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
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

import java.util.Iterator;
import java.util.logging.Logger;

import io.sentry.Sentry;

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
    private static final String MESSAGE = "MESSAGE";

    private CallbackContext callbackContext = null;

    private String orderId;
    private Long amount;
    private String deviceId;
    private String nepwebUrl;
    private String merchantId;

    private static Raven raven;
    private NepClient nepClient;
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

        LOG.w("eliberty.cordova.plugin.nepting", "execute Cordova");
        this.callbackContext = callbackContext;
        final JSONArray finalArgs = args;

        if (action.equals(START_ACTIVITY)) {
            LOG.w("eliberty.cordova.plugin.nepting", "START_ACTIVITY");
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        JSONObject obj = finalArgs.getJSONObject(0);
                        orderId = obj.has("orderId") ? obj.getString("orderId") : null;
                        deviceId = obj.has("deviceId") ? obj.getString("deviceId") : "";
                        amount = Long.parseLong(obj.has("amount") ? obj.getString("amount") : "0");
                        nepwebUrl = obj.has("nepwebUrl") ? obj.getString("nepwebUrl") : "";
                        merchantId = obj.has("merchantId") ? obj.getString("merchantId") : "";

                        Sentry.getContext().addExtra("deviceId", deviceId);
                        Sentry.getContext().addExtra("orderId", orderId);
                        Sentry.getContext().addExtra("params", obj);

                        startNeptingSDK();
                    }
                    catch (JSONException ex) {
                        LOG.w("eliberty.cordova.plugin.nepting", "JSONException: " + ex.getMessage());
                        raven.sendException(ex);
                    }
                    catch (Exception e) {
                        LOG.w("eliberty.cordova.plugin.nepting", "Nepting fail", e);
                        raven.sendException(e);
                        runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, e.getMessage());
                    }
                }
            });
        }

        return true;
    }

    /**
     * Launch Nepting SDK
     */
    private void startNeptingSDK()
    {
        Context ctx = cordova.getActivity().getApplicationContext();

        // Init SDK must done only once
        nepClient = new AllPosClient(this, logger, ctx, false);
        String[] nepWebUrlList = { nepwebUrl };

        LoginRequest request = new LoginRequest(merchantId, nepWebUrlList, LoadBalancingAlgorithm.FIRST_ALIVE, null);

        cordova.getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LOG.w("eliberty.cordova.plugin.nepting", "nepClient.login : " + request.toString());
        nepClient.login(request);
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

    /**
     * Return a Json object for the cordova's callback in case of mpos error
     *
     * @param code
     * @param message
     * @param params
     */
    private void runCallbackSuccess(String code, String message, JSONObject params)
    {
        try {
            LOG.w("eliberty.cordova.plugin.nepting", "call success callback runCallbackSuccess");
            JSONObject obj = new JSONObject();
            obj.put("code", code);
            obj.put("message", message);

            JSONObject merged = merge(obj, params);

            LOG.w("eliberty.cordova.plugin.nepting", "runCallbackSuccess merged : " + merged.toString());

            PluginResult result = new PluginResult(PluginResult.Status.OK, merged);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);
        }
        catch (JSONException jse) {
            Sentry.capture(jse);
            LOG.w("eliberty.cordova.plugin.nepting", "JSONException : " + jse.getMessage());
            runCallbackError(Integer.toString(jse.hashCode()), jse.getMessage());
        }
        catch (Exception ex) {
            LOG.w("eliberty.cordova.plugin.nepting", "Exception", ex);
            Sentry.capture(ex);
            runCallbackError(Integer.toString(ex.hashCode()), ex.getMessage());
        }
    }

    private static JSONObject merge(JSONObject... jsonObjects) throws JSONException {

        JSONObject jsonObject = new JSONObject();

        for(JSONObject temp : jsonObjects){
            Iterator<String> keys = temp.keys();
            while(keys.hasNext()){
                String key = keys.next();
                jsonObject.put(key, temp.get(key));
            }

        }
        return jsonObject;
    }

    /**
     *
     * @param transactionResponse
     */
    @Override
    public void transactionEnded(TransactionResponse transactionResponse) {
        LOG.w("eliberty.cordova.plugin.nepting", "transactionEnded : " + transactionResponse.toString());

        try {
            JSONObject params = new JSONObject();

            if (transactionResponse != null) {
                if (transactionResponse.getGlobalStatus().equalsIgnoreCase("success")) {
                    params.put("transactionId", transactionResponse.getAuthorizationNumber());
                    params.put("transactionUuId", transactionResponse.getAuthorizationCode());
                    params.put("receipt", transactionResponse.getCustomerTicket());
                    params.put("transactionDate", transactionResponse.getDateTime());

                    LOG.w("eliberty.cordova.plugin.nepting", "transactionEnded params : " + params.toString());

                    runCallbackSuccess(transactionResponse.getGlobalStatus().toUpperCase(), "", params);

                } else {
                    runCallbackSuccess(transactionResponse.getGlobalStatus().toUpperCase(), "", params);
                }

                if (nepClient != null) {
                    nepClient.logoff();
                    nepClient.interrupt();
                }
            }
        }
        catch (JSONException ex) {

        }
    }

    /**
     *
     * @param loginResponse
     */
    @Override
    public void loginEnded(LoginResponse loginResponse) {
        LOG.w("eliberty.cordova.plugin.nepting", "loginEnded : " + loginResponse.toString());
        if (loginResponse.getGlobalStatus().toString().equalsIgnoreCase("success"))
        {
            LOG.w("eliberty.cordova.plugin.nepting", "loginEnded success");
            // Process Transaction
            Currency defaultCurrency = new Currency("EUR", 2, 978);
            TransactionType transactionType = TransactionType.DEBIT;
            TransactionRequest transactionRequest = new TransactionRequest(transactionType, amount, defaultCurrency, false, Long.toString(System.currentTimeMillis()));
            nepClient.startTransaction(transactionRequest);
        } else {
            LOG.w("eliberty.cordova.plugin.nepting", "loginEnded failed : " + loginResponse.getGlobalStatus().toString());
            // Display error message
        }
    }

    /**
     *
     * @param integer
     */
    @Override
    public void fetchLocalTransactionListEnded(int integer) {
        LOG.w("eliberty.cordova.plugin.nepting", "fetchLocalTransactionListEnded : " + integer);
    }

    /**
     *
     * @param terminalInformation
     */
    @Override
    public void getTerminalInformationEnded(TerminalInformation terminalInformation) {
        LOG.w("eliberty.cordova.plugin.nepting", "getTerminalInformationEnded : " + terminalInformation.toString());
    }

    /**
     *
     * @param uiRequest
     * @return
     */
    @Override
    public String postUIRequest(UIRequest uiRequest) {
        LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest : " + uiRequest.toString());
        runCallbackSuccess(MESSAGE, uiRequest.getMessage(), new JSONObject());
        return uiRequest.toString();
    }
}
