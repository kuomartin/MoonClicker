package com.xaxaxax.relc.hiddenapi;

import android.hardware.display.DisplayManagerHidden;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code VIRTUAL_DISPLAY_FLAG_*} values {@code DisplayManagerHidden} declares, captured as
 * they are actually compiled into callers.
 *
 * <p>This file is Java on purpose. These stubs are {@code public static final int} constant
 * variables, and JLS 13.1 requires a reference to one to be <em>inlined</em> at compile time —
 * the caller never reads the field, it carries a literal. So a wrong value in the stub is baked
 * into the APK and the platform's real value never gets a chance to correct it: the display is
 * created with the wrong flags, silently, with no {@code NoSuchFieldError} to notice. Reading
 * the constants here reproduces exactly that inlining, which is what makes the comparison in
 * {@link VirtualDisplayFlagContractTest} meaningful. Writing it in Kotlin would leave the
 * inlining up to the compiler rather than the language spec.
 *
 * <p>{@code sinceApi} is the API level from which we assert the platform must declare the flag.
 * Below it, a missing flag is reported as a skip rather than a failure — but the value is still
 * compared wherever the platform does have it.
 */
public final class VirtualDisplayFlagTable {

    /** The platform class the stub stands in for. */
    public static final String PLATFORM_OWNER = "android.hardware.display.DisplayManager";

    public static final class Flag {
        public final String name;
        public final int stubValue;
        public final int sinceApi;

        Flag(String name, int stubValue, int sinceApi) {
            this.name = name;
            this.stubValue = stubValue;
            this.sinceApi = sinceApi;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static List<Flag> all() {
        List<Flag> flags = new ArrayList<>();
        // Public SDK since API 19/20 — safe to require from our minSdk.
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_PUBLIC",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC, MIN_API));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_PRESENTATION",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION, MIN_API));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_SECURE",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SECURE, MIN_API));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, MIN_API));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, MIN_API));

        // @hide since well before minSdk.
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD, MIN_API));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH, MIN_API));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT, MIN_API));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL, MIN_API));

        // Each of these arrived with a specific release; RelcV2Service gates on the same levels.
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS,
                Build.VERSION_CODES.Q));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_TRUSTED",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED, Build.VERSION_CODES.R));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP, Build.VERSION_CODES.S));
        // Verified missing on a real API 31 (S) device. API 32 (S_V2) isn't in the device
        // matrix, so whether it actually arrived at 32 or 33 is unconfirmed — Tiramisu is the
        // earliest level we have a real, matching device run for.
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED,
                Build.VERSION_CODES.TIRAMISU));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED,
                Build.VERSION_CODES.TIRAMISU));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS,
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP,
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE));
        flags.add(new Flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED",
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED,
                Build.VERSION_CODES.VANILLA_ICE_CREAM));
        return Collections.unmodifiableList(flags);
    }

    /** Mirrors {@code MIN_SUPPORTED_API}; kept local so this file depends on no Kotlin source. */
    private static final int MIN_API = Build.VERSION_CODES.O_MR1;

    private VirtualDisplayFlagTable() {
    }
}
