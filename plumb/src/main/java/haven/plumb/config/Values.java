package haven.plumb.config;

/** Blank-checking for seam configuration. */
public final class Values {

    private Values() {}

    /**
     * True when a value was actually supplied.
     *
     * Every seam property here is nullable by design: in a cell the bundle
     * injects them all, but locally and in a partially provisioned tenant some
     * are simply absent, and plumb has to report that as SKIPPED rather than die
     * at boot like every other Haven service does. Blank counts as absent — an
     * empty env var is what a Kubernetes secret key that does not exist looks
     * like once it has been through the downward API.
     */
    public static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** True only when every value was supplied. */
    public static boolean allSet(String... values) {
        for (String value : values) {
            if (!isSet(value)) {
                return false;
            }
        }
        return true;
    }
}
