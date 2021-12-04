package com.eliberty.cordova.plugin.nepting;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.WindowManager.LayoutParams;
import android.widget.EditText;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
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
import com.nepting.common.client.model.ExtendedResult;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
 */
public class CordovaNepting extends CordovaPlugin implements UICallback {
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
    private String terminalIp;
    private String terminalType;

    private NepClient nepClient;
    private Currency defaultCurrency;

    private String actionResult = null;
    private AlertDialog matbdf = null;
    private AlertDialog mdf = null;
    private AlertDialog kedf = null;

    private Integer iterateTransactionEnded;
    private Integer iterateLoginEnded;
    private LoginRequest loginRequest = null;
    private Logger logger = Logger.getLogger("CordovaLog");

    /**
     * Executes the request.
     * https://github.com/apache/cordova-android/blob/master/framework/src/org/apache/cordova/CordovaPlugin.java
     * <p>
     * This method is called from the WebView thread. To do a non-trivial amount of work, use:
     * cordova.getThreadPool().execute(runnable);
     * <p>
     * To run on the UI thread, use:
     * cordova.getActivity().runOnUiThread(runnable);
     *
     * @param action          The action to execute.
     * @param args            The exec() arguments.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return Whether the action was valid.
     */
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        logger.log(Level.INFO, "execute Cordova");
        this.callbackContext = callbackContext;

        if (action.equals(START_ACTIVITY)) {
            logger.log(Level.INFO, "START_ACTIVITY");

            // Init Sentry
            ctx = cordova.getActivity().getApplicationContext();

            Runnable task = initBankTransaction(args);
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
    private Runnable initBankTransaction(JSONArray finalArgs) {
        return () -> {
            try {
                logger.log(Level.INFO, "initBankTransaction");

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
                terminalIp = obj.getString("terminalIp");
                terminalType = obj.getString("terminalType");

                sentry = Sentry.init(dsn, new AndroidSentryClientFactory(ctx));
                sentry.getContext().addExtra("deviceId", deviceId);
                sentry.getContext().addExtra("orderId", orderId);
                sentry.getContext().addExtra("params", obj);
                sentry.getContext().addExtra("terminalIp", terminalIp);
                sentry.getContext().addExtra("terminalType", terminalType);
                sentry.getContext().addExtra("nepwebUrl", nepwebUrl);
                sentry.getContext().addExtra("merchantId", merchantId);

                defaultCurrency = new Currency("EUR", 2, 978);

                startNeptingSDK();
            } catch (Exception e) {
                logger.log(Level.SEVERE,  "Nepting fail", e);
                this.extractLogToSentry();
                sentry.sendException(e);
                runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, e.getMessage());
            }
        };
    }

    /**
     *
     * @return LoginRequest
     */
    private LoginRequest getLoginRequest() {
        String[] nepWebUrlList = {nepwebUrl};

        if (terminalType.equals("SPm20")) { // USB or Bluetooth
            logger.log(Level.INFO, "terminal Type SPm20");
            loginRequest = new LoginRequest(merchantId, nepWebUrlList, LoadBalancingAlgorithm.FIRST_ALIVE, null);
        }

        if (terminalType.equals("SPp30")) { // IP
            logger.log(Level.INFO, "terminal Type SPp30");
            loginRequest = new LoginRequest(merchantId, nepWebUrlList, LoadBalancingAlgorithm.FIRST_ALIVE, terminalIp);
        }

        if (loginRequest == null) {
            runCallbackError(TOUCH_INIT_MPOS_IN_ERROR, "The type of terminal is not known");
        }

        return loginRequest;
    }

    /**
     * start Nepting SDK
     */
    private void startNeptingSDK() {
        Context ctx = cordova.getActivity().getApplicationContext();
        nepClient = new AllPosClient(this, logger, ctx, false);
        loginRequest = getLoginRequest();

        logger.log(Level.INFO, "nepClient.login : " + loginRequest.toString());

        iterateTransactionEnded = 0;
        iterateLoginEnded = 0;

        nepClient.login(loginRequest);
    }

    /**
     * Return a Json object for the cordova's callback in case of mpos error
     *
     * @param code    The code error
     * @param message The message error
     */
    private void runCallbackError(String code, String message) {
        try {
            this.closeNeptingSDK();

            logger.log(Level.INFO, "call error callback runCallbackError");
            JSONObject obj = new JSONObject();
            obj.put("code", code);
            obj.put("message", message);

            PluginResult result = new PluginResult(PluginResult.Status.ERROR, obj);
            result.setKeepCallback(true);

            this.extractLogToSentry();
            sentry.sendMessage("Payment Error with code : " + code + " - " + orderId + "/" + deviceId);

            callbackContext.sendPluginResult(result);
        } catch (JSONException jse) {
            logger.log(Level.SEVERE, "JSONException : " + jse.getMessage());
            this.extractLogToSentry();
            sentry.sendException(jse);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Exception", ex);
            this.extractLogToSentry();
            sentry.sendException(ex);
        }
    }

    /**
     * Return a Json object for the cordova's callback in case of mpos success
     *
     * @param code    String
     * @param message String
     * @param params  JSONObject
     */
    private void runCallbackSuccess(String code, String message, JSONObject params) {
        try {
            logger.log(Level.INFO, "call success callback runCallbackSuccess");
            JSONObject obj = new JSONObject();
            obj.put("code", code);
            obj.put("message", message);

            JSONObject merged = merge(obj, params);

            logger.log(Level.INFO, "runCallbackSuccess merged : " + merged.toString());

            PluginResult result = new PluginResult(PluginResult.Status.OK, merged);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);
        } catch (JSONException jse) {
            logger.log(Level.INFO, "JSONException : " + jse.getMessage());
            this.extractLogToSentry();
            sentry.sendException(jse);

            runCallbackError(Integer.toString(jse.hashCode()), jse.getMessage());
        } catch (Exception ex) {
            logger.log(Level.INFO, "Exception", ex);
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

        for (JSONObject temp : jsonObjects) {
            Iterator<String> keys = temp.keys();
            while (keys.hasNext()) {
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
        logger.log(Level.INFO, "transactionEnded : " + transactionResponse.toString());

        try {
            JSONObject params = new JSONObject();

            params.put("transactionId", transactionResponse.getMerchantTransactionId());
            params.put("authorizationId", transactionResponse.getAuthorizationCode() + "_" + transactionResponse.getAuthorizationNumber());
            params.put("receipt", transactionResponse.getCustomerTicket());
            params.put("transactionDate", transactionResponse.getDateTime());

            logger.log(Level.INFO, "transactionEnded params : " + params.toString());

            if (transactionResponse.getGlobalStatus().toString().toUpperCase().equals("SUCCESS") && 1 == iterateTransactionEnded) {
                logger.log(Level.INFO, "transactionEnded with SUCCESS");
                runCallbackSuccess(transactionResponse.getGlobalStatus().toString().toUpperCase(), "", params);
                this.closeNeptingSDK();
            } else if (transactionResponse.getExtendedResultList().contains(ExtendedResult.Sys_NepsaTimeoutCompletion.getCode()) && 0 == iterateTransactionEnded) {

                logger.log(Level.INFO, "transactionEnded with Sys_NepsaTimeoutCompletion get LAST_TRANSACTION");
                iterateTransactionEnded++;
                TransactionRequest transactionRequest = new TransactionRequest(TransactionType.LAST_TRANSACTION, 0, defaultCurrency, false, Long.toString(System.currentTimeMillis()));
                transactionRequest.setMerchantTransactionId(orderId);
                nepClient.startTransaction(transactionRequest);
            } else {
                logger.log(Level.INFO, "transactionEnded " + transactionResponse.getGlobalStatus().toString().toUpperCase());
                this.extractLogToSentry();

                EventBuilder eventBuilder = new EventBuilder()
                        .withMessage("Payment " + transactionResponse.getGlobalStatus().toString().toUpperCase() + " - " + orderId + "/" + deviceId + " " + transactionResponse.getExtendedResultList().toString())
                        .withLevel(Event.Level.INFO)
                        .withExtra("params", params)
                        .withLogger(logger.getName());

                sentry.sendEvent(eventBuilder);

                runCallbackSuccess(transactionResponse.getGlobalStatus().toString().toUpperCase(), "", params);
                this.closeNeptingSDK();
            }
        } catch (JSONException ex) {
            logger.log(Level.SEVERE, "JSONException: " + ex.getMessage());
            this.extractLogToSentry();
            sentry.sendException(ex);
            runCallbackError(Integer.toString(ex.hashCode()), ex.getMessage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception: " + e.getMessage());
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
        logger.log(Level.INFO, "loginEnded : " + loginResponse.toString());

        List<Integer> retryCodeError = new ArrayList<Integer>();
        retryCodeError.add(ExtendedResult.Sys_ConnectionError.getCode());
        retryCodeError.add(ExtendedResult.Sys_CommunicationError.getCode());

        if (loginResponse.isSuccessful()) {
            logger.log(Level.INFO, "loginEnded success");
            // Process Transaction
            TransactionRequest transactionRequest = new TransactionRequest(TransactionType.DEBIT, amount, defaultCurrency, false, Long.toString(System.currentTimeMillis()));
            transactionRequest.setPrivateData("orderID:" + orderId + "&deviveID:" + deviceId);
            transactionRequest.setMerchantTransactionId(orderId);
            nepClient.startTransaction(transactionRequest);
        } else if (loginResponse.getExtendedResultList().containsAll(retryCodeError) && 0 == iterateLoginEnded) {
            iterateLoginEnded++;
            nepClient.login(loginRequest);
        } else {
            logger.log(Level.INFO, "loginEnded failed : " + loginResponse.getGlobalStatus().toString());
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
        logger.log(Level.INFO, "fetchLocalTransactionListEnded : " + integer);
    }

    /**
     * This method is called by the client once the getTerminalInformation method ended (successfully or not).
     * Not implemented by e-Libyerty
     *
     * @param terminalInformation TerminalInformation
     */
    @Override
    public void getTerminalInformationEnded(TerminalInformation terminalInformation) {
        logger.log(Level.INFO, "getTerminalInformationEnded : " + terminalInformation.toString());

        logger.log(Level.INFO, "getClientVersion : " + terminalInformation.getClientVersion());
        logger.log(Level.INFO, "getFirmwareVersion : " + terminalInformation.getFirmwareVersion());
        logger.log(Level.INFO, "getModel : " + terminalInformation.getModel());
        logger.log(Level.INFO, "getSerialNumber : " + terminalInformation.getSerialNumber());
        logger.log(Level.INFO, "getSupplier : " + terminalInformation.getSupplier());
        logger.log(Level.INFO, "getOfflineTransactionsCount : " + terminalInformation.getOfflineTransactionsCount());
    }

    /**
     * This method is used to call a UI Request:
     * - display a text (MESSAGE mode)
     * - display a question and 1 or 2 buttons (QUESTION interactive mode)
     * - display a menu (MENU interactive mode)
     * - display a text, edit a text and 2 buttons (KEYS ENTRY interactive mode)
     * <p>
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

        logger.log(Level.INFO, "postUIRequest : " + action.getActionType() + " message : " + action.getMessage());

        if (action.getActionType().equals(ActionType.MESSAGE)) {
            logger.log(Level.INFO, "postUIRequest 1 TYPE: " + action.getActionType() + " message : " + action.getMessage());
            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());
            return null;
        } else if (action.getActionType().equals(ActionType.QUESTION)) {
            logger.log(Level.INFO, "postUIRequest 2 TYPE: " + action.getActionType() + " message : " + action.getMessage());

            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());

            cordova.getActivity().runOnUiThread(getModalQuestion());
        } else if (action.getActionType().equals(ActionType.KEYS_ENTRY)) {
            logger.log(Level.INFO, "postUIRequest 2 TYPE: " + action.getActionType() + " message : " + action.getMessage());

            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());

            cordova.getActivity().runOnUiThread(getModalKeysEntry());
        } else if (action.getActionType().equals(ActionType.MENU)) {
            logger.log(Level.INFO, "postUIRequest 2 TYPE: " + action.getActionType() + " message : " + action.getMessage());

            runCallbackSuccess(MESSAGE, action.getMessage(), new JSONObject());

            cordova.getActivity().runOnUiThread(getModalMenu());
        } else {
            logger.log(Level.INFO, "Unknown action " + action.getActionType());
            return null;
        }

        while (timer > 0 && actionResult == null) {
            logger.log(Level.INFO, "postUIRequest iteration");
            timer--;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logger.log(Level.INFO, "postUIRequest InterruptedException" + e.getMessage());
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
            logger.log(Level.INFO, "Authentication only for question for the moment");
            return actionResult;
        }

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

            logger.log(Level.INFO, "getModalQuestion nbelement : " + action.getLabelList().length);

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

            logger.log(Level.INFO, "postUIRequest before AlertDialog show");

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

            logger.log(Level.INFO, "postUIRequest before AlertDialog show");

            kedf = alert.show();
        };
    }

    /**
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

            logger.log(Level.INFO, "postUIRequest before AlertDialog show");

            mdf = alert.show();
        };
    }

    /**
     * Extract data Log
     */
    private void extractLogToSentry() {
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
            logger.log(Level.INFO, "IOException " + e.getMessage());
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
            logger.log(Level.INFO, "closeNeptingSDK Exception : " + e.getMessage());
        }
    }

    /**
     * Close SDK onDestroy Cordova Plugin
     */
    public void onDestroy() {
        this.closeNeptingSDK();
    }
}
