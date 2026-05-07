package com.xaxaxax.relc;

interface IRelcShizukuService {
    boolean setOverlayAllowed(String packageName);
    boolean grantRuntimePermission(String packageName, String permissionName);
}
