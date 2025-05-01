package com.arttitude360.reactnative.rnpaystack;

import android.app.Activity;
import android.util.Log;
import android.net.Uri;
import android.os.Bundle;
import android.content.Intent;
import android.util.Patterns;

import co.paystack.android.Paystack;
import co.paystack.android.PaystackSdk;
import co.paystack.android.model.Card;
import co.paystack.android.model.Charge;
import co.paystack.android.Transaction;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;


public class RNPaystackModule extends ReactContextBaseJavaModule {

    protected Card card;
    private Charge charge;
    private Transaction transaction;

    private ReactApplicationContext reactContext;
    private Promise pendingPromise;
    private ReadableMap chargeOptions;
    private String mPublicKey;

    public static final String TAG = "RNPaystack";
    public static String REACT_CLASS = "RNPaystackModule";
    private static RNPaystackModule sInstance = null;

    public static RNPaystackModule getInstance() {
        return sInstance;
    }

    public RNPaystackModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        sInstance = this;
        PaystackSdk.initialize(this.reactContext);
    }

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @ReactMethod
    public void init(ReadableMap options) {
        String newPublicKey = options.getString("publicKey");
        if (newPublicKey != null) {
            mPublicKey = newPublicKey;
            PaystackSdk.setPublicKey(newPublicKey);
        }
    }

    @ReactMethod
    public void chargeCard(ReadableMap cardData, final Promise promise) {
        this.pendingPromise = promise;
        this.chargeOptions = cardData;
        validateFullTransaction();

        if (card != null && card.isValid()) {
            try {
                createTransaction();
            } catch (Exception error) {
                rejectPromise("E_CHARGE_ERROR", error.getMessage());
            }
        }
    }

    private void validateFullTransaction() {
        String cardNumber = chargeOptions.getString("cardNumber");
        String expiryMonth = chargeOptions.getString("expiryMonth");
        String expiryYear = chargeOptions.getString("expiryYear");
        String cvc = chargeOptions.getString("cvc");
        String email = chargeOptions.getString("email");
        int amountInKobo = chargeOptions.getInt("amountInKobo");

        validateCard(cardNumber, expiryMonth, expiryYear, cvc);

        charge = new Charge();
        charge.setCard(card);

        if (isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            rejectPromise("E_INVALID_EMAIL", "Invalid or empty email");
            return;
        }

        charge.setEmail(email);

        if (amountInKobo < 1) {
            rejectPromise("E_INVALID_AMOUNT", "Invalid amount");
            return;
        }

        charge.setAmount(amountInKobo);

        if (hasStringKey("currency")) {
            charge.setCurrency(chargeOptions.getString("currency"));
        }

        if (hasStringKey("plan")) {
            charge.setPlan(chargeOptions.getString("plan"));
        }

        if (hasStringKey("subAccount")) {
            charge.setSubaccount(chargeOptions.getString("subAccount"));
            if (hasStringKey("bearer")) {
                String bearer = chargeOptions.getString("bearer");
                if ("subaccount".equals(bearer)) charge.setBearer(Charge.Bearer.subaccount);
                else if ("account".equals(bearer)) charge.setBearer(Charge.Bearer.account);
            }
            if (hasIntKey("transactionCharge")) {
                charge.setTransactionCharge(chargeOptions.getInt("transactionCharge"));
            }
        }

        if (hasStringKey("reference")) {
            charge.setReference(chargeOptions.getString("reference"));
        }

        try {
            if (chargeOptions.hasKey("metadata") && chargeOptions.getType("metadata") == ReadableType.Map) {
                JSONObject metadataJson = convertMapToJson(chargeOptions.getMap("metadata"));
                charge.setMetadata(metadataJson);
            }
        } catch (JSONException e) {
            rejectPromise("E_METADATA_ERROR", "Metadata JSON error: " + e.getMessage());
        }
    }

    private void validateCard(String cardNumber, String expiryMonth, String expiryYear, String cvc) {
        if (isEmpty(cardNumber)) {
            rejectPromise("E_INVALID_NUMBER", "Empty card number");
            return;
        }

        card = new Card.Builder(cardNumber, 0, 0, "").build();
        if (!card.validNumber()) {
            rejectPromise("E_INVALID_NUMBER", "Invalid card number");
            return;
        }

        if (isEmpty(cvc)) {
            rejectPromise("E_INVALID_CVC", "Empty CVC");
            return;
        }

        card.setCvc(cvc);
        if (!card.validCVC()) {
            rejectPromise("E_INVALID_CVC", "Invalid CVC");
            return;
        }

        int month, year;
        try {
            month = Integer.parseInt(expiryMonth);
            year = Integer.parseInt(expiryYear);
        } catch (Exception e) {
            rejectPromise("E_INVALID_DATE", "Invalid expiration date");
            return;
        }

        card.setExpiryMonth(month);
        card.setExpiryYear(year);
        if (!card.validExpiryDate()) {
            rejectPromise("E_INVALID_DATE", "Invalid expiration date");
        }
    }

    private void createTransaction() {
        transaction = null;
        Activity currentActivity = getCurrentActivity();

        PaystackSdk.chargeCard(currentActivity, charge, new Paystack.TransactionCallback() {
            @Override
            public void onSuccess(Transaction transaction) {
                RNPaystackModule.this.transaction = transaction;
                WritableMap map = Arguments.createMap();
                map.putString("reference", transaction.getReference());
                resolvePromise(map);
            }

            @Override
            public void beforeValidate(Transaction transaction) {
                RNPaystackModule.this.transaction = transaction;
            }

            @Override
            public void onError(Throwable error, Transaction transaction) {
                RNPaystackModule.this.transaction = transaction;
                if (transaction != null && transaction.getReference() != null) {
                    rejectPromise("E_TRANSACTION_ERROR", transaction.getReference() + " error: " + error.getMessage());
                } else {
                    rejectPromise("E_TRANSACTION_ERROR", error.getMessage());
                }
            }
        });
    }

    private JSONObject convertMapToJson(ReadableMap readableMap) throws JSONException {
        JSONObject json = new JSONObject();
        ReadableMapKeySetIterator iterator = readableMap.keySetIterator();

        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            ReadableType type = readableMap.getType(key);

            switch (type) {
                case String:
                    json.put(key, readableMap.getString(key));
                    break;
                case Number:
                    json.put(key, readableMap.getDouble(key));
                    break;
                case Boolean:
                    json.put(key, readableMap.getBoolean(key));
                    break;
                case Map:
                    json.put(key, convertMapToJson(readableMap.getMap(key)));
                    break;
                case Array:
                    json.put(key, convertArrayToJson(readableMap.getArray(key)));
                    break;
                case Null:
                    json.put(key, JSONObject.NULL);
                    break;
            }
        }

        return json;
    }

    private JSONArray convertArrayToJson(ReadableArray readableArray) throws JSONException {
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < readableArray.size(); i++) {
            ReadableType type = readableArray.getType(i);

            switch (type) {
                case String:
                    jsonArray.put(readableArray.getString(i));
                    break;
                case Number:
                    jsonArray.put(readableArray.getDouble(i));
                    break;
                case Boolean:
                    jsonArray.put(readableArray.getBoolean(i));
                    break;
                case Map:
                    jsonArray.put(convertMapToJson(readableArray.getMap(i)));
                    break;
                case Array:
                    jsonArray.put(convertArrayToJson(readableArray.getArray(i)));
                    break;
                case Null:
                    jsonArray.put(JSONObject.NULL);
                    break;
            }
        }
        return jsonArray;
    }

    private boolean isEmpty(String s) {
        return s == null || s.length() < 1;
    }

    private boolean hasStringKey(String key) {
        return chargeOptions.hasKey(key) && !chargeOptions.isNull(key) && !chargeOptions.getString(key).isEmpty();
    }

    private boolean hasIntKey(String key) {
        return chargeOptions.hasKey(key) && !chargeOptions.isNull(key) && chargeOptions.getInt(key) > 0;
    }

    private void rejectPromise(String code, String message) {
        if (this.pendingPromise != null) {
            this.pendingPromise.reject(code, message);
            this.pendingPromise = null;
        }
    }

    private void resolvePromise(Object data) {
        if (this.pendingPromise != null) {
            this.pendingPromise.resolve(data);
            this.pendingPromise = null;
        }
    }
}