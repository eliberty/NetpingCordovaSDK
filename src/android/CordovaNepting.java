package com.eliberty.cordova.plugin.nepting;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.WindowManager.LayoutParams;
import android.widget.EditText;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.InvalidParameterException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.sentry.Sentry;
import io.sentry.SentryClient;
import io.sentry.android.AndroidSentryClientFactory;
import io.sentry.event.BreadcrumbBuilder;
import io.sentry.event.Event;
import io.sentry.event.EventBuilder;


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

    private UIRequest action;
    private CallbackContext callbackContext = null;

    private Long amount;
    private String merchantId;
    private String nepwebUrl;
    private String orderId;
    private String deviceId;
    private SentryClient sentry;
    private Context ctx;
    private String dsn;

    private NepClient nepClient;
    public Logger logger;

    private String actionResult = null;
    private AlertDialog matbdf = null;
    private AlertDialog mdf = null;
    private AlertDialog kedf = null;

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

            // Init Sentry
            ctx = cordova.getActivity().getApplicationContext();

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
                LOG.w("eliberty.cordova.plugin.nepting", "getTask");

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
                    throw new InvalidParameterException("SentryDsn parameter was empty");
                }

                orderId = obj.getString("orderId");
                deviceId = obj.getString("deviceId");
                amount = obj.getLong("amount");
                nepwebUrl = obj.getString("nepwebUrl");
                merchantId = obj.getString("merchantId");

                dsn = obj.getString("sentryDsn");

                LOG.w("eliberty.cordova.plugin.nepting", "Sentry DSN DSN:" + dsn);
                sentry = Sentry.init(dsn, new AndroidSentryClientFactory(ctx));

                sentry.getContext().addExtra("deviceId", deviceId);
                sentry.getContext().addExtra("orderId", orderId);
                sentry.getContext().addExtra("params", obj);

                startNeptingSDK();
            }
            catch (Exception e) {
                LOG.w("eliberty.cordova.plugin.nepting", "Nepting fail", e);
                this.extractLogToSentry();
                sentry.sendException(e);
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

        Logger logger2 = Logger.getLogger("CordovaLog");

        nepClient = new AllPosClient(this, logger2, ctx, false);

        // Workaround software to init MPOS
//        nepClient.getTerminalInformation();

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
            this.closeNeptingSDK();

            LOG.w("eliberty.cordova.plugin.nepting", "call error callback runCallbackError");
            JSONObject obj = new JSONObject();
            obj.put("code", code);
            obj.put("message", message);

            PluginResult result = new PluginResult(PluginResult.Status.ERROR, obj);
            result.setKeepCallback(true);

            this.extractLogToSentry();
            sentry.sendMessage("runCallbackError with code : " + code + " and message : " + message);

            callbackContext.sendPluginResult(result);
        }
        catch (JSONException jse) {
            LOG.w("eliberty.cordova.plugin.nepting", "JSONException : " + jse.getMessage());
            this.extractLogToSentry();
            sentry.sendException(jse);
        }
        catch (Exception ex) {
            LOG.w("eliberty.cordova.plugin.nepting", "Exception", ex);
            this.extractLogToSentry();
            sentry.sendException(ex);
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
            LOG.w("eliberty.cordova.plugin.nepting", "JSONException : " + jse.getMessage());
            this.extractLogToSentry();
            sentry.sendException(jse);

            runCallbackError(Integer.toString(jse.hashCode()), jse.getMessage());
        }
        catch (Exception ex) {
            LOG.w("eliberty.cordova.plugin.nepting", "Exception", ex);
            this.extractLogToSentry();
            sentry.sendException(ex);
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


            if (!transactionResponse.getGlobalStatus().toString().toUpperCase().equals("SUCCESS")) {
                this.extractLogToSentry();

                EventBuilder eventBuilder = new EventBuilder()
                        .withMessage("Paiement " + transactionResponse.getGlobalStatus().toString().toUpperCase())
                        .withLevel(Event.Level.INFO)
                        .withExtra("params", params)
                        .withLogger(CordovaNepting.class.getName());

                sentry.sendEvent(eventBuilder);
            }

            runCallbackSuccess(transactionResponse.getGlobalStatus().toString().toUpperCase(), "", params);

            this.closeNeptingSDK();
        }
        catch (JSONException ex) {
            LOG.w("eliberty.cordova.plugin.nepting", "JSONException: " + ex.getMessage());
            this.extractLogToSentry();
            sentry.sendException(ex);
            runCallbackError(Integer.toString(ex.hashCode()), ex.getMessage());
        }
        catch (Exception e) {
            LOG.w("eliberty.cordova.plugin.nepting", "Exception: " + e.getMessage());
            this.extractLogToSentry();
            sentry.sendException(e);
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
            transactionRequest.setMerchantTransactionId(orderId);
            nepClient.startTransaction(transactionRequest);
        } else {
            LOG.w("eliberty.cordova.plugin.nepting", "loginEnded failed : " + loginResponse.getGlobalStatus().toString());
            // Display error message
            runCallbackError(LOGIN_FAILED, "");
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

//        LOG.w("eliberty.cordova.plugin.nepting", "getClientVersion : " + terminalInformation.getClientVersion());
//        LOG.w("eliberty.cordova.plugin.nepting", "getFirmwareVersion : " + terminalInformation.getFirmwareVersion());
//        LOG.w("eliberty.cordova.plugin.nepting", "getModel : " + terminalInformation.getModel());
//        LOG.w("eliberty.cordova.plugin.nepting", "getSerialNumber : " + terminalInformation.getSerialNumber());
//        LOG.w("eliberty.cordova.plugin.nepting", "getSupplier : " + terminalInformation.getSupplier());
//        LOG.w("eliberty.cordova.plugin.nepting", "getOfflineTransactionsCount : " + terminalInformation.getOfflineTransactionsCount());
//
//        String[] nepWebUrlList = { nepwebUrl };
//        LoginRequest request = new LoginRequest(merchantId, nepWebUrlList, LoadBalancingAlgorithm.FIRST_ALIVE, null);
//
//        LOG.w("eliberty.cordova.plugin.nepting", "nepClient.login : " + request.toString());
//        nepClient.login(request);
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
     * @param action UIRequest
     * @return String
     */
    @Override
    public String postUIRequest(UIRequest action) {
        this.action = action;
        this.actionResult = null;
        long timer = action.getTimeoutMs() / 500;

        LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest : " + action.getActionType() + " message : " + action.getMessage());

        if (action.getActionType().equals(ActionType.MESSAGE)) {
            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest 1 TYPE: " + action.getActionType() + " message : " + action.getMessage());
            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());
            return null;
        }
        else if (action.getActionType().equals(ActionType.QUESTION)) {
            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest 2 TYPE: " + action.getActionType() + " message : " + action.getMessage());

            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());

            cordova.getActivity().runOnUiThread(getModalQuestion());
        }
        else if (action.getActionType().equals(ActionType.KEYS_ENTRY)) {
            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest 2 TYPE: " + action.getActionType() + " message : " + action.getMessage());

            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());

            cordova.getActivity().runOnUiThread(getModalKeysEntry());
        }
        else if (action.getActionType().equals(ActionType.MENU)) {
            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest 2 TYPE: " + action.getActionType() + " message : " + action.getMessage());

            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());

            cordova.getActivity().runOnUiThread(getModalMenu());
        }
        else {
            LOG.w("eliberty.cordova.plugin.nepting", "Unknown action "+action.getActionType());
            return null;
        }

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

        if (matbdf != null) {
            matbdf.dismiss();
        }
        if (kedf != null) {
            kedf.dismiss();
        }
        if (mdf != null) {
            mdf.dismiss();
        }

        if (!action.isAuthenticationNeeded()) {
            return actionResult;
        }

        if (action.getActionType() != ActionType.QUESTION) {
            LOG.w("callUIAction", "Authentication only for question for the moment");
            return actionResult;
        }

        // todo: /!\ works with first label
        if (actionResult == null || !actionResult.contentEquals(action.getLabelList()[0])) {
            return actionResult;
        }

        return null;
    }


    /**
     * Return a runnable task
     *
     * @return Runnable
     */
    private Runnable getModalQuestion() {
        return () -> {
            AlertDialog.Builder alert = new AlertDialog.Builder(cordova.getActivity());

            alert.setTitle(action.getActionType().toString());
            alert.setMessage(action.getMessage());

            LOG.w("eliberty.cordova.plugin.nepting", "getModalQuestion nbelement : " + action.getLabelList().length);

            alert.setPositiveButton(action.getLabelList()[0], new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    runCallbackSuccess(MESSAGE, action.getLabelList()[0], new JSONObject());
                    actionResult = action.getLabelList()[0];
                }
            });

            if (action.getLabelList().length > 1) {
                alert.setNegativeButton(action.getLabelList()[1], new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        runCallbackSuccess(MESSAGE, action.getLabelList()[1], new JSONObject());
                        actionResult = action.getLabelList()[1];
                    }
                });
            }

            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest before AlertDialog show");

            matbdf = alert.show();
        };
    }

    /**
     * Return a runnable task
     *
     * @return Runnable
     */
    private Runnable getModalKeysEntry() {
        return () -> {
            final EditText edittext = new EditText(cordova.getActivity());
            AlertDialog.Builder alert = new AlertDialog.Builder(cordova.getActivity());

            alert.setTitle(action.getActionType().toString());
            alert.setMessage(action.getMessage());
            alert.setView(edittext);

            alert.setPositiveButton(action.getLabelList()[0], new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    String editTextValue = edittext.getText().toString();
                    String concat = action.getLabelList()[0];
                    concat = concat + ";" + editTextValue;
                    actionResult = concat;
                    runCallbackSuccess(MESSAGE, concat, new JSONObject());
                }
            });

            alert.setNegativeButton(action.getLabelList()[1], new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    String editTextValue = edittext.getText().toString();
                    String concat = action.getLabelList()[0];
                    concat = concat + ";" + editTextValue;
                    actionResult = concat;
                    runCallbackSuccess(MESSAGE, concat, new JSONObject());
                }
            });

            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest before AlertDialog show");

            kedf = alert.show();
        };
    }

    /**
     *
     * @return Runnable
     */
    private Runnable getModalMenu() {
        return () -> {
            AlertDialog.Builder alert = new AlertDialog.Builder(cordova.getActivity());
            alert.setTitle(action.getActionType().toString());

            alert.setItems(action.getLabelList(), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    // The 'which' argument contains the index position of the selected item
                    actionResult = action.getLabelList()[which];
                    runCallbackSuccess(MESSAGE, actionResult, new JSONObject());
                    dialog.dismiss();
                }
            });

            LOG.w("eliberty.cordova.plugin.nepting", "postUIRequest before AlertDialog show");

            mdf = alert.show();
        };
    }

    /**
     * Extract data Log
     */
    private void extractLogToSentry(){
        int pid = android.os.Process.myPid();
        try {
            String command = String.format("logcat -d | grep cordova");
            Process process = Runtime.getRuntime().exec(command);

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String currentLine = null;

            while ((currentLine = reader.readLine()) != null) {
                if (currentLine != null && currentLine.contains(String.valueOf(pid))) {

                    BreadcrumbBuilder breadcrumbBuilder = new BreadcrumbBuilder();
                    breadcrumbBuilder.setCategory("console");
                    breadcrumbBuilder.setMessage(currentLine);

                    sentry.getContext().recordBreadcrumb(breadcrumbBuilder.build());
                }
            }
        } catch (IOException e) {
            LOG.w("eliberty.cordova.plugin.nepting", "IOException " + e.getMessage());
        }
    }

    /**
     * Launch the logoff and interrupt for Nepting
     */
    private void closeNeptingSDK() {
        try {
            if (nepClient != null) {
                nepClient.logoff();
                nepClient.interrupt();
            }
        } catch (Exception e) {
            LOG.w("eliberty.cordova.plugin.nepting", "closeNeptingSDK Exception : " + e.getMessage());
        }
    }

    /**
     * Close SDK onDestroy Cordova Plugin
     */
    public void onDestroy() {
        this.closeNeptingSDK();
    }
}
