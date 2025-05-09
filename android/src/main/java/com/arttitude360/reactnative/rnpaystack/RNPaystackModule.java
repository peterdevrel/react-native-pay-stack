
package com.arttitude360.reactnative.rnpaystack;

import android.app.Activity;
import android.util.Log;
import android.util.Patterns;

import co.paystack.android.Paystack;
import co.paystack.android.PaystackSdk;
import co.paystack.android.model.Card;
import co.paystack.android.model.Charge;
import co.paystack.android.Transaction;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public RNPaystackModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
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
        this.chargeOptions = null;
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

        int month = -1;
        try {
            month = Integer.parseInt(expiryMonth);
        } catch (Exception ignored) {}

        if (month < 1) {
            rejectPromise("E_INVALID_MONTH", "Invalid expiration month");
            return;
        }

        card.setExpiryMonth(month);

        int year = -1;
        try {
            year = Integer.parseInt(expiryYear);
        } catch (Exception ignored) {}

        if (year < 1) {
            rejectPromise("E_INVALID_YEAR", "Invalid expiration year");
            return;
        }

        card.setExpiryYear(year);

        if (!card.validExpiryDate()) {
            rejectPromise("E_INVALID_DATE", "Invalid expiration date");
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

        if (isEmpty(email)) {
            rejectPromise("E_INVALID_EMAIL", "Email cannot be empty");
            return;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            rejectPromise("E_INVALID_EMAIL", "Invalid email");
            return;
        }

        charge.setEmail(email);

        if (amountInKobo < 1) {
            rejectPromise("E_INVALID_AMOUNT", "Invalid amount");
            return;
        }

        charge.setAmount(amountInKobo);

        // METADATA IMPLEMENTATION USING Map<String, Object>

            if (chargeOptions.hasKey("metadata")) {
                try {
                    ReadableMap metadataMap = chargeOptions.getMap("metadata");
                    JSONArray customFields = new JSONArray();

                    // 1. Handle custom_fields array
                    if (metadataMap.hasKey("custom_fields")) {
                        ReadableArray fields = metadataMap.getArray("custom_fields");
                        for (int i = 0; i < fields.size(); i++) {
                            ReadableMap field = fields.getMap(i);
                            JSONObject fieldJson = new JSONObject();
                            
                            // Required fields
                            fieldJson.put("display_name", field.getString("display_name"));
                            fieldJson.put("variable_name", field.getString("variable_name"));
                            fieldJson.put("value", field.getString("value"));
                            
                            customFields.put(fieldJson);
                        }
                    }

                    // 2. Set as Paystack-compatible format
                    charge.putMetadata("custom_fields", customFields.toString());

                } catch (Exception e) {
                    Log.e(TAG, "Metadata error: " + e.getMessage());
                }
            }


            if (hasStringKey("currency")) {
                charge.setCurrency(chargeOptions.getString("currency"));
            }

            if (hasStringKey("plan")) {
                charge.setPlan(chargeOptions.getString("plan"));
            }

            if (hasStringKey("subAccount")) {
                charge.setSubaccount(chargeOptions.getString("subAccount"));

                if (hasStringKey("bearer") && chargeOptions.getString("bearer") == "subaccount") {
                    charge.setBearer(Charge.Bearer.subaccount);
                }

                if (hasStringKey("bearer") && chargeOptions.getString("bearer") == "account") {
                    charge.setBearer(Charge.Bearer.account);
                }

                if (hasIntKey("transactionCharge")) {
                    charge.setTransactionCharge(chargeOptions.getInt("transactionCharge"));
                }
            }

            if (hasStringKey("reference")) {
                charge.setReference(chargeOptions.getString("reference"));
            }



    }

    private Map<String, Object> convertReadableMap(ReadableMap readableMap) {
        Map<String, Object> map = new HashMap<>();
        ReadableMapKeySetIterator iterator = readableMap.keySetIterator();
        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            switch (readableMap.getType(key)) {
                case Null:
                    map.put(key, null);
                    break;
                case Boolean:
                    map.put(key, readableMap.getBoolean(key));
                    break;
                case Number:
                    map.put(key, readableMap.getDouble(key));
                    break;
                case String:
                    map.put(key, readableMap.getString(key));
                    break;
                case Map:
                    map.put(key, convertReadableMap(readableMap.getMap(key)));
                    break;
                case Array:
                    map.put(key, convertReadableArray(readableMap.getArray(key)));
                    break;
            }
        }
        return map;
    }

    private List<Object> convertReadableArray(ReadableArray readableArray) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < readableArray.size(); i++) {
            switch (readableArray.getType(i)) {
                case Null:
                    list.add(null);
                    break;
                case Boolean:
                    list.add(readableArray.getBoolean(i));
                    break;
                case Number:
                    list.add(readableArray.getDouble(i));
                    break;
                case String:
                    list.add(readableArray.getString(i));
                    break;
                case Map:
                    list.add(convertReadableMap(readableArray.getMap(i)));
                    break;
                case Array:
                    list.add(convertReadableArray(readableArray.getArray(i)));
                    break;
            }
        }
        return list;
    }

    private void createTransaction() {
        transaction = null;
        Activity currentActivity = getCurrentActivity();

        PaystackSdk.chargeCard(currentActivity, charge, new Paystack.TransactionCallback() {
            @Override
            public void onSuccess(Transaction transaction) {
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
                if (transaction.getReference() == null) {
                    rejectPromise("E_TRANSACTION_ERROR", error.getMessage());
                } else {
                    rejectPromise("E_TRANSACTION_ERROR",
                            transaction.getReference() + " concluded with error: " + error.getMessage());
                }
            }
        });
    }

    private boolean isEmpty(String s) {
        return s == null || s.length() < 1;
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