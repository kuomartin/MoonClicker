package android.os;


/**
 * Hidden in SDK. Please use AIDL in implementation.
 */
public interface IRemoteCallback {
    void sendResult(Bundle data);
}