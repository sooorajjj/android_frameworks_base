/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telephony;

import android.text.format.Time;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.gsm.SmsCbHeader;

import java.io.UnsupportedEncodingException;
/**
 * Parcelable object containing a received cell broadcast message. There are four different types
 * of Cell Broadcast messages:
 *
 * <ul>
 * <li>opt-in informational broadcasts, e.g. news, weather, stock quotes, sports scores</li>
 * <li>cell information messages, broadcast on channel 50, indicating the current cell name for
 *  roaming purposes (required to display on the idle screen in Brazil)</li>
 * <li>emergency broadcasts for the Japanese Earthquake and Tsunami Warning System (ETWS)</li>
 * <li>emergency broadcasts for the American Commercial Mobile Alert Service (CMAS)</li>
 * </ul>
 *
 * <p>There are also four different CB message formats: GSM, ETWS Primary Notification (GSM only),
 * UMTS, and CDMA. Some fields are only applicable for some message formats. Other fields were
 * unified under a common name, avoiding some names, such as "Message Identifier", that refer to
 * two completely different concepts in 3GPP and CDMA.
 *
 * <p>The GSM/UMTS Message Identifier field is available via {@link #getServiceCategory}, the name
 * of the equivalent field in CDMA. In both cases the service category is a 16-bit value, but 3GPP
 * and 3GPP2 have completely different meanings for the respective values. For ETWS and CMAS, the
 * application should
 *
 * <p>The CDMA Message Identifier field is available via {@link #getSerialNumber}, which is used
 * to detect the receipt of a duplicate message to be discarded. In CDMA, the message ID is
 * unique to the current PLMN. In GSM/UMTS, there is a 16-bit serial number containing a 2-bit
 * Geographical Scope field which indicates whether the 10-bit message code and 4-bit update number
 * are considered unique to the PLMN, to the current cell, or to the current Location Area (or
 * Service Area in UMTS). The relevant values are concatenated into a single String which will be
 * unique if the messages are not duplicates.
 *
 * <p>The SMS dispatcher does not detect duplicate messages. However, it does concatenate the
 * pages of a GSM multi-page cell broadcast into a single SmsCbMessage object.
 *
 * <p>Interested applications with {@code RECEIVE_SMS_PERMISSION} can register to receive
 * {@code SMS_CB_RECEIVED_ACTION} broadcast intents for incoming non-emergency broadcasts.
 * Only system applications such as the CellBroadcastReceiver may receive notifications for
 * emergency broadcasts (ETWS and CMAS). This is intended to prevent any potential for delays or
 * interference with the immediate display of the alert message and playing of the alert sound and
 * vibration pattern, which could be caused by poorly written or malicious non-system code.
 *
 * @hide
 */
public class SmsCbMessage implements Parcelable {

    protected static final String LOG_TAG = "SMSCB";

    private SmsCbHeader mHeader;

    private long mPrimaryNotificationTimestamp;

    private static final int PDU_BODY_PAGE_LENGTH = 82;

    /**
     * Languages in the 0000xxxx DCS group as defined in 3GPP TS 23.038, section 5.
     */
    private static final String[] LANGUAGE_CODES_GROUP_0 = {
            "de", "en", "it", "fr", "es", "nl", "sv", "da", "pt", "fi", "no", "el", "tr", "hu",
            "pl", null
    };

    /**
     * Languages in the 0010xxxx DCS group as defined in 3GPP TS 23.038, section 5.
     */
    private static final String[] LANGUAGE_CODES_GROUP_2 = {
            "cs", "he", "ar", "ru", "is", null, null, null, null, null, null, null, null, null,
            null, null
    };
    private static final char CARRIAGE_RETURN = 0x0d;
    /** Cell wide geographical scope with immediate display (GSM/UMTS only). */
    public static final int GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE = 0;

    /** PLMN wide geographical scope (GSM/UMTS and all CDMA broadcasts). */
    public static final int GEOGRAPHICAL_SCOPE_PLMN_WIDE = 1;

    /** Location / service area wide geographical scope (GSM/UMTS only). */
    public static final int GEOGRAPHICAL_SCOPE_LA_WIDE = 2;

    /** Cell wide geographical scope (GSM/UMTS only). */
    public static final int GEOGRAPHICAL_SCOPE_CELL_WIDE = 3;

    /** GSM or UMTS format cell broadcast. */
    public static final int MESSAGE_FORMAT_3GPP = 1;

    /** CDMA format cell broadcast. */
    public static final int MESSAGE_FORMAT_3GPP2 = 2;

    /** Normal message priority. */
    public static final int MESSAGE_PRIORITY_NORMAL = 0;

    /** Interactive message priority. */
    public static final int MESSAGE_PRIORITY_INTERACTIVE = 1;

    /** Urgent message priority. */
    public static final int MESSAGE_PRIORITY_URGENT = 2;

    /** Emergency message priority. */
    public static final int MESSAGE_PRIORITY_EMERGENCY = 3;

    /** Format of this message (for interpretation of service category values). */
    private int mMessageFormat;

    /** Geographical scope of broadcast. */
    private int mGeographicalScope;

    /**
     * Serial number of broadcast (message identifier for CDMA, geographical scope + message code +
     * update number for GSM/UMTS). The serial number plus the location code uniquely identify
     * a cell broadcast for duplicate detection.
     */
    private int mSerialNumber;

    /**
     * Location identifier for this message. It consists of the current operator MCC/MNC as a
     * 5 or 6-digit decimal string. In addition, for GSM/UMTS, if the Geographical Scope of the
     * message is not binary 01, the Location Area is included for comparison. If the GS is
     * 00 or 11, the Cell ID is also included. LAC and Cell ID are -1 if not specified.
     */
    private SmsCbLocation mLocation;

    /**
     * 16-bit CDMA service category or GSM/UMTS message identifier. For ETWS and CMAS warnings,
     * the information provided by the category is also available via {@link #getEtwsWarningInfo()}
     * or {@link #getCmasWarningInfo()}.
     */
    private int mServiceCategory;

    /** Message language, as a two-character string, e.g. "en". */
    private String mLanguage;

    /** Message body, as a String. */
    private String mBody;

    /** Message priority (including emergency priority). */
    private int mPriority;

    /** ETWS warning notification information (ETWS warnings only). */
    private SmsCbEtwsInfo mEtwsWarningInfo;

    /** CMAS warning notification information (CMAS warnings only). */
    private SmsCbCmasInfo mCmasWarningInfo;

    /**
     * Create an instance of this class from a received PDU
     *
     * @param pdu PDU bytes
     * @return An instance of this class, or null if invalid pdu
     */
    public static SmsCbMessage createFromPdu(byte[] pdu) {
        try {
            return new SmsCbMessage(pdu);
        } catch (IllegalArgumentException e) {
            Log.w(LOG_TAG, "Failed parsing SMS-CB pdu", e);
            return null;
        }
    }

    /** 43 byte digital signature of ETWS primary notification with security. */
    private byte[] mPrimaryNotificationDigitalSignature;

    private SmsCbMessage(byte[] pdu) throws IllegalArgumentException {
        mHeader = new SmsCbHeader(pdu);
        if (mHeader.format == SmsCbHeader.FORMAT_ETWS_PRIMARY) {
            mBody = "ETWS";
            // ETWS primary notification with security is 56 octets in length
            if (pdu.length >= SmsCbHeader.PDU_LENGTH_ETWS) {
                mPrimaryNotificationTimestamp = getTimestampMillis(pdu);
                mPrimaryNotificationDigitalSignature = new byte[43];
                // digital signature starts after 6 byte header and 7 byte timestamp
                System.arraycopy(pdu, 13, mPrimaryNotificationDigitalSignature, 0, 43);
            }
        } else {
            parseBody(pdu);
        }
    }

    private void parseBody(byte[] pdu) {
        int encoding;
        boolean hasLanguageIndicator = false;

        // Extract encoding and language from DCS, as defined in 3gpp TS 23.038,
        // section 5.
        switch ((mHeader.dataCodingScheme & 0xf0) >> 4) {
            case 0x00:
                encoding = SmsMessage.ENCODING_7BIT;
                mLanguage = LANGUAGE_CODES_GROUP_0[mHeader.dataCodingScheme & 0x0f];
                break;

            case 0x01:
                hasLanguageIndicator = true;
                if ((mHeader.dataCodingScheme & 0x0f) == 0x01) {
                    encoding = SmsMessage.ENCODING_16BIT;
                } else {
                    encoding = SmsMessage.ENCODING_7BIT;
                }
                break;

            case 0x02:
                encoding = SmsMessage.ENCODING_7BIT;
                mLanguage = LANGUAGE_CODES_GROUP_2[mHeader.dataCodingScheme & 0x0f];
                break;

            case 0x03:
                encoding = SmsMessage.ENCODING_7BIT;
                break;

            case 0x04:
            case 0x05:
                switch ((mHeader.dataCodingScheme & 0x0c) >> 2) {
                    case 0x01:
                        encoding = SmsMessage.ENCODING_8BIT;
                        break;

                    case 0x02:
                        encoding = SmsMessage.ENCODING_16BIT;
                        break;

                    case 0x00:
                    default:
                        encoding = SmsMessage.ENCODING_7BIT;
                        break;
                }
                break;

            case 0x06:
            case 0x07:
                // Compression not supported
            case 0x09:
                // UDH structure not supported
            case 0x0e:
                // Defined by the WAP forum not supported
                encoding = SmsMessage.ENCODING_UNKNOWN;
                break;

            case 0x0f:
                if (((mHeader.dataCodingScheme & 0x04) >> 2) == 0x01) {
                    encoding = SmsMessage.ENCODING_8BIT;
                } else {
                    encoding = SmsMessage.ENCODING_7BIT;
                }
                break;

            default:
                // Reserved values are to be treated as 7-bit
                encoding = SmsMessage.ENCODING_7BIT;
                break;
        }

        if (mHeader.format == SmsCbHeader.FORMAT_UMTS) {
            // Payload may contain multiple pages
            int nrPages = pdu[SmsCbHeader.PDU_HEADER_LENGTH];

            if (pdu.length < SmsCbHeader.PDU_HEADER_LENGTH + 1 + (PDU_BODY_PAGE_LENGTH + 1)
                    * nrPages) {
                throw new IllegalArgumentException("Pdu length " + pdu.length + " does not match "
                        + nrPages + " pages");
            }

            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < nrPages; i++) {
                // Each page is 82 bytes followed by a length octet indicating
                // the number of useful octets within those 82
                int offset = SmsCbHeader.PDU_HEADER_LENGTH + 1 + (PDU_BODY_PAGE_LENGTH + 1) * i;
                int length = pdu[offset + PDU_BODY_PAGE_LENGTH];

                if (length > PDU_BODY_PAGE_LENGTH) {
                    throw new IllegalArgumentException("Page length " + length
                            + " exceeds maximum value " + PDU_BODY_PAGE_LENGTH);
                }

                sb.append(unpackBody(pdu, encoding, offset, length, hasLanguageIndicator));
            }
            mBody = sb.toString();
        } else {
            // Payload is one single page
            int offset = SmsCbHeader.PDU_HEADER_LENGTH;
            int length = pdu.length - offset;

            mBody = unpackBody(pdu, encoding, offset, length, hasLanguageIndicator);
        }
    }
    /**
     * Create a new SmsCbMessage with the specified data.
     */
    public SmsCbMessage(int messageFormat, int geographicalScope, int serialNumber,
            SmsCbLocation location, int serviceCategory, String language, String body,
            int priority, SmsCbEtwsInfo etwsWarningInfo, SmsCbCmasInfo cmasWarningInfo) {
        mMessageFormat = messageFormat;
        mGeographicalScope = geographicalScope;
        mSerialNumber = serialNumber;
        mLocation = location;
        mServiceCategory = serviceCategory;
        mLanguage = language;
        mBody = body;
        mPriority = priority;
        mEtwsWarningInfo = etwsWarningInfo;
        mCmasWarningInfo = cmasWarningInfo;
    }

    /** Create a new SmsCbMessage object from a Parcel. */
    public SmsCbMessage(Parcel in) {
        mMessageFormat = in.readInt();
        mGeographicalScope = in.readInt();
        mSerialNumber = in.readInt();
        mLocation = new SmsCbLocation(in);
        mServiceCategory = in.readInt();
        mLanguage = in.readString();
        mBody = in.readString();
        mPriority = in.readInt();
        int type = in.readInt();
        switch (type) {
            case 'E':
                // unparcel ETWS warning information
                mEtwsWarningInfo = new SmsCbEtwsInfo(in);
                mCmasWarningInfo = null;
                break;

            case 'C':
                // unparcel CMAS warning information
                mEtwsWarningInfo = null;
                mCmasWarningInfo = new SmsCbCmasInfo(in);
                break;

            default:
                mEtwsWarningInfo = null;
                mCmasWarningInfo = null;
        }
    }

    /**
     * Flatten this object into a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written (ignored).
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMessageFormat);
        dest.writeInt(mGeographicalScope);
        dest.writeInt(mSerialNumber);
        mLocation.writeToParcel(dest, flags);
        dest.writeInt(mServiceCategory);
        dest.writeString(mLanguage);
        dest.writeString(mBody);
        dest.writeInt(mPriority);
        if (mEtwsWarningInfo != null) {
            // parcel ETWS warning information
            dest.writeInt('E');
            mEtwsWarningInfo.writeToParcel(dest, flags);
        } else if (mCmasWarningInfo != null) {
            // parcel CMAS warning information
            dest.writeInt('C');
            mCmasWarningInfo.writeToParcel(dest, flags);
        } else {
            // no ETWS or CMAS warning information
            dest.writeInt('0');
        }
    }

    public static final Parcelable.Creator<SmsCbMessage> CREATOR
            = new Parcelable.Creator<SmsCbMessage>() {
        @Override
        public SmsCbMessage createFromParcel(Parcel in) {
            return new SmsCbMessage(in);
        }

        @Override
        public SmsCbMessage[] newArray(int size) {
            return new SmsCbMessage[size];
        }
    };

    /**
     * Return the geographical scope of this message (GSM/UMTS only).
     *
     * @return Geographical scope
     */
    public int getGeographicalScope() {
        return mGeographicalScope;
    }

    /**
     * Return the broadcast serial number of broadcast (message identifier for CDMA, or
     * geographical scope + message code + update number for GSM/UMTS). The serial number plus
     * the location code uniquely identify a cell broadcast for duplicate detection.
     *
     * @return the 16-bit CDMA message identifier or GSM/UMTS serial number
     */
    public int getSerialNumber() {
        return mSerialNumber;
    }

    /**
     * Return the location identifier for this message, consisting of the MCC/MNC as a
     * 5 or 6-digit decimal string. In addition, for GSM/UMTS, if the Geographical Scope of the
     * message is not binary 01, the Location Area is included. If the GS is 00 or 11, the
     * cell ID is also included. The {@link SmsCbLocation} object includes a method to test
     * if the location is included within another location area or within a PLMN and CellLocation.
     *
     * @return the geographical location code for duplicate message detection
     */
    public SmsCbLocation getLocation() {
        return mLocation;
    }

    /**
     * Return the 16-bit CDMA service category or GSM/UMTS message identifier. The interpretation
     * of the category is radio technology specific. For ETWS and CMAS warnings, the information
     * provided by the category is available via {@link #getEtwsWarningInfo()} or
     * {@link #getCmasWarningInfo()} in a radio technology independent format.
     *
     * @return the radio technology specific service category
     */
    public int getServiceCategory() {
        return mServiceCategory;
    }

    /**
     * Get the ISO-639-1 language code for this message, or null if unspecified
     *
     * @return Language code
     */
    public String getLanguageCode() {
        return mLanguage;
    }

    /**
     * Get the body of this message, or null if no body available
     *
     * @return Body, or null
     */
    public String getMessageBody() {
        return mBody;
    }

    /**
     * Get the message format ({@link #MESSAGE_FORMAT_3GPP} or {@link #MESSAGE_FORMAT_3GPP2}).
     * @return an integer representing 3GPP or 3GPP2 message format
     */
    public int getMessageFormat() {
        return mMessageFormat;
    }

    /**
     * Get the message priority. Normal broadcasts return {@link #MESSAGE_PRIORITY_NORMAL}
     * and emergency broadcasts return {@link #MESSAGE_PRIORITY_EMERGENCY}. CDMA also may return
     * {@link #MESSAGE_PRIORITY_INTERACTIVE} or {@link #MESSAGE_PRIORITY_URGENT}.
     * @return an integer representing the message priority
     */
    public int getMessagePriority() {
        return mPriority;
    }

    /**
     * If this is an ETWS warning notification then this method will return an object containing
     * the ETWS warning type, the emergency user alert flag, and the popup flag. If this is an
     * ETWS primary notification (GSM only), there will also be a 7-byte timestamp and 43-byte
     * digital signature. As of Release 10, 3GPP TS 23.041 states that the UE shall ignore the
     * ETWS primary notification timestamp and digital signature if received.
     *
     * @return an SmsCbEtwsInfo object, or null if this is not an ETWS warning notification
     */
    public SmsCbEtwsInfo getEtwsWarningInfo() {
        return mEtwsWarningInfo;
    }

    /**
     * If this is a CMAS warning notification then this method will return an object containing
     * the CMAS message class, category, response type, severity, urgency and certainty.
     * The message class is always present. Severity, urgency and certainty are present for CDMA
     * warning notifications containing a type 1 elements record and for GSM and UMTS warnings
     * except for the Presidential-level alert category. Category and response type are only
     * available for CDMA notifications containing a type 1 elements record.
     *
     * @return an SmsCbCmasInfo object, or null if this is not a CMAS warning notification
     */
    public SmsCbCmasInfo getCmasWarningInfo() {
        return mCmasWarningInfo;
    }

    /**
     * Return whether this message is an emergency (PWS) message type.
     * @return true if the message is a public warning notification; false otherwise
     */
    public boolean isEmergencyMessage() {
        return mPriority == MESSAGE_PRIORITY_EMERGENCY;
    }

    /**
     * Return whether this message is an ETWS warning alert.
     * @return true if the message is an ETWS warning notification; false otherwise
     */
    public boolean isEtwsMessage() {
        return mEtwsWarningInfo != null;
    }

    /**
     * Return whether this message is a CMAS warning alert.
     * @return true if the message is a CMAS warning notification; false otherwise
     */
    public boolean isCmasMessage() {
        return mCmasWarningInfo != null;
    }

    @Override
    public String toString() {
        return "SmsCbMessage{geographicalScope=" + mGeographicalScope + ", serialNumber="
                + mSerialNumber + ", location=" + mLocation + ", serviceCategory="
                + mServiceCategory + ", language=" + mLanguage + ", body=" + mBody
                + ", priority=" + mPriority
                + (mEtwsWarningInfo != null ? (", " + mEtwsWarningInfo.toString()) : "")
                + (mCmasWarningInfo != null ? (", " + mCmasWarningInfo.toString()) : "") + '}';
    }

    /**
     * Describe the kinds of special objects contained in the marshalled representation.
     * @return a bitmask indicating this Parcelable contains no special objects
     */
    @Override
    public int describeContents() {
        return 0;
    }

        /**
     * Unpack body text from the pdu using the given encoding, position and
     * length within the pdu
     *
     * @param pdu The pdu
     * @param encoding The encoding, as derived from the DCS
     * @param offset Position of the first byte to unpack
     * @param length Number of bytes to unpack
     * @param hasLanguageIndicator true if the body text is preceded by a
     *            language indicator. If so, this method will as a side-effect
     *            assign the extracted language code into mLanguage
     * @return Body text
     */
    private String unpackBody(byte[] pdu, int encoding, int offset, int length,
            boolean hasLanguageIndicator) {
        String body = null;

        switch (encoding) {
            case SmsMessage.ENCODING_7BIT:
                body = GsmAlphabet.gsm7BitPackedToString(pdu, offset, length * 8 / 7);

                if (hasLanguageIndicator && body != null && body.length() > 2) {
                    // Language is two GSM characters followed by a CR.
                    // The actual body text is offset by 3 characters.
                    mLanguage = body.substring(0, 2);
                    body = body.substring(3);
                }
                break;

            case SmsMessage.ENCODING_16BIT:
                if (hasLanguageIndicator && pdu.length >= offset + 2) {
                    // Language is two GSM characters.
                    // The actual body text is offset by 2 bytes.
                    mLanguage = GsmAlphabet.gsm7BitPackedToString(pdu, offset, 2);
                    offset += 2;
                    length -= 2;
                }

                try {
                    body = new String(pdu, offset, (length & 0xfffe), "utf-16");
                } catch (UnsupportedEncodingException e) {
                    // Eeeek
                }
                break;

            default:
                break;
        }

        if (body != null) {
            // Remove trailing carriage return
            for (int i = body.length() - 1; i >= 0; i--) {
                if (body.charAt(i) != CARRIAGE_RETURN) {
                    body = body.substring(0, i + 1);
                    break;
                }
            }
        } else {
            body = "";
        }

        return body;
    }

    /**
     * Append text to the message body. This is used to concatenate multi-page GSM broadcasts.
     * @param body the text to append to this message
     */
    public void appendToBody(String body) {
        mBody = mBody + body;
    }
    private long getTimestampMillis(byte[] pdu) {
        // Timestamp starts after CB header, in pdu[6]
        int year = IccUtils.gsmBcdByteToInt(pdu[6]);
        int month = IccUtils.gsmBcdByteToInt(pdu[7]);
        int day = IccUtils.gsmBcdByteToInt(pdu[8]);
        int hour = IccUtils.gsmBcdByteToInt(pdu[9]);
        int minute = IccUtils.gsmBcdByteToInt(pdu[10]);
        int second = IccUtils.gsmBcdByteToInt(pdu[11]);

        // For the timezone, the most significant bit of the
        // least significant nibble is the sign byte
        // (meaning the max range of this field is 79 quarter-hours,
        // which is more than enough)

        byte tzByte = pdu[12];

        // Mask out sign bit.
        int timezoneOffset = IccUtils.gsmBcdByteToInt((byte) (tzByte & (~0x08)));

        timezoneOffset = ((tzByte & 0x08) == 0) ? timezoneOffset : -timezoneOffset;

        Time time = new Time(Time.TIMEZONE_UTC);

        // It's 2006.  Should I really support years < 2000?
        time.year = year >= 90 ? year + 1900 : year + 2000;
        time.month = month - 1;
        time.monthDay = day;
        time.hour = hour;
        time.minute = minute;
        time.second = second;

        // Timezone offset is in quarter hours.
        return time.toMillis(true) - (timezoneOffset * 15 * 60 * 1000);
    }

}
