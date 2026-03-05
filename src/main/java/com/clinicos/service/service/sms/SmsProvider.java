package com.clinicos.service.service.sms;

/**
 * Abstraction for SMS providers (Fast2SMS, MSG91, Twilio, etc.).
 * Swap providers by changing clinicos.sms.provider property.
 */
public interface SmsProvider {

    /**
     * Send an OTP SMS to the given phone number using DLT template.
     *
     * @param phoneNumber  10-digit mobile number (without country code)
     * @param otp          the OTP value to substitute in the template
     * @return result containing success/failure and provider metadata
     * @throws SmsProviderException on errors
     */
    SmsResult sendOtp(String phoneNumber, String otp);
}
