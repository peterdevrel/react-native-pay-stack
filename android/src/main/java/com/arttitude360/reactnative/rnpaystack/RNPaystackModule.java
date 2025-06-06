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
import java.util.Iterator;

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

    private static RNPaystackModule sInstance = null;

    public static RNPaystackModule getInstance() {
        return sInstance;
    }

    public RNPaystackModule(ReactApplicationContext reactContext) {
        super(reactContext);

        this.reactContext = reactContext;
        sInstance = this;

        // Initialize PaystackSdk
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

    @ReactMethod
    public void chargeCardWithAccessCode(ReadableMap cardData, final Promise promise) {

        this.chargeOptions = null;

        this.pendingPromise = promise;
        this.chargeOptions = cardData;

        validateAccessCodeTransaction();

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

        // build card object with ONLY the number, update the other fields later
        card = new Card.Builder(cardNumber, 0, 0, "").build();

        if (!card.validNumber()) {
            rejectPromise("E_INVALID_NUMBER", "Invalid card number");
            return;
        }

        // validate cvc
        if (isEmpty(cvc)) {
            rejectPromise("E_INVALID_CVC", "Empty CVC");
            return;
        }

        // update the cvc field of the card
        card.setCvc(cvc);

        // check that it's valid
        if (!card.validCVC()) {
            rejectPromise("E_INVALID_CVC", "Invalid CVC");
            return;
        }

        int month = -1;
        try {
            month = Integer.parseInt(expiryMonth);
        } catch (Exception ignored) {
        }

        // validate expiry month
        if (month < 1) {
            rejectPromise("E_INVALID_MONTH", "Invalid expiration month");
            return;
        }

        // update the expiryMonth field of the card
        card.setExpiryMonth(month);

        int year = -1;
        try {
            year = Integer.parseInt(expiryYear);
        } catch (Exception ignored) {
        }

        // validate expiry year
        if (year < 1) {
            rejectPromise("E_INVALID_YEAR", "Invalid expiration year");
            return;
        }

        // update the expiryYear field of the card
        card.setExpiryYear(year);

        // validate expiry
        if (!card.validExpiryDate()) {
            rejectPromise("E_INVALID_DATE", "Invalid expiration date");
            return;
        }
    }

    private void validateAccessCodeTransaction() {
        String cardNumber = chargeOptions.getString("cardNumber");
        String expiryMonth = chargeOptions.getString("expiryMonth");
        String expiryYear = chargeOptions.getString("expiryYear");
        String cvc = chargeOptions.getString("cvc");

        validateCard(cardNumber, expiryMonth, expiryYear, cvc);

        charge = new Charge();
        charge.setCard(card);

        if (hasStringKey("accessCode")) {
            charge.setAccessCode(chargeOptions.getString("accessCode"));
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



        if (chargeOptions.hasKey("metadata")) {
            try {
                ReadableMap metadataMap = chargeOptions.getMap("metadata");
                JSONObject paystackMetadata = new JSONObject();
                
                // 1. Process custom_fields array
                if (metadataMap.hasKey("custom_fields")) {
                    JSONArray customFieldsArray = new JSONArray();
                    ReadableArray fields = metadataMap.getArray("custom_fields");
                    
                    for (int i = 0; i < fields.size(); i++) {
                        ReadableMap field = fields.getMap(i);
                        JSONObject fieldJson = new JSONObject();
                        
                        if (field.hasKey("display_name")) {
                            fieldJson.put("display_name", field.getString("display_name"));
                        }
                        if (field.hasKey("variable_name")) {
                            fieldJson.put("variable_name", field.getString("variable_name"));
                        }
                        if (field.hasKey("value")) {
                            switch (field.getType("value")) {
                                case Number:
                                    fieldJson.put("value", field.getDouble("value"));
                                    break;
                                case String:
                                    fieldJson.put("value", field.getString("value"));
                                    break;
                                case Boolean:
                                    fieldJson.put("value", field.getBoolean("value"));
                                    break;
                                default:
                                    fieldJson.put("value", field.getString("value"));
                            }
                        }
                        customFieldsArray.put(fieldJson);
                    }
                    // Add as stringified JSON array
                    charge.putMetadata("custom_fields", customFieldsArray.toString());
                }
                
                // 2. Process other metadata fields
                ReadableMapKeySetIterator iterator = metadataMap.keySetIterator();
                while (iterator.hasNextKey()) {
                    String key = iterator.nextKey();
                    if (!key.equals("custom_fields")) {
                        switch (metadataMap.getType(key)) {
                            case Number:
                                charge.putMetadata(key, String.valueOf(metadataMap.getDouble(key)));
                                break;
                            case String:
                                charge.putMetadata(key, metadataMap.getString(key));
                                break;
                            case Boolean:
                                charge.putMetadata(key, String.valueOf(metadataMap.getBoolean(key)));
                                break;
                        }
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Metadata processing failed", e);
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

                // This is called only after transaction is successful
                RNPaystackModule.this.transaction = transaction;

                WritableMap map = Arguments.createMap();
                map.putString("reference", transaction.getReference());

                resolvePromise(map);
            }

            @Override
            public void beforeValidate(Transaction transaction) {
                // This is called only before requesting OTP
                // Save reference so you may send to server if
                // error occurs with OTP
                RNPaystackModule.this.transaction = transaction;
            }

            @Override
            public void onError(Throwable error, Transaction transaction) {
                RNPaystackModule.this.transaction = transaction;

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