package com.xaxaxax.moonclicker.hiddenapi.contracts;

import android.hardware.display.DisplayManagerHidden;
import android.os.Build;
import android.os.PowerManagerHidden;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The compile-time constants {@code :hidden-api}'s stubs declare, captured as they are actually
 * compiled into callers.
 *
 * <p>This file is Java on purpose. These stubs are {@code public static final} constant
 * variables, and JLS 13.1 requires a reference to one to be <em>inlined</em> at compile time —
 * the caller never reads the field, it carries a literal. So a wrong value in the stub is baked
 * into the APK and the platform's real value never gets a chance to correct it: a virtual display
 * is created with the wrong flags, or {@code PowerManager} is handed the wrong reason code,
 * silently, with no {@code NoSuchFieldError} to notice. Reading the constants here reproduces
 * exactly that inlining, which is what makes the comparison in {@code StubConstantContractTest}
 * meaningful. Writing it in Kotlin would leave the inlining up to the compiler rather than the
 * language spec.
 *
 * <p>Stub and platform class names are string literals, never {@code Foo.class}: on a device the
 * stubs are not in the APK, so a class literal would throw {@code NoClassDefFoundError}.
 *
 * <p>{@code sinceApi} is the API level from which we assert the platform must declare the
 * constant. Below it, the platform must not declare it at all.
 */
public final class StubConstantTable {

    public static final class Constant {
        /** The stub class that declares the constant, for {@code StubCoverageTest}. */
        public final String stub;
        /** The platform class the stub stands in for. */
        public final String platformOwner;
        public final String name;
        public final int stubValue;
        public final int sinceApi;

        Constant(String stub, String platformOwner, String name, int stubValue, int sinceApi) {
            this.stub = stub;
            this.platformOwner = platformOwner;
            this.name = name;
            this.stubValue = stubValue;
            this.sinceApi = sinceApi;
        }

        @Override
        public String toString() {
            return platformOwner.substring(platformOwner.lastIndexOf('.') + 1) + "." + name;
        }
    }

    private static final String DISPLAY_MANAGER_STUB = "android.hardware.display.DisplayManagerHidden";
    private static final String DISPLAY_MANAGER = "android.hardware.display.DisplayManager";
    private static final String POWER_MANAGER_STUB = "android.os.PowerManagerHidden";
    private static final String POWER_MANAGER = "android.os.PowerManager";

    public static List<Constant> all() {
        // Public SDK since API 19/20 — safe to require from our minSdk.

        return List.of(displayFlag("VIRTUAL_DISPLAY_FLAG_PUBLIC",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC, MIN_API), displayFlag("VIRTUAL_DISPLAY_FLAG_PRESENTATION",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION, MIN_API), displayFlag("VIRTUAL_DISPLAY_FLAG_SECURE",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SECURE, MIN_API), displayFlag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, MIN_API), displayFlag("VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, MIN_API),

                // @hide since well before minSdk.
                displayFlag("VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD, MIN_API), displayFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH, MIN_API), displayFlag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT, MIN_API), displayFlag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL, MIN_API),

                // Each of these arrived with a specific release; MoonClickerService gates on the same levels.
                displayFlag("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS,
                        Build.VERSION_CODES.Q), displayFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED, Build.VERSION_CODES.R), displayFlag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP, Build.VERSION_CODES.S),
                // Verified missing on a real API 31 (S) device. API 32 (S_V2) isn't in the device
                // matrix, so whether it actually arrived at 32 or 33 is unconfirmed — Tiramisu is the
                // earliest level we have a real, matching device run for.
                displayFlag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED,
                        Build.VERSION_CODES.TIRAMISU), displayFlag("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED,
                        Build.VERSION_CODES.TIRAMISU), displayFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS,
                        Build.VERSION_CODES.UPSIDE_DOWN_CAKE), displayFlag("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP,
                        Build.VERSION_CODES.UPSIDE_DOWN_CAKE), displayFlag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED",
                        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED,
                        Build.VERSION_CODES.UPSIDE_DOWN_CAKE),

                // Reason code VirtualDisplayLifecycle passes to goToSleep; predates minSdk.
                new Constant(POWER_MANAGER_STUB, POWER_MANAGER, "GO_TO_SLEEP_REASON_APPLICATION",
                        PowerManagerHidden.GO_TO_SLEEP_REASON_APPLICATION, MIN_API));
    }

    private static Constant displayFlag(String name, int stubValue, int sinceApi) {
        return new Constant(DISPLAY_MANAGER_STUB, DISPLAY_MANAGER, name, stubValue, sinceApi);
    }

    /** Mirrors {@code MIN_SUPPORTED_API}; kept local so this file depends on no Kotlin source. */
    private static final int MIN_API = Build.VERSION_CODES.O_MR1;

    private StubConstantTable() {
    }
}
