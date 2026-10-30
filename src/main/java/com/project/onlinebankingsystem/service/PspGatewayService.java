package com.project.onlinebankingsystem.service;

import com.project.onlinebankingsystem.enums.UpiPayeeType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class PspGatewayService {

    private static final Pattern VPA_PATTERN = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$");

    // Known Verified NPCI Merchant Registry Simulation
    private static final Map<String, VerifiedMerchantInfo> VERIFIED_MERCHANTS = new HashMap<>();

    // Known Bank Handle Directory
    private static final Map<String, String> BANK_HANDLES = new HashMap<>();

    static {
        // Banks
        BANK_HANDLES.put("oksbi", "State Bank of India");
        BANK_HANDLES.put("sbi", "State Bank of India");
        BANK_HANDLES.put("okhdfcbank", "HDFC Bank");
        BANK_HANDLES.put("hdfcbank", "HDFC Bank");
        BANK_HANDLES.put("okicici", "ICICI Bank");
        BANK_HANDLES.put("icici", "ICICI Bank");
        BANK_HANDLES.put("okaxis", "Axis Bank");
        BANK_HANDLES.put("axisbank", "Axis Bank");
        BANK_HANDLES.put("paytm", "Paytm Payments Bank");
        BANK_HANDLES.put("ybl", "Yes Bank (PhonePe PSP)");
        BANK_HANDLES.put("ibl", "ICICI Bank (PhonePe PSP)");
        BANK_HANDLES.put("axl", "Axis Bank (PhonePe PSP)");
        BANK_HANDLES.put("barodampay", "Bank of Baroda");
        BANK_HANDLES.put("pnb", "Punjab National Bank");
        BANK_HANDLES.put("postbank", "India Post Payments Bank");
        BANK_HANDLES.put("apl", "Amazon Pay (Axis Bank PSP)");
        BANK_HANDLES.put("jiopay", "Jio Payments Bank");
        BANK_HANDLES.put("obsbank", "Apex Horizon Bank");
        BANK_HANDLES.put("apexbank", "Apex Horizon Bank");

        // Verified Merchants
        VERIFIED_MERCHANTS.put("swiggy@icici", new VerifiedMerchantInfo("Swiggy Bundl Technologies Pvt Ltd", "5812 - Fast Food & Delivery", "ICICI Bank (NPCI Switch)"));
        VERIFIED_MERCHANTS.put("zomato@hdfcbank", new VerifiedMerchantInfo("Zomato Limited", "5812 - Restaurants & Dining", "HDFC Bank (NPCI Switch)"));
        VERIFIED_MERCHANTS.put("amazon@apl", new VerifiedMerchantInfo("Amazon Seller Services Pvt Ltd", "5311 - Department Stores & E-Commerce", "Amazon Pay PSP"));
        VERIFIED_MERCHANTS.put("flipkart@axisbank", new VerifiedMerchantInfo("Flipkart India Private Limited", "5311 - Online Marketplace", "Axis Bank (NPCI Switch)"));
        VERIFIED_MERCHANTS.put("uber@axisbank", new VerifiedMerchantInfo("Uber India Systems Pvt Ltd", "4121 - Taxicabs & Rideshare", "Axis Bank (NPCI Switch)"));
        VERIFIED_MERCHANTS.put("ola@icici", new VerifiedMerchantInfo("ANI Technologies Pvt Ltd (Ola)", "4121 - Taxicabs & Rideshare", "ICICI Bank (NPCI Switch)"));
        VERIFIED_MERCHANTS.put("irctc@sbi", new VerifiedMerchantInfo("Indian Railway Catering & Tourism Corp (IRCTC)", "4112 - Passenger Railways", "SBI UPI Gateway"));
        VERIFIED_MERCHANTS.put("starbucks@hdfcbank", new VerifiedMerchantInfo("Tata Starbucks Private Limited", "5814 - Fast Food & Beverages", "HDFC Bank (NPCI Switch)"));
        VERIFIED_MERCHANTS.put("reliance@jiopay", new VerifiedMerchantInfo("Reliance Retail Limited", "5411 - Grocery & Retail Supermarkets", "Jio Payments Bank PSP"));
        VERIFIED_MERCHANTS.put("electricity@paytm", new VerifiedMerchantInfo("State Electricity Utility Board (BESCOM)", "4900 - Utilities - Electric & Power", "Paytm Merchant Gateway"));
        VERIFIED_MERCHANTS.put("airtel@axisbank", new VerifiedMerchantInfo("Bharti Airtel Limited", "4814 - Telecommunication Services", "Axis Bank (NPCI Switch)"));
        VERIFIED_MERCHANTS.put("jio@jiopay", new VerifiedMerchantInfo("Reliance Jio Infocomm Limited", "4814 - Telecommunication Services", "Jio Payments Bank PSP"));
    }

    public static class VerifiedMerchantInfo {
        public final String name;
        public final String category;
        public final String bank;

        public VerifiedMerchantInfo(String name, String category, String bank) {
            this.name = name;
            this.category = category;
            this.bank = bank;
        }
    }

    /**
     * Validates VPA format against NPCI specification.
     */
    public boolean isValidVpaFormat(String vpa) {
        if (vpa == null || vpa.isBlank()) {
            return false;
        }
        return VPA_PATTERN.matcher(vpa.trim()).matches();
    }

    /**
     * Checks if VPA is recognized as an On-Us handle belonging to Apex Horizon Bank.
     */
    public boolean isOnUsVpa(String vpa) {
        if (vpa == null) return false;
        String clean = vpa.trim().toLowerCase();
        return clean.endsWith("@obsbank") || clean.endsWith("@apexbank");
    }

    /**
     * Resolves bank name from VPA handle.
     */
    public String resolveBankName(String vpa) {
        if (vpa == null || !vpa.contains("@")) {
            return "UPI Payment Network";
        }
        String handle = vpa.substring(vpa.indexOf("@") + 1).toLowerCase().trim();
        return BANK_HANDLES.getOrDefault(handle, "NPCI Interbank Switch (" + handle.toUpperCase() + ")");
    }

    /**
     * Looks up verified merchant if applicable.
     */
    public VerifiedMerchantInfo getVerifiedMerchant(String vpa) {
        if (vpa == null) return null;
        return VERIFIED_MERCHANTS.get(vpa.trim().toLowerCase());
    }

    /**
     * Determines whether a VPA represents a Merchant (P2M) or an Individual (P2P).
     */
    public UpiPayeeType determinePayeeType(String vpa, String merchantCode) {
        if (vpa == null) return UpiPayeeType.INDIVIDUAL;
        String clean = vpa.trim().toLowerCase();

        if (VERIFIED_MERCHANTS.containsKey(clean)) {
            return UpiPayeeType.MERCHANT;
        }

        if (merchantCode != null && !merchantCode.isBlank() && !merchantCode.equals("0000")) {
            return UpiPayeeType.MERCHANT;
        }

        String usernamePart = clean.contains("@") ? clean.substring(0, clean.indexOf("@")) : clean;
        if (usernamePart.contains("merchant") || usernamePart.contains("store") || usernamePart.contains("pay") ||
                usernamePart.contains("shop") || usernamePart.contains("pos") || usernamePart.contains("mart")) {
            return UpiPayeeType.MERCHANT;
        }

        return UpiPayeeType.INDIVIDUAL;
    }

    /**
     * Formats a human-friendly name from bare VPA when no name was specified in QR.
     * E.g. "rahul.sharma@oksbi" -> "Rahul Sharma"
     */
    public String formatDisplayNameFromVpa(String vpa) {
        if (vpa == null || !vpa.contains("@")) return "Verified UPI Payee";
        String user = vpa.substring(0, vpa.indexOf("@"));
        String[] parts = user.split("[._-]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1).toLowerCase());
            }
        }
        return sb.length() > 0 ? sb.toString() : "Verified UPI Payee";
    }

    /**
     * Generates a 12-digit authentic NPCI Retrieval Reference Number (RRN).
     * Format: Year last digit (1 char) + Julian Day of year (3 chars) + 8-digit secure random sequence.
     * E.g. "628019028341"
     */
    public String generateNpciRrn() {
        LocalDate now = LocalDate.now();
        int yearDigit = now.getYear() % 10;
        int dayOfYear = now.getDayOfYear();
        String prefix = String.format("%d%03d", yearDigit, dayOfYear);

        long randomSeq = (long) (Math.random() * 90000000L) + 10000000L;
        return prefix + randomSeq;
    }

    /**
     * Formats official UPI Transaction Reference.
     * E.g. "UPI/CR/428019028341/APEX/OK"
     */
    public String generateUpiTransactionReference(String rrn) {
        return "UPI/CR/" + (rrn != null ? rrn : generateNpciRrn()) + "/APEX/OK";
    }
}
