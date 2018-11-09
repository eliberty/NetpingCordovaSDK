package com.eliberty.cordova.plugin.nepting;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.WindowManager.LayoutParams;
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
import com.nepting.common.client.callback.ActionType;
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
import java.security.InvalidParameterException;
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
    private static final String START_ACTIVITY = "startActivity";
    private static final String TOUCH_INIT_MPOS_IN_ERROR = "TOUCH_INIT_MPOS_IN_ERROR";
    private static final String MESSAGE = "MESSAGE";
    private static final String LOGIN_FAILED = "LOGIN_FAILED";


    private CallbackContext callbackContext = null;

    private Long amount;
    private String merchantId;
    private String nepwebUrl;
    private String orderId;
    private String deviceId;

    private static Raven raven;
    private NepClient nepClient;
    public Logger logger;
    public Integer timer = 2500;
    public String actionResult = null;

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


        LOG.w("eliberty.cordova.plugin.nepting", "execute Cordova");
        this.callbackContext = callbackContext;

        if (action.equals(START_ACTIVITY)) {
            LOG.w("eliberty.cordova.plugin.nepting", "START_ACTIVITY");

            Runnable task = getTask(args);

            cordova.getActivity().runOnUiThread(task);
        }

        return true;
    }

    /**
     * Return a runnable task
     *
     * @param finalArgs JSONArray
     * @return Runnable
     */
    private Runnable getTask(JSONArray finalArgs) {
        return () -> {
            try {
                cordova.getActivity().getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
                JSONObject obj = finalArgs.getJSONObject(0);

                if (!obj.has("amount")) {
                    throw new InvalidParameterException("amount parameter was empty");
                }

                if (!obj.has("orderId")) {
                    throw new InvalidParameterException("orderId parameter was empty");
                }

                if (!obj.has("deviceId")) {
                    throw new InvalidParameterException("deviceId parameter was empty");
                }

                if (!obj.has("nepwebUrl")) {
                    throw new InvalidParameterException("nepwebUrl parameter was empty");
                }

                if (!obj.has("merchantId")) {
                    throw new InvalidParameterException("merchantId parameter was empty");
                }

                if (!obj.has("sentryDsn")) {
                    throw new InvalidParameterException("sentryDsn parameter was empty");
                }

                orderId = obj.getString("orderId");
                deviceId = obj.getString("deviceId");
                amount = obj.getLong("amount");
                nepwebUrl = obj.getString("nepwebUrl");
                merchantId = obj.getString("merchantId");
                String sentryDsn = obj.getString("sentryDsn");

                // Init Sentry
                raven = RavenFactory.ravenInstance(sentryDsn);

                Sentry.getContext().addExtra("deviceId", deviceId);
                Sentry.getContext().addExtra("orderId", orderId);
                Sentry.getContext().addExtra("params", obj);

                startNeptingSDK();
            }
            catch (Exception e) {
                LOG.w("eliberty.cordova.plugin.nepting", "Nepting fail", e);
                if (raven != null) raven.sendException(e);
                runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, e.getMessage());
            }
        };
    }

    /**
     * start Nepting SDK
     */
    private void startNeptingSDK()
    {
        Context ctx = cordova.getActivity().getApplicationContext();

        nepClient = new AllPosClient(this, logger, ctx, false);
        String[] nepWebUrlList = { nepwebUrl };

        LoginRequest request = new LoginRequest(merchantId, nepWebUrlList, LoadBalancingAlgorithm.FIRST_ALIVE, null);

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

            PluginResult result = new PluginResult(PluginResult.Status.ERROR, obj);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);
        }
        catch (JSONException jse) {
            raven.sendException(jse);
            LOG.w("eliberty.cordova.plugin.nepting", "JSONException : " + jse.getMessage());
            runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, jse.getMessage());
        }
        catch (Exception ex) {
            LOG.w("eliberty.cordova.plugin.nepting", "Exception", ex);
            raven.sendException(ex);
            runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, ex.getMessage());
        }
    }

    /**
     * Return a Json object for the cordova's callback in case of mpos success
     *
     * @param code String
     * @param message String
     * @param params JSONObject
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

    /**
     * Static method who return a merge JSONObject from more JSONObject
     *
     * @param jsonObjects JSONObject...
     * @return JSONObject
     * @throws JSONException JSONException
     */
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
     * This method is called by the client once a transaction has been completed (successfully or not)
     *
     * @param transactionResponse TransactionResponse
     */
    @Override
    public void transactionEnded(TransactionResponse transactionResponse) {
        LOG.w("eliberty.cordova.plugin.nepting", "transactionEnded : " + transactionResponse.toString());

        try {
            JSONObject params = new JSONObject();

            params.put("transactionId", transactionResponse.getMerchantTransactionId());
            params.put("authorizationId", transactionResponse.getAuthorizationCode() + "_" + transactionResponse.getAuthorizationNumber());
            params.put("receipt", transactionResponse.getCustomerTicket());
            params.put("transactionDate", transactionResponse.getDateTime());

            LOG.w("eliberty.cordova.plugin.nepting", "transactionEnded params : " + params.toString());
            runCallbackSuccess(transactionResponse.getGlobalStatus().toUpperCase(), "", params);

            if (nepClient != null) {
                nepClient.logoff();
                nepClient.interrupt();
            }
        }
        catch (JSONException ex) {
            LOG.w("eliberty.cordova.plugin.nepting", "JSONException: " + ex.getMessage());
            raven.sendException(ex);
            runCallbackError(Integer.toString(ex.hashCode()), ex.getMessage());
        }
        catch (Exception e) {
            LOG.w("eliberty.cordova.plugin.nepting", "Exception: " + e.getMessage());
            raven.sendException(e);
            runCallbackError(Integer.toString(e.hashCode()), e.getMessage());
        }
    }

    /**
     * This method is called by the client once login phase has been completed (successfully or not)
     *
     * @param loginResponse LoginResponse
     */
    @Override
    public void loginEnded(LoginResponse loginResponse) {
        LOG.w("eliberty.cordova.plugin.nepting", "loginEnded : " + loginResponse.toString());

        if (loginResponse.isSuccessful())
        {
            LOG.w("eliberty.cordova.plugin.nepting", "loginEnded success");
            // Process Transaction
            Currency defaultCurrency = new Currency("EUR", 2, 978);
            TransactionType transactionType = TransactionType.DEBIT;
            TransactionRequest transactionRequest = new TransactionRequest(transactionType, amount, defaultCurrency, false, Long.toString(System.currentTimeMillis()));
            transactionRequest.setPrivateData("orderID:" + orderId + "&deviveID:" + deviceId);
            nepClient.startTransaction(transactionRequest);
        } else {
            LOG.w("eliberty.cordova.plugin.nepting", "loginEnded failed : " + loginResponse.getGlobalStatus().toString());
            // Display error message
            runCallbackSuccess(LOGIN_FAILED, "", new JSONObject());
        }
    }

    /**
     * This method is called by the client once the fetchLocalTransactionList method ended (successfully or not).
     * Not implemented by e-Libyerty
     *
     * @param integer int
     */
    @Override
    public void fetchLocalTransactionListEnded(int integer) {
        LOG.w("eliberty.cordova.plugin.nepting", "fetchLocalTransactionListEnded : " + integer);
    }

    /**
     * This method is called by the client once the getTerminalInformation method ended (successfully or not).
     * Not implemented by e-Libyerty
     *
     * @param terminalInformation TerminalInformation
     */
    @Override
    public void getTerminalInformationEnded(TerminalInformation terminalInformation) {
        LOG.w("eliberty.cordova.plugin.nepting", "getTerminalInformationEnded : " + terminalInformation.toString());
    }

    /**
     * This method is used to call a UI Request:
     * - display a text (MESSAGE mode)
     * - display a question and 1 or 2 buttons (QUESTION interactive mode)
     * - display a menu (MENU interactive mode)
     * - display a text, edit a text and 2 buttons (KEYS ENTRY interactive mode)
     *
     * Possible results for interactive modes are:
     * - timeout - null string (in all interactive modes)
     * - clicked button label (in QUESTION mode)
     * - selected menu item (in MENU mode)
     * - clicked button label and typed text - separated by a semicolon (in KEYS ENTRY mode)
     *
     * @param uiRequest UIRequest
     * @return String
     */
    @Override
    public String postUIRequest(UIRequest uiRequest) {
        LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest : " + uiRequest.getActionType() + " message : " + uiRequest.getMessage());

        if (uiRequest.getActionType().equals(ActionType.MESSAGE)) {
            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest 1 TYPE: " + uiRequest.getActionType() + " message : " + uiRequest.getMessage());
            runCallbackSuccess(MESSAGE, uiRequest.getMessage(), new JSONObject());
            return uiRequest.toString();
        } else {
            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest 2 TYPE: " + uiRequest.getActionType() + " message : " + uiRequest.getMessage());

            runCallbackSuccess(MESSAGE, uiRequest.getMessage(), new JSONObject());

            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    AlertDialog.Builder alert = new AlertDialog.Builder(cordova.getActivity());

                    alert.setTitle(uiRequest.getActionType().toString());
                    alert.setMessage(uiRequest.getMessage());
                    alert.setPositiveButton(uiRequest.getLabelList()[0], new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            runCallbackSuccess(MESSAGE, uiRequest.getLabelList()[0], new JSONObject());

                            dialog.dismiss();
                            actionResult = uiRequest.getLabelList()[0];
                        }
                    });

                    alert.setNegativeButton(uiRequest.getLabelList()[1], new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            runCallbackSuccess(MESSAGE, uiRequest.getLabelList()[1], new JSONObject());

                            dialog.dismiss();
                            actionResult = uiRequest.getLabelList()[1];
                        }
                    });

                    LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest before AlertDialog show");

                    AlertDialog dialog = alert.show();
                    cordova.getActivity().getWindow().addFlags(LayoutParams.TYPE_SYSTEM_ALERT);
                }
            });

            while (timer > 0 && actionResult == null) {
                LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest iteration");
                timer--;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest InterruptedException" + e.getMessage());
                    e.printStackTrace();
                }
            }

            return actionResult;
        }
    }
}
